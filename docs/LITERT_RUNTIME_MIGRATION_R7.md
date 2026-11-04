# LiteRT runtime migration — R7 (2026-11-04)

## Why the old runtime was removed

The Galaxy S24 FE API 36 diagnostic reported failure while resolving `task_vision_jni`. Both specialist profiles shared the same Task Vision `0.4.4` EfficientDet loader, so initialization failed before screenshot inference. The previous filesystem-loader correction addressed model-file access but left the Task Vision native library in the APK.

## Runtime decision

R7 removes `org.tensorflow:tensorflow-lite-task-vision:0.4.4` and does not add MediaPipe Tasks. MediaPipe Tasks is not selected because the product's network policy permits explicit model downloads only; the app must not add an SDK telemetry/usage-metrics path.

The two existing `.tflite` models now use the standalone CPU `Interpreter` compatibility API bundled in Google AI Edge LiteRT Android `2.3.2` (`com.google.ai.edge.litert:litert:2.3.2`). The interpreter maps each verified model file from private app storage. No Play Services model runtime, remote inference, or additional telemetry client is used.

This is a deliberately narrow compatibility exception to LiteRT's recommendation to use `CompiledModel` for new applications. The pinned EfficientDet model terminates in `TFLite_Detection_PostProcess`. An upstream LiteRT issue reports that `CompiledModel.createOutputBuffers()` on CPU can allocate four-byte output buffers for this operator even when the model's outputs are larger. That can cause an out-of-bounds native access. R7 therefore avoids that output-buffer path rather than attempting to work around it with unverified buffer handles. The adapter sizes direct output buffers from the runtime tensor metadata and validates the fixed `[1,25,4]`, `[1,25]`, `[1,25]`, `[1]` contract before decoding detections. The raw interpreter output follows TensorFlow Lite `TFLite_Detection_PostProcess`: `[ymin,xmin,ymax,xmax]`. This must not be confused with the `[top,left,right,bottom]` bounding-box representation exposed after MediaPipe Task API postprocessing. The model uses COCO category IDs offset by one (`CLASS_OFFSET = 1` in Google AutoML EfficientDet postprocessing), so the COCO `person` ID is 1. The integration tests validate raw coordinate ordering and the documented one-based class-ID range.

## Buffer contract and failure behavior

`LocalLiteRtCpuModel` calls `allocateTensors()`, checks the model's tensor counts and FLOAT32 element types, obtains fixed tensor shapes and runtime byte counts, and rejects dynamic, oversized, or inconsistent tensor allocations. It allocates direct native-order input and output buffers once. Inference reuses those buffers on the existing single-thread model executor. Outputs are copied to owned float arrays before model-specific validation.

The EfficientDet adapter validates its four outputs, count, class indices, scores and normalized boxes before creating candidate person regions. The segmenter validates the exact 256×256×6 output before category mapping. Malformed output throws; it cannot create a permissive region or rectangle fallback.

The model files, source URLs, hashes, size bounds and licenses are unchanged. No model is retrained, converted, quantized or rewritten.

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
