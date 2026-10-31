# Model selection — v0.3.2

## Active experimental image models

1. **Viddexa NSFW Detection 2 Nano** — 5-way explicit-content image classifier. Experimental `sexy` signal; model documentation caveat says suggestive content without explicit nudity may be classified as safe.
2. **OwenElliott Image Safety Classifier XS** — independent 3-way ONNX classifier (`NSFL`, `NSFW`, `SFW`). Its broad NSFW class provides a second opinion for adult visual content but cannot distinguish nudity from suggestive content. NSFL is underrepresented in the publisher-described training data.

A model's `safe`/`SFW` output cannot cancel another model's positive detection. Signals are retained separately; no averaging of incompatible model scores is performed.

## Deferred candidates

- Viddexa Mini: larger comparison; same category definitions/limitations as Nano. Benchmark before bundling.
- Freepik EVA: larger severity model; benchmark/runtime and licence/data provenance review pending.
- NudeNet: do not bundle yet. The PyPI wrapper's MIT metadata does not resolve provenance and licensing of the YOLO model weights/upstream implementation; official upstream artifacts show AGPL licensing. Legal review required.

## Runtime

ONNX Runtime Android CPU is the initial common runtime. Viddexa uses its documented rescale/normalize preprocessing and logits output. Owen XS accepts RGB 224x224 float32 0..255 NCHW, bilinear-resized, with normalization and softmax baked into ONNX. The pipelines are deliberately separate to prevent preprocessor/output-activation mix-ups.
