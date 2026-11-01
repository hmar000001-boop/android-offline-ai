# Security and privacy notes

ScreenVeil is experimental software that processes sensitive on-screen pixels. Treat it as a privileged application and review the source before enabling its Accessibility Service.

## Capture permissions and behavior

- ScreenVeil does not use `MediaProjection`, a screen-recording consent dialog, a foreground capture service, or `SYSTEM_ALERT_WINDOW`.
- The user must manually enable ScreenVeil under Android Settings → Accessibility. The application cannot grant itself this capability.
- Android binds the service through `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- The service declares `canTakeScreenshot=true` and `canRetrieveWindowContent=true`. The latter is needed to retrieve active application-window IDs and their screen bounds for `takeScreenshotOfWindow()`.
- The implementation reads event type/package metadata, active window IDs and window bounds. It does not call `rootInActiveWindow`, inspect `AccessibilityNodeInfo`, read text/content descriptions, click UI elements or perform gestures.
- Screenshots are requested from the active application window, not the entire display. The Android API specifically supports excluding accessibility overlays from the captured window. System surfaces outside the target window are not analyzed.
- Android may reject screenshots of secure/protected windows or rate-limit capture requests. ScreenVeil does not bypass `FLAG_SECURE`, DRM or platform restrictions.

## Local data

- Screen frames are held in process memory and copied into a private app-cache JPEG only for LiteRT-LM image input. The JPEG is deleted in a `finally` block after inference.
- On application startup, stale `screenveil-frame-*` / legacy `screen-frame-*` cache files are removed.
- The model is stored under private `filesDir` storage. It is downloaded only after the user presses the download button, from a pinned repository commit, and is checked against a pinned SHA-256 before loading.
- No screenshot, model prompt, model output, package list, event stream or usage analytics is uploaded.
- `INTERNET` is needed only for the explicit model download. The runtime path performs local inference.
- Android backup is disabled (`allowBackup=false`).

## Model trust

A SHA-256 hash verifies integrity against a known expected digest; it does not independently prove the publisher's identity or the model's semantic safety. ScreenVeil therefore ships with one hard-coded, source-reviewed catalogue entry. Arbitrary model import is not enabled. Additional models must be reviewed for source provenance, exact immutable artifact, license, runtime compatibility and evaluation results before being added.

## Security limitations

- ScreenVeil is not a security boundary and can miss or misclassify content.
- Gemma 4 E2B is not a specialist moderation classifier. Its boxes are generated estimates.
- The app does not guarantee a redaction before the first visible frame.
- Protected windows, system surfaces, DRM-backed media and some vendor application windows may not be capturable.
- Accessibility service availability and screenshot limits depend on Android/device behavior.
- Model inference can consume significant RAM, CPU/GPU time, battery and thermal headroom.

Do not rely on ScreenVeil as the sole child-safety, workplace-safety, legal-compliance or other high-assurance control.
