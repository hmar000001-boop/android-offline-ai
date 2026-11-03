# Security and privacy notes — ScreenVeil 1.6.0 (R6)

ScreenVeil is experimental software that processes active-window screenshots after the user explicitly enables its Android AccessibilityService and starts protection. It is not a certified safety filter or a high-assurance security boundary.

## Network and data handling

- The only application code that opens a network connection is `ModelInstaller`, called from the explicit model-download UI.
- Downloads use fixed HTTPS catalog URLs, approved hosts, redirect validation, lower/upper size bounds and an exact SHA-256. Bytes remain temporary until complete verification succeeds.
- SHA-256 proves identity against the catalog; it does not prove model accuracy, safety, publisher identity or data quality.
- Screenshots and model inference remain local. Frames are stored temporarily in private cache and deleted after processing; abandoned temporary frame files are removed on startup.
- No analytics, telemetry, remote inference, background model updates, arbitrary URLs, user-imported models, screenshot upload or model marketplace is implemented.
- Model downloads reveal ordinary network metadata (for example IP address, time and requested artifact) to hosting/CDN providers. Inference after download does not require a network connection.

## Accessibility scope

The service reads event metadata, active-window IDs and bounds to schedule/crop screenshot processing. It does not query view nodes, extract on-screen text, click controls, perform gestures or automate other apps. Android secure-window and DRM restrictions are respected; they are not bypassed.

## R6 redaction safety invariants

These limits apply to all model outputs and every decision category. There are no confirmed-category exemptions:

- One final rendered body/clothing mask may cover at most **35%** of the application window.
- The union of all rendered masks may cover at most **45%** of the application window.
- Exceeding either hard limit rejects the output and triggers a stop/clear; masks are never shrunk into arbitrary rectangles.
- Coverage at or above **35%** sustained for **3 seconds** triggers an independently scheduled stop/clear watchdog.
- Maximum session duration is **5 minutes** during the test period.
- Screenshot callbacks have a **5-second** deadline; model inference has an **8-second** deadline.
- Invalid or unavailable segmentation is a safety failure. Rectangular fallback is prohibited.

The policy is checked before rendering and again against the final composed alpha bitmap. The watchdogs run on a main-looper Handler independently of the screenshot and inference executors. They cannot guarantee app-level recovery if Android's main process/looper itself is completely unresponsive; Android process/service teardown remains the final system-level fallback.

## Pixel-level body/clothing segmentation

R6 uses the pinned Google Selfie Multiclass 256×256 TFLite model. Its six output categories are background, hair, body skin, face skin, clothes, and accessories/others. R6 selects body-skin and clothes pixels only, intersects them with the content detector's candidate geometry, applies a small dilation for mask-boundary safety, and renders the configured effect only where the final pixel mask is selected. The rest of the detection rectangle is transparent.

This segmentation model is not a swimsuit classifier, not an anatomical part parser, and not guaranteed pixel-perfect. It is a coarse person/selfie segmentation model; its performance on app screenshots, distant/small people, occlusions, diverse lighting and the Galaxy S24 FE is unmeasured. If segmentation returns an invalid/empty mask or fails, protection stops and clears the overlay; it does not fall back to the rectangle.

## Fail-open behavior and recovery

ScreenVeil deliberately fails open to preserve device usability. On an unsafe coverage result, failed segmentation, screenshot timeout, inference timeout, repeated model/capture failure, overlay failure, Android secure-window denial, Accessibility interruption or service unbind, protection switches OFF and the overlay is cleared.

- The notification carrying the Stop action is mandatory. Start is refused if app notifications or the active notification channel are disabled or if the ongoing Stop notification cannot be posted.
- The Quick Settings tile is stop-only. It must be added by the user before the session; when protection is OFF it opens the app rather than starting protection.
- Android system recovery remains available by disabling the Accessibility service or force-stopping ScreenVeil from App Info.
- Masks are cleared on relevant window/content/scroll changes and rebuilt after a fresh screenshot. Content may be visible during capture/inference latency or after protection stops.
- A late result from a timed-out worker is invalidated. The worker executor is shut down and a process-wide poison latch prevents Accessibility unbind/rebind from creating a replacement worker while a potentially stuck JNI/native thread may still exist. After a hard screenshot/inference timeout, the user must force-stop ScreenVeil from Android App Info (which terminates the process), reopen it, and re-enable Accessibility before trying again.

The 5-minute session and 3-second coverage watchdogs are safety limits, not proof that all false positives or hangs are recoverable. A hard worker timeout intentionally requires a full app-process restart before protection can be started again. The handler cannot preempt a completely blocked Android main looper.

## Model and classifier limitations

OpenNSFW and ViT are general NSFW classifiers, not clothing or swimwear classifiers. Gemma is a general multimodal model; on-screen text can steer language models despite prompt instructions, so prompt injection remains a limitation. The optional Swimwear-aware profile is disabled; the current approved catalog does not provide validated swimwear-specific detection. A pinned artifact hash is not a model safety audit or accuracy benchmark.

## Diagnostics

`diagnostics.log` records events, timings and counts, not screenshots, on-screen text, detected content or app names. It is kept in private app storage and is never uploaded automatically. The user may copy or clear it in the app.

## Model supply-chain requirements

Each admitted model must have a named publisher, fixed artifact path, reviewed license declaration, exact SHA-256, byte-size bounds, approved HTTPS host, Android runtime adapter and documented preprocessing/output contract. See `docs/MODEL_GOVERNANCE.md` and `THIRD_PARTY_NOTICES.md`. These checks are not a third-party security audit.

## Reporting

For a security or privacy issue, use the repository host's private vulnerability-reporting/advisory mechanism where available. Avoid posting screenshots, personal data or sensitive reproduction material in public issue trackers.
