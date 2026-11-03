# Security and privacy notes

ScreenVeil is experimental software that processes screenshots from the active application window after the user explicitly enables its Android AccessibilityService. It is not a certified safety filter and must not be treated as a high-assurance security boundary.

## Network and data handling

- The only application code that opens a network connection is `ModelInstaller`, and it is called from the explicit model-download UI action.
- Downloads are restricted to fixed catalog URLs using HTTPS. Redirects are followed manually and every hop must remain on an exactly approved host (`storage.googleapis.com`, `huggingface.co`/`hf.co` and their subdomains, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`); GitHub user-content subdomains are rejected. Arbitrary URLs and user-imported models are not supported.
- Each artifact has an exact SHA-256 and lower/upper byte bounds. Bytes are streamed to a temporary file and the artifact is moved to its final private-storage path only after size and SHA-256 checks succeed.
- SHA-256 establishes byte identity with the pinned catalog; it does not prove that the publisher, model architecture, model behavior or binary is safe.
- Screenshots are processed locally and are not uploaded by ScreenVeil. The temporary image passed to LiteRT-LM is stored under the app's private cache directory and deleted after inference; stale `screenveil-frame-*` files are removed on app startup.
- The app does not implement analytics, telemetry, remote inference, background model updates, an arbitrary model marketplace or screenshot persistence.
- Model downloads do reveal ordinary network metadata to the hosting/CDN providers (such as IP address, request time and requested artifact). After installation, model inference does not require a network connection.

## Accessibility scope

The service reads event metadata and active-window IDs/bounds solely to schedule/crop screenshot processing. It does not query view nodes, extract text, click controls, perform gestures or automate other apps. Android's secure-window and screenshot restrictions must not be bypassed.

## Failure behavior

ScreenVeil fails **open**, deliberately: a technical failure must never lock the user's screen. After a failed capture, inference or overlay update it keeps the last localized masks for a bounded number of retries (3 model failures, 5 capture failures), then pauses, removes its overlay and posts a "ScreenVeil paused - screen is NOT protected" notification. There is no opaque whole-window fallback.

Protection also pauses itself when Android forbids a capture (FLAG_SECURE windows), when the service is interrupted or unbound, and when the whole window stays covered for 30 seconds (treated as a probable detection error). Masks are lifted while the notification shade or lock screen is the active window.

Emergency stop: the ongoing notification has a Stop action, and a Quick Settings tile toggles protection without needing to see the covered screen.

Detection limits that remain: masks are removed on every scroll, window or content change and re-drawn after the next analysis (roughly 0.5-2 s), so content can be visible during that window. A missed region or an incorrect `allow` is a false negative that no fallback can recover. The Gemma profile reads text that appears on screen and can be steered by it (prompt injection); treat it as experimental. Unconfirmed (`UNCERTAIN` / person-only) regions that would cover 70% of the window or more are discarded.

Diagnostics: `diagnostics.log` in private storage records events and counts only (no screenshots, detected content, on-screen text or app names). It is never uploaded; the user can copy or clear it in the app.

## Model supply-chain requirements

Every admitted model must have a named publisher, fixed source revision/artifact path, reviewed license declaration, exact pinned SHA-256, byte-size bounds, approved HTTPS host, Android runtime adapter and documented preprocessing/output contract. Balanced policy does not require a formal third-party security audit for every model, but it does require an identifiable and reasonably reputable upstream project and our own artifact/runtime checks. A model card or SHA-256 alone is not a security certification.

The app catalog excludes models whose licensing, artifact provenance, Android execution path or offline behavior do not satisfy these rules. See `docs/MODEL_GOVERNANCE.md` and `THIRD_PARTY_NOTICES.md`.

## Reporting

For a security or privacy issue, use the repository host's private vulnerability-reporting/advisory mechanism where available. Avoid posting screenshots, personal data or sensitive reproduction material in public issue trackers.
