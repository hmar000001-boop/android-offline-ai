# Security and privacy notes

ScreenVeil is experimental software that processes screenshots from the active application window after the user explicitly enables its Android AccessibilityService. It is not a certified safety filter and must not be treated as a high-assurance security boundary.

## Network and data handling

- The only application code that opens a network connection is `ModelInstaller`, and it is called from the explicit model-download UI action.
- Downloads are restricted to fixed catalog URLs using HTTPS. Redirects are followed manually and every hop must remain on an approved hosting domain. Arbitrary URLs and user-imported models are not supported.
- Each artifact has an exact SHA-256 and lower/upper byte bounds. Bytes are streamed to a temporary file and the artifact is moved to its final private-storage path only after size and SHA-256 checks succeed.
- SHA-256 establishes byte identity with the pinned catalog; it does not prove that the publisher, model architecture, model behavior or binary is safe.
- Screenshots are processed locally and are not uploaded by ScreenVeil. The temporary image passed to LiteRT-LM is stored under the app's private cache directory and deleted after inference; stale `screenveil-frame-*` files are removed on app startup.
- The app does not implement analytics, telemetry, remote inference, background model updates, an arbitrary model marketplace or screenshot persistence.
- Model downloads do reveal ordinary network metadata to the hosting/CDN providers (such as IP address, request time and requested artifact). After installation, model inference does not require a network connection.

## Accessibility scope

The service reads event metadata and active-window IDs/bounds solely to schedule/crop screenshot processing. It does not query view nodes, extract text, click controls, perform gestures or automate other apps. Android's secure-window and screenshot restrictions must not be bypassed.

## Failure behavior

For notified window changes, scrolling, any window-content-change event and technical capture/inference/formatting failures, the service attempts to replace detailed masks with an opaque app-window cover and retry. After three consecutive model/structured-output failures it stops repeated expensive inference, retains the cover and requires the user to stop/restart protection to retry. An accessibility interruption retains the cover while the service remains connected; if Android disables or destroys the service, the overlay cannot be guaranteed to survive. If the overlay itself cannot be displayed, the UI reports a critical error; do not represent that state as protected. A syntactically valid but visually incorrect model response, including a missed media region or incorrect `allow`, remains a detection false negative and is not eliminated by this fallback.

## Model supply-chain requirements

Every admitted model must have a named publisher, fixed source revision/artifact path, reviewed license declaration, exact pinned SHA-256, byte-size bounds, approved HTTPS host, Android runtime adapter and documented preprocessing/output contract. Balanced policy does not require a formal third-party security audit for every model, but it does require an identifiable and reasonably reputable upstream project and our own artifact/runtime checks. A model card or SHA-256 alone is not a security certification.

The app catalog excludes models whose licensing, artifact provenance, Android execution path or offline behavior do not satisfy these rules. See `docs/MODEL_GOVERNANCE.md` and `THIRD_PARTY_NOTICES.md`.

## Reporting

For a security or privacy issue, use the repository host's private vulnerability-reporting/advisory mechanism where available. Avoid posting screenshots, personal data or sensitive reproduction material in public issue trackers.
