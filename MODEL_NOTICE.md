# Model notices — FilterBridge Lab 0.3.2

## Viddexa NSFW Detection 2 Nano
- Repository: https://huggingface.co/viddexa/nsfw-detection-2-nano
- Revision: `12e57200346246b37382f746e4d94d10b014f6a1`
- Repository licence metadata: Apache-2.0; verify provenance and intended commercial use before release.
- Outputs: `safe`, `hentai`, `porn`, `sexy`, `drawing`.
- The publisher describes explicit nudity as its intended use. The `sexy` output is experimental and may not detect suggestive clothing/poses without explicit nudity.
- It is a whole-image classifier: it returns no blur-region coordinates.

## OwenElliott Image Safety Classifier XS
- Repository: https://huggingface.co/OwenElliott/image-safety-classifier-xs
- Revision: `606ad3dfd6a023215e3ab0797040437cc365977b`
- Repository licence metadata: MIT.
- ONNX SHA-256: `8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689`
- Outputs: `NSFL`, `NSFW`, `SFW` (softmax is baked into the graph).
- Its NSFW class is broad: pornography or highly suggestive content. It does not distinguish nudity from suggestive imagery.
- Its publisher describes a proprietary dataset of about 320,000 images scraped from the web and warns that the NSFL class is underrepresented. This provenance should be reviewed before commercial distribution.

Both models are downloaded from pinned Hugging Face revisions by GitHub Actions, SHA-256 checked, tested in CI and packaged into the APK. No model inference requires a network connection. The classifier scores are not independent guarantees or calibrated probabilities. A normal/SFW output is never a complete safety verdict.
