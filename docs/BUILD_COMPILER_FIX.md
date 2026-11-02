# Kotlin compilation correction

## Defect identified

The specialized ONNX detector called `OrtSession.Result.get(outputName).value` in three inference methods. In the ONNX Runtime Java API, `OrtSession.Result.get(String)` returns `Optional<OnnxValue>`, not `OnnxValue` directly. Dereferencing `.value` on the `Optional` is therefore a Kotlin compilation error.

The three call sites now unwrap the optional explicitly with `orElseThrow { IllegalStateException(...) }` before reading the ONNX value. The corrected expression was independently compiled against a Java API stub matching the documented ONNX Runtime method signature.

Official API reference: https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.Result.html

## Build log improvement

The GitHub Actions workflow now captures the full Gradle output in `build-output.log`, invokes Gradle with `--stacktrace`, and uploads that log plus Gradle's problems report even when the build fails. This prevents the actionable Kotlin `e:` diagnostics from being lost when only the final Gradle summary is copied.

## Validation boundary

This environment does not have the pinned Gradle distribution or Android SDK platform available. DNS resolution for `services.gradle.org` fails here, so a complete Android compilation could not be executed locally. The correction addresses a confirmed API misuse, but the project must be rerun in GitHub Actions. If compilation still fails, inspect the first `e:` diagnostics in `build-output.log`; do not treat the final `BUILD FAILED` summary as the root cause.


## 2026-11-02 — TensorFlow Lite Task Vision model path

GitHub Actions reported:

```text
SpecializedScreenModerationDetector.kt:67:71 Argument type mismatch: actual type is 'File', but 'String!' was expected.
```

`ObjectDetector.createFromFileAndOptions(context, modelPath, options)` in TensorFlow Lite Task Vision 0.4.4 expects the model path as a `String`, not a `java.io.File`. The installer intentionally keeps model artifacts as `File` values, so the adapter now passes `personFile.absolutePath`. This does not copy or download the model; it supplies the path of the already-installed local artifact.
