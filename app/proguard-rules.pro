# ScreenVeil release (R8) rules.
# Native-backed inference runtimes resolve classes and methods from JNI; keep their public surface.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**

# Android instantiates these by name from the manifest.
-keep class dev.screenveil.app.capture.ScreenProtectionAccessibilityService
-keep class dev.screenveil.app.capture.ProtectionTileService
-keep class dev.screenveil.app.capture.StopProtectionReceiver

# Debug-only test seams are dead code in release (BuildConfig.DEBUG is false) and are stripped.
