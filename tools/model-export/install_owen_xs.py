#!/usr/bin/env python3
"""Download, pin, validate and package the Owen XS ONNX model during CI only."""
from __future__ import annotations
import hashlib, json, shutil
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
from huggingface_hub import hf_hub_download

MODEL_ID = "OwenElliott/image-safety-classifier-xs"
REVISION = "606ad3dfd6a023215e3ab0797040437cc365977b"
FILENAME = "onnx/image-safety-classifier-xs.onnx"
EXPECTED_SHA256 = "8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689"
EXPECTED_BYTES = 13_137_569
ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "ai-runtime-onnx" / "src" / "main" / "assets" / "models"
OUT = ASSET_DIR / "owen-image-safety-xs.onnx"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {MODEL_ID}@{REVISION}", flush=True)
    downloaded = Path(hf_hub_download(repo_id=MODEL_ID, filename=FILENAME, revision=REVISION))
    actual_hash = sha256(downloaded)
    if actual_hash != EXPECTED_SHA256:
        raise RuntimeError(f"Owen XS SHA-256 mismatch: {actual_hash}")
    if downloaded.stat().st_size != EXPECTED_BYTES:
        raise RuntimeError(f"Owen XS size mismatch: {downloaded.stat().st_size}")
    graph = onnx.load(str(downloaded), load_external_data=True)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(downloaded), providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    if len(inputs) != 1 or inputs[0].name != "image" or inputs[0].type != "tensor(float)":
        raise RuntimeError(f"Unexpected ONNX input: {[(x.name, x.type, x.shape) for x in inputs]}")
    if list(inputs[0].shape) != [1, 3, 224, 224]:
        raise RuntimeError(f"Unexpected input shape: {inputs[0].shape}")
    if len(outputs) != 1 or outputs[0].type != "tensor(float)" or list(outputs[0].shape) != [1, 3]:
        raise RuntimeError(f"Unexpected ONNX output contract: {[(x.name, x.type, x.shape) for x in outputs]}")
    # The model card states that normalization and softmax are already baked into the graph.
    sample = np.full((1, 3, 224, 224), 127.0, dtype=np.float32)
    result = session.run(None, {"image": sample})[0]
    if result.shape != (1, 3) or not np.isfinite(result).all():
        raise RuntimeError("Owen XS model smoke inference produced invalid values.")
    if np.any(result < 0) or np.any(result > 1) or abs(float(result[0].sum()) - 1.0) > 0.03:
        raise RuntimeError(f"Owen XS ONNX output is not a probability vector: {result.tolist()}")
    shutil.copyfile(downloaded, OUT)
    model_card = Path(hf_hub_download(repo_id=MODEL_ID, filename="README.md", revision=REVISION))
    shutil.copyfile(model_card, ASSET_DIR / "OWEN-IMAGE-SAFETY-MODEL-CARD.md")
    manifest = {
        "modelId": MODEL_ID,
        "revision": REVISION,
        "license": "MIT (Hugging Face repository metadata; model/data provenance still requires production review)",
        "onnxSha256": EXPECTED_SHA256,
        "onnxBytes": EXPECTED_BYTES,
        "inputName": "image",
        "inputWidth": 224,
        "inputHeight": 224,
        "inputLayout": "NCHW",
        "colorOrder": "RGB",
        "preprocessing": "BILINEAR_RGB_0_255",
        "outputActivation": "SOFTMAX_BAKED_IN",
        "labels": "NSFL,NSFW,SFW",
        "outputName": outputs[0].name,
        "note": "NSFW is broad; NSFL training class is underrepresented; output scores are not independently calibrated."
    }
    props = ASSET_DIR / "owen-image-safety-xs.properties"
    with props.open("w", encoding="utf-8", newline="\n") as stream:
        for key, value in manifest.items(): stream.write(f"{key}={value}\n")
    summary = {"model": MODEL_ID, "revision": REVISION, "onnx_sha256": EXPECTED_SHA256,
               "onnx_bytes": EXPECTED_BYTES, "input": [1,3,224,224], "output": [1,3],
               "labels": ["NSFL", "NSFW", "SFW"], "smoke_output": result[0].tolist()}
    summary_path = ROOT / "build" / "owen-xs-export-summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2), flush=True)

if __name__ == "__main__":
    main()
