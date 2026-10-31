#!/usr/bin/env python3
"""Download a pinned Hugging Face model, export it to ONNX, and verify parity.

This runs only in build CI. Runtime inference in the Android APK is fully offline.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
from huggingface_hub import snapshot_download
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForImageClassification

MODEL_ID = "viddexa/nsfw-detection-2-nano"
REVISION = "12e57200346246b37382f746e4d94d10b014f6a1"
EXPECTED_LABELS = ["safe", "hentai", "porn", "sexy", "drawing"]
ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "ai-runtime-onnx" / "src" / "main" / "assets" / "models"
WORK_DIR = ROOT / "build" / "viddexa-export"
OUTPUT_MODEL = ASSET_DIR / "viddexa-nano.onnx"
OUTPUT_MANIFEST = ASSET_DIR / "model.properties"


class LogitsOnly(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        return self.model(pixel_values=pixel_values).logits


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def labels_from_config(model: AutoModelForImageClassification) -> list[str]:
    id2label = model.config.id2label
    labels = [str(id2label[index]).strip().lower() for index in range(model.config.num_labels)]
    if labels != EXPECTED_LABELS:
        raise RuntimeError(f"Model labels changed: expected {EXPECTED_LABELS}, received {labels}")
    return labels


def get_dimension(value: object, axis: str) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, dict) and axis in value:
        return int(value[axis])
    raise RuntimeError(f"Unsupported image processor size: {value!r}")


def main() -> int:
    torch.set_num_threads(2)
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Downloading {MODEL_ID} at immutable revision {REVISION}", flush=True)
    snapshot = Path(snapshot_download(
        repo_id=MODEL_ID,
        revision=REVISION,
        local_dir=str(WORK_DIR / "snapshot"),
        allow_patterns=["config.json", "preprocessor_config.json", "model.safetensors", "README.md"],
    ))
    weights = snapshot / "model.safetensors"
    if not weights.is_file() or weights.stat().st_size < 10_000_000:
        raise RuntimeError("Pinned model weights are missing or unexpectedly small.")
    source_weights_sha = sha256(weights)

    processor = AutoImageProcessor.from_pretrained(str(snapshot), use_fast=False)
    model = AutoModelForImageClassification.from_pretrained(
        str(snapshot), use_safetensors=True, local_files_only=True
    ).cpu().eval()
    labels = labels_from_config(model)
    if model.config.num_labels != 5:
        raise RuntimeError("This exporter is pinned to the Viddexa five-class classifier.")

    width = get_dimension(processor.size, "width")
    height = get_dimension(processor.size, "height")
    if (width, height) != (224, 224):
        raise RuntimeError(f"Unexpected preprocessing dimensions: {width}x{height}")
    if getattr(processor, "do_center_crop", False):
        raise RuntimeError("The Android preprocessor does not currently implement center crop.")
    if not processor.do_resize or not processor.do_rescale or not processor.do_normalize:
        raise RuntimeError("The Android preprocessor requires resize, rescale and normalize.")
    if int(processor.resample) != 0:
        raise RuntimeError(f"Unsupported resampling algorithm: {processor.resample}")

    # The dummy image is processed through the exact pinned Hugging Face image processor.
    sample_image = Image.new("RGB", (320, 240), color=(91, 137, 203))
    sample = processor(images=sample_image, return_tensors="pt")["pixel_values"].cpu().contiguous()
    if tuple(sample.shape) != (1, 3, height, width):
        raise RuntimeError(f"Unexpected input tensor shape: {tuple(sample.shape)}")

    wrapper = LogitsOnly(model).eval()
    with torch.no_grad():
        torch_logits = wrapper(sample).cpu().numpy()

    temporary_onnx = OUTPUT_MODEL.with_suffix(".onnx.part")
    if temporary_onnx.exists():
        temporary_onnx.unlink()
    print("Exporting static NCHW ONNX graph (opset 17)", flush=True)
    torch.onnx.export(
        wrapper,
        (sample,),
        str(temporary_onnx),
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["pixel_values"],
        output_names=["logits"],
        dynamic_axes=None,
        dynamo=False,
    )
    onnx_model = onnx.load(str(temporary_onnx), load_external_data=True)
    onnx.checker.check_model(onnx_model)

    session = ort.InferenceSession(str(temporary_onnx), providers=["CPUExecutionProvider"])
    input_names = [entry.name for entry in session.get_inputs()]
    output_names = [entry.name for entry in session.get_outputs()]
    if input_names != ["pixel_values"] or output_names != ["logits"]:
        raise RuntimeError(f"Unexpected ONNX signatures: inputs={input_names}, outputs={output_names}")
    input_meta = session.get_inputs()[0]
    output_meta = session.get_outputs()[0]
    if input_meta.type != "tensor(float)" or input_meta.shape != [1, 3, height, width]:
        raise RuntimeError(f"Unexpected ONNX input contract: type={input_meta.type}, shape={input_meta.shape}")
    if output_meta.type != "tensor(float)" or output_meta.shape != [1, 5]:
        raise RuntimeError(f"Unexpected ONNX output contract: type={output_meta.type}, shape={output_meta.shape}")
    ort_logits = session.run(["logits"], {"pixel_values": sample.numpy()})[0]
    max_abs_error = float(np.max(np.abs(torch_logits - ort_logits)))
    if not np.isfinite(ort_logits).all() or max_abs_error > 1.0e-3:
        raise RuntimeError(f"PyTorch/ONNX parity check failed; max absolute error={max_abs_error}")

    # Confirm a second deterministic tensor so the export is not validated on only one colour.
    generator = np.random.default_rng(20261031)
    random_input = generator.normal(0.0, 1.0, size=(1, 3, height, width)).astype(np.float32)
    with torch.no_grad():
        expected = wrapper(torch.from_numpy(random_input)).cpu().numpy()
    actual = session.run(["logits"], {"pixel_values": random_input})[0]
    random_error = float(np.max(np.abs(expected - actual)))
    if not np.isfinite(actual).all() or random_error > 1.0e-3:
        raise RuntimeError(f"Random-input parity check failed; max absolute error={random_error}")

    mean = [float(value) for value in processor.image_mean]
    std = [float(value) for value in processor.image_std]
    rescale_factor = float(processor.rescale_factor)
    onnx_sha = sha256(temporary_onnx)
    temporary_onnx.replace(OUTPUT_MODEL)

    manifest = {
        "modelId": MODEL_ID,
        "revision": REVISION,
        "license": "apache-2.0 (model repository metadata; review upstream terms before distribution)",
        "sourceWeightsSha256": source_weights_sha,
        "onnxSha256": onnx_sha,
        "exporter": f"torch={torch.__version__};transformers=4.56.1;onnx={onnx.__version__};onnxruntime={ort.__version__}",
        "inputWidth": width,
        "inputHeight": height,
        "inputLayout": "NCHW",
        "colorOrder": "RGB",
        "doRescale": bool(processor.do_rescale),
        "rescaleFactor": rescale_factor,
        "doNormalize": bool(processor.do_normalize),
        "imageMean": ",".join(str(value) for value in mean),
        "imageStd": ",".join(str(value) for value in std),
        "resample": int(processor.resample),
        "labels": ",".join(labels),
        "pytorchOnnxMaxAbsErrorSample": max_abs_error,
        "pytorchOnnxMaxAbsErrorRandom": random_error,
        "coverageNote": "Experimental image classifier. The model card says intended use is explicit nudity; sexy output is not a validated suggestive-content detector.",
    }
    with OUTPUT_MANIFEST.open("w", encoding="utf-8", newline="\n") as target:
        for key, value in manifest.items():
            target.write(f"{key}={value}\n")

    # Preserve the upstream model card beside the generated APK model as attribution material.
    source_card = snapshot / "README.md"
    if source_card.is_file():
        shutil.copyfile(source_card, ASSET_DIR / "VIDDEXA-MODEL-CARD.md")

    summary = {
        "model": MODEL_ID,
        "revision": REVISION,
        "source_weights_sha256": source_weights_sha,
        "onnx_sha256": onnx_sha,
        "onnx_bytes": OUTPUT_MODEL.stat().st_size,
        "labels": labels,
        "input_shape": [1, 3, height, width],
        "output_shape": [1, 5],
        "pytorch_onnx_max_abs_error_sample": max_abs_error,
        "pytorch_onnx_max_abs_error_random": random_error,
        "assets": [str(OUTPUT_MODEL), str(OUTPUT_MANIFEST)],
    }
    summary_path = WORK_DIR / "export-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # CI should fail loudly; it must not make an APK without the model.
        print(f"MODEL_EXPORT_FAILED: {type(error).__name__}: {error}", file=sys.stderr, flush=True)
        raise
