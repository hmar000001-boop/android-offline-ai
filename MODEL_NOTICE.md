# Model notice — Viddexa NSFW Detection 2 Nano

The CI workflow downloads an immutable revision of `viddexa/nsfw-detection-2-nano` from Hugging Face and exports it to ONNX for inclusion in the debug APK.

- Model: `viddexa/nsfw-detection-2-nano`
- Revision: `12e57200346246b37382f746e4d94d10b014f6a1`
- Repository metadata licence: Apache License 2.0
- Upstream model card: https://huggingface.co/viddexa/nsfw-detection-2-nano/tree/12e57200346246b37382f746e4d94d10b014f6a1
- Labels: `safe`, `hentai`, `porn`, `sexy`, `drawing`
- Model card reports publisher evaluation metrics on LSPD; these are not FilterBridge test results.

## Scope limitation

The model card's class descriptions say that `sexy` can include images without explicit nudity but with suggestive nudity or risqué clothing. The same model card's intended-use section says the model is designed to detect explicit nudity and that suggestive nudity or risqué clothing without explicit nudity can be labelled safe. This is a material inconsistency. FilterBridge displays the `sexy` score as an experimental signal but does not treat it as a validated suggestive detector, and does not declare any category fully covered by this adapter.

The classifier is a whole-image classifier. It does not output regions, segmentation masks, body-part boxes, video timestamps, or audio evidence. Video inference is not connected in this release.

The repository metadata declares Apache-2.0. Before commercial redistribution, review all upstream terms, model provenance, training data rights, and the licence of the fine-tuned weights; the model-card licence label is not a substitute for legal review.

No original user image is sent to Hugging Face. The model download happens in GitHub Actions while building the APK. Inference on the phone uses the bundled ONNX file and does not require a network connection.

## Preprocessing validation limit

Android Bitmap resize/colour-conversion parity against the upstream PIL image processor has not yet been measured on a physical device; this must be checked before production use.
