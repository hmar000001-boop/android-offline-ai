# Test plan — 0.3.2

## CI gates
- Viddexa model download/export, immutable revision and labels validation.
- Owen XS download at immutable revision, exact SHA-256 and file-size verification.
- ONNX input/output contract validation and smoke inference for Owen XS.
- PyTorch-to-ONNX parity checks for Viddexa.
- Core unit tests and Android lint/build.

## Device test
1. Enable Airplane mode and turn Wi-Fi off.
2. Choose a normal photo and run analysis.
3. Verify two model reports appear, not one.
4. Verify Viddexa displays `normal (upstream label safe)` rather than claiming the image is safe.
5. Verify Owen displays `NSFL`, `NSFW`, `SFW` and the highest-class note.
6. Choose a second image where the Viddexa model predicted normal/safe but expected content is adult. Observe whether Owen's broad NSFW score differs. Do not use this as a final benchmark with one image.
7. Confirm that a high NSFW score creates `adult_visual_content` evidence and invokes the configured policy.
8. Confirm that an SFW/normal result from one model never cancels a positive detection from the other.
9. Select a video and verify it remains NOT_ANALYZED in this release.
10. Restore filtering thresholds after any forced-policy tests.

## Accuracy evaluation
Use a legally obtained and consented, labelled test set. Separate explicit nudity, suggestive imagery, ordinary portraits, beach/sports, artwork, anime, medical/non-sexual exposures and common negatives. Record per-class precision, recall, F1, confusion matrix, false positives and false negatives. Never report vendor metrics as FilterBridge accuracy.

## Known tests still required
- Run on the target Samsung phone.
- Compare Android image preprocessing against reference Pillow preprocessing with pixel-level golden fixtures.
- Run a held-out semantic image benchmark; ONNX smoke inference alone does not prove classification quality.
- Measure latency, memory, heat and battery.
