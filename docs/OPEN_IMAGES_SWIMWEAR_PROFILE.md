# Google Open Images V4 — Swimwear-aware profile admission

## Intended profile

The planned `SWIMWEAR_AWARE` pipeline is separate from Balanced and Fast. It is intended to replace EfficientDet-Lite0 with Google's SSD MobileNet V2 trained on Open Images V4 for that profile only. The planned remaining content classifiers are Yahoo OpenNSFW and ViT-Tiny NSFW. The existing EfficientDet profiles remain unchanged.

The profile entry is currently visible but disabled. It has no admitted artifact, no download URL, no approved size bounds and no SHA-256. This is intentional. Do not add a guessed digest or treat the TensorFlow SavedModel as a TFLite download.

## Candidate evidence

Google's Kaggle model page: https://www.kaggle.com/models/google/mobilenet-v2/tensorFlow1/openimages-v4-ssd-mobilenet-v2/1

The model card describes an SSD + MobileNet V2 trained on Open Images V4, 600 boxable categories, overall Open Images V4 mAP 0.34, and Apache-2.0. Its outputs include bounding boxes, human-readable class names, class labels and confidence scores. The page's listed `saved_model.pb` is approximately 63.36 MB. These are model-card properties, not independent ScreenVeil evaluation results.

TensorFlow's older TF1 detection model zoo lists `ssd_mobilenetv2_oidv4` and points to the checkpoint archive: https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/tf1_detection_zoo.md

The TensorFlow mobile conversion guide describes conversion of supported SSD detection models: https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/running_on_mobile_tf2.md

An older Android conversion example for the OIDv4 SSD model documents the intermediate `export_tflite_ssd_graph.py` step and the `TFLite_Detection_PostProcess` outputs: https://github.com/iamnabink/TFObjectDetectionDemoApp

## Required conversion and validation gates

1. Pin the exact Google checkpoint archive, config file, TensorFlow/Object Detection API revision and conversion environment. Record source URLs and hashes.
2. Export the pretrained weights to a 300x300 SSD TFLite graph. Do not train, fine-tune or modify learned weights.
3. Keep the expected SSD detection output contract (`boxes`, `classes`, `scores`, `count`) and determine the actual input tensor type/normalization from the exported graph. Do not assume it from a generic SSD example.
4. Extract the Open Images label map from the same pinned source revision. Verify the exact class names used at runtime, especially `Person` and `Swimwear`; do not hardcode a numeric class index without testing the label map.
5. Either attach valid TFLite ObjectDetector metadata and prove compatibility with `tensorflow-lite-task-vision`, or write a dedicated Interpreter adapter that validates all tensor shapes, types and output ordering.
6. Compare SavedModel and TFLite detections on a fixed fixture set. Measure class-specific Swimwear precision/recall and box IoU, along with Person recall. Include swimwear types, modest swimwear, sports swimwear, pool backgrounds, beach clothing and non-swimwear controls.
7. Verify local inference with all network connectivity disabled after model installation. Record APK/device/runtime versions, P50/P95 latency, peak memory and thermal behavior on Galaxy S24 FE.
8. Only after these gates pass, record the exact exported TFLite SHA-256 and byte-size bounds in `SpecializedModelCatalog`, add it to the download allow-list, implement the separate detector adapter, and enable the profile.

## Intended detection decision

A positive `Swimwear` box should be associated with an overlapping `Person` box where possible. For the user's policy, redact the full associated person region; if no person match exists, redact the detected garment box. A missing `Swimwear` detection must not automatically become `ALLOW`. The two general NSFW classifiers remain additional evidence and do not themselves provide a swimwear label.

## Current status

**Not installed, not selectable and not runtime-integrated.** Balanced and Fast continue using EfficientDet-Lite0. This document describes the admission path, not a completed model conversion.
