# Security and privacy notes

ScreenVeil is experimental software that processes screenshots from the active application window after the user explicitly enables its Android AccessibilityService. It is not a certified safety filter and must not be treated as a high-assurance security boundary.

## Data handling

- Screen screenshots are copied to process memory and are not uploaded by ScreenVeil.
- The temporary image passed to LiteRT-LM is written under the app's private cache directory and deleted after inference; stale `screenveil-frame-*` files are removed on app startup.
- The only network operation implemented by the app is the user-initiated download of the pinned Gemma artifact.
- The downloaded model is accepted only after a SHA-256 check against a hard-coded hash for an immutable upstream revision.
- Do not add analytics, telemetry, remote inference, arbitrary model downloads or screenshot persistence without a separate privacy review and explicit product decision.

## Accessibility scope

The service reads event metadata and active-window IDs/bounds solely to schedule/crop screenshot processing. It does not query view nodes, extract text, click controls, perform gestures or automate other apps. Android's secure-window and screenshot restrictions must not be bypassed.

## Failure behavior

For notified window changes, scrolling, any window-content-change event and technical capture/inference/formatting failures, the service attempts to replace detailed masks with an opaque app-window cover and retry. After three consecutive model/structured-output failures it stops repeated expensive inference, retains the cover and requires the user to stop/restart protection to retry. An accessibility interruption retains the cover while the service remains connected; if Android disables or destroys the service, the overlay cannot be guaranteed to survive. If the overlay itself cannot be displayed, the UI reports a critical error; do not represent that state as protected. A syntactically valid but visually incorrect model response, including a missed media region or incorrect `allow`, remains a detection false negative and is not eliminated by this fallback.

## Model supply-chain requirements

Every model added to the allow-list must have a named publisher, immutable revision, exact artifact path, pinned SHA-256, license review, Android runtime adapter, preprocessing/output documentation and a successful device benchmark. A hash proves file identity, not publisher identity or license permission. Never treat a model card or candidate listing as proof that a model is safe and operational.

## Reporting

For a security or privacy issue, use the repository host's private vulnerability-reporting/advisory mechanism where available. Avoid posting screenshots, personal data or sensitive reproduction material in public issue trackers.
