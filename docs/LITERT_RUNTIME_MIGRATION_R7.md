# LiteRT runtime migration — R7 (2026-11-04)

## Why the old runtime was removed

The Galaxy S24 FE API 36 diagnostic reported failure while resolving `task_vision_jni`. Both specialist profiles shared the same Task Vision `0.4.4` EfficientDet loader, so initialization failed before screenshot inference. The previous filesystem-loader correction addressed model-file access but left the Task Vision native library in the APK.

## Runtime decision

R7 removes `org.tensorflow:tensorflow-lite-task-vision:0.4.4` and does not add MediaPipe Tasks. MediaPipe Tasks is not selected because the product's network policy permits explicit model downloads only; the app must not add an SDK telemetry/usage-metrics path.

The two existing `.tflite` models now use the standalone CPU `Interpreter` compatibility API bundled in Google AI Edge LiteRT Android `2.3.2` (`com.google.ai.edge.litert:litert:2.3.2`). The interpreter maps each verified model file from private app storage. No Play Services model runtime, remote inference, or additional telemetry client is used.

This is a deliberately narrow compatibility exception to LiteRT's recommendation to use `CompiledModel` for new applications. R7 initially treated the old MediaPipe EfficientDet asset as a four-output `TFLite_Detection_PostProcess` model. API 34 instrumentation disproved that assumption: the old artifact exposed two outputs and relied on model metadata for external anchor decoding/NMS. That artifact and its R7 four-output contract are retired. The R9 correction below replaces the detector artifact with a separately pinned TensorFlow Hub TFLite deployment and tests its real tensor contract. The buffer-allocation rationale for using the Interpreter API remains: the adapter allocates direct input/output buffers from runtime tensor metadata and rejects mismatched contracts before decoding.

## Buffer contract and failure behavior

`LocalLiteRtCpuModel` calls `allocateTensors()`, checks each model's tensor counts and declared input/output element types (EfficientDet RGB input is UINT8; Selfie Multiclass input is FLOAT32; postprocess outputs are FLOAT32), obtains fixed tensor shapes and runtime byte counts, and rejects dynamic, oversized, or inconsistent tensor allocations. It allocates direct native-order input and output buffers once. Inference reuses those buffers on the existing single-thread model executor. Outputs are copied to owned float arrays before model-specific validation.

The EfficientDet adapter validates its four outputs, count, class indices, scores and normalized boxes before creating candidate person regions. The segmenter validates the exact 256×256×6 output before category mapping. Malformed output throws; it cannot create a permissive region or rectangle fallback.

The R9 detector deployment is separately pinned at a full repository commit and exact file hash. The Selfie Multiclass, OpenNSFW, ViT-Tiny and Gemma artifact pins are unchanged. ScreenVeil does not train, convert, quantize or rewrite model weights.

## Preserved protection policy

This migration does not modify:

- the 35% maximum per-mask coverage and 45% combined-mask coverage;
- the independent watchdog for coverage at or above 35% for 3 seconds;
- the 5-minute maximum protection session;
- the 5-second screenshot callback deadline or 8-second inference deadline;
- the process poison latch after a hard worker timeout;
- body-skin and clothing categories only, with the existing letterbox clipping and dilation;
- the no-rectangular-fallback rule;
- Emergency Stop notification action and stop-only Quick Settings tile;
- the Fast/Balanced/Gemma profile separation.

## Validation status

The source includes real-model instrumentation smoke tests for both pinned `.tflite` artifacts and a CI check for 16 KB ELF LOAD and APK ZIP alignment of packaged 64-bit native libraries. Those tests must run in GitHub Actions on API 34 and API 36, followed by physical Galaxy S24 FE ARM64 testing. A source archive and static checks are not evidence that the APK builds or that either model's accuracy is suitable for content moderation.


## R9 correction — EfficientDet artifact contract (2026-11-04)

The first LiteRT Interpreter CI run showed that the previously pinned MediaPipe model exposes two output tensors, not four. That model uses out-of-graph SSD anchor decoding and NMS configured through `DETECTOR_METADATA`; it is not a raw four-output `TFLite_Detection_PostProcess` model. R7's four-output assumption was incorrect and is superseded by R9.

R9 changes only the EfficientDet artifact to the LiteRT Community TFLite deployment of TensorFlow Hub `tensorflow/efficientdet/lite0/detection/1` at immutable commit `971d935f3679eabbcce7b4d3733f351d403ff2b9`. The upstream model repository identifies it as the TFLite deployment of that model; TensorFlow Hub documents the input as RGB `uint8` pixels in [0,255] and the outputs as boxes, scores, classes and detection count. The catalog pins the full repository commit, exact published file size and SHA-256. The runtime supplies raw RGB `uint8` bytes and continues to reject any model whose tensor count, shape, type or element byte size differs from the fixed four-output contract.

The replacement is not asserted equivalent bit-for-bit to the previous MediaPipe artifact. It is the same EfficientDet-Lite0 COCO detector family with a different deployment/export. The new model's exact CPU output contract must pass the R9 real-model instrumentation test before the APK can be used. The detector category filter, score threshold, person-crop limits, segmentation and protection safety policy are unchanged.
