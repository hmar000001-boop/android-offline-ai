# FilterBridge Lab architecture

## Dependency direction

```text
demo-app  ───────────────┐
                         ├──> filter-core (pure Java)
android-ingest ──────────┘

Future Android model adapters ──> filter-core contracts
filter-core must never depend on Android, a platform API, or a model vendor.
```

## Responsibilities

### `filter-core`

- Platform-neutral media and analysis contracts.
- Extensible category identifiers and normalized spatial/time evidence.
- Explicit analyzer coverage for enabled categories.
- Deterministic user policy: warning, blur, hide, or unverified.
- Fail-closed orchestration for analyzer exceptions and null results.

### `android-ingest`

- Android Photo Picker URI acquisition and validation.
- Lazy stream access without copying media to app storage.
- Bounded image preview decoding and EXIF orientation.
- Bounded video preflight sampling, with safe abstention where scaled extraction is unavailable.
- Android types are contained in this module.

### `demo-app`

- Minimal local photo/video selection UI.
- Preview, progress, errors, and local preference controls.
- Background media work and stale-result cancellation using a selection generation.
- The current composition deliberately installs `NotConfiguredAnalyzer`; no model is implied.

## Security and correctness invariants

1. No network permission or remote inference in this demo.
2. No broad media-library permission; the user selects each asset.
3. No media bytes are copied to persistent app storage by the picker adapter.
4. Missing, failed, or incomplete analysis is `UNVERIFIED`, never `ALLOW`.
5. One specialist model cannot claim coverage of categories it did not analyse.
6. A positive detection can still trigger the user's selected action even if other categories remain unverified.
7. Model observations never make religious or normative decisions; the deterministic policy uses the user's configuration.
8. Confidence thresholds are provisional until validated per model and category.

## Boundaries of this release

This is a hardened engineering foundation, not a production content-protection product. There are no actual classification weights, OCR/ASR/music models, or blur/hide renderers in this release. The first model must be chosen and evaluated before those behaviours can be truthfully implemented. Video currently has local preview and bounded preflight sampling only; it does not provide frame-by-frame protection.
