# Investigation: Viddexa reported `safe = 100%`

## What was checked

1. The pinned Viddexa revision's class order is `safe, hentai, porn, sexy, drawing`.
2. The Java mapper uses that same order.
3. The model export script reads `id2label` from the pinned model config and fails CI if the order changes.
4. The ONNX export exposes raw logits and the Android mapper applies a numerically stable softmax exactly once.
5. The Android preprocessor uses RGB, NCHW, direct resize, rescale and channel normalization from the pinned image-processor manifest.
6. CI's existing PyTorch/ONNX parity test proves graph conversion consistency on synthetic tensors. It does **not** prove semantic accuracy on real images, nor does it fully prove Android Bitmap preprocessing matches PIL pixel-for-pixel.

## Conclusion

No confirmed class-index reversal or double-softmax bug was found in the 0.3.1 source. The observed output may be a model false negative. The Viddexa model card says the model's intended use is explicit nudity and warns that suggestive clothing/risqué imagery without explicit nudity may be classified as safe. If the user's example contains clearly explicit material, the output should be treated as a serious false negative for that test case.

A `safe` class score of 100% is only the top class of that one model. It was incorrectly easy to read as an overall safety verdict. Version 0.3.2 changes the display to `NORMAL (upstream class label: safe)` and explicitly says this is not a safety verdict.

## Changes in 0.3.2

- Adds an independent OwenElliott XS image-safety classifier, pinned to a specific Hugging Face commit and exact ONNX SHA-256.
- Owen produces a broad NSFW signal, not separate nudity and suggestive classes; it is represented by a separate `adult_visual_content` category rather than mislabeled as a specific class.
- Owen's NSFL signal maps to an experimental violence/distress signal. The publisher notes that its NSFL training class is underrepresented.
- Composite analysis preserves positive detections: one model's `NORMAL`/`SFW` result cannot cancel another model's positive finding.
- If both models predict their ordinary-content classes, the result still remains incomplete/UNVERIFIED because enabled categories have no validated coverage.

## Still required before production

- Obtain a controlled, lawful, consented and independently labelled validation set.
- Compare model results on the same held-out examples; calculate precision, recall, F1 and false-negative rates for separate categories.
- Run an Android-vs-Pillow preprocessing parity fixture, including EXIF orientation and alpha-channel cases.
- Measure inference latency, memory and thermal behaviour on target Samsung hardware.
- Independently audit model and dataset provenance/licences before commercial distribution.
