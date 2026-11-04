# Physical-device validation plan — Samsung Galaxy S24 FE (R8)

This plan is mandatory before considering ScreenVeil suitable for ordinary use. CI can establish source compilation, unit tests and synthetic lifecycle behavior. Only the physical Galaxy S24 FE can establish real segmentation/model compatibility, actual overlay alignment, One UI behavior, latency, memory, battery and thermal stability.

## Gate 0 — CI and model artifact integrity

1. Run the R8 GitHub Actions workflow on the intended branch.
2. Require green `build`, `release-build` (including 16 KB ELF/ZIP alignment checks), and `emulator-lifecycle` API 34 and API 36 jobs.
3. Manually run `verify-model-pins` using `workflow_dispatch`. It verifies all five catalog artifacts, including the optional large Gemma model; confirm both EfficientDet and Selfie Multiclass artifacts pass the pinned SHA-256 and size bounds.
4. The debug APK is the controlled test artifact. The R8 release APK is unsigned unless the repository signing secrets are configured. Do not install an unsigned release APK.

## Gate 1 — install and emergency exits

1. Install the R8 debug APK. If Android reports a signature conflict with an earlier debug installation, remove the older app first; this may also remove locally downloaded model files.
2. Open ScreenVeil. Confirm no MediaProjection/screen-recording permission flow and no Display over other apps permission request.
3. Grant notification permission. Disable/re-enable ScreenVeil notification channel once to verify that Start is refused while the Stop notification cannot be shown.
4. Add ScreenVeil's Quick Settings tile manually. Confirm it is labelled as a stop action; when protection is OFF, tapping it must open the app and must not start protection.
5. Enable ScreenVeil manually in Android Settings → Accessibility. Confirm binding alone does not start protection.
6. Confirm Start refuses until the required model artifacts are installed and the notification Stop control is available.
7. Start protection and immediately verify the ongoing notification's Stop action. Verify the Quick Settings tile also stops protection, sets it OFF and removes every overlay pixel.
8. Verify the Android system fallback: disabling ScreenVeil Accessibility or force-stopping the app clears the overlay.

## Gate 2 — model installation and initialization

1. Select **Balanced multi-model · offline**. Download EfficientDet-Lite0, Yahoo OpenNSFW, ViT-Tiny NSFW and Google Selfie Multiclass. Require successful size and SHA-256 verification for each.
2. Confirm EfficientDet and Selfie Multiclass load from their verified private files using the bundled LiteRT `2.3.2` CPU Interpreter compatibility API; no MediaPipe Tasks runtime or analytics dependency is introduced. Verify logs reach `MODEL_INITIALIZED` and then confirm the segmenter warm-up succeeds.
3. Confirm Fast requires EfficientDet, OpenNSFW and the same segmentation model, but not ViT or Gemma.
4. Confirm Gemma is separate and optional; its 2.6 GB download is not required by the specialist profiles.
5. Test interrupted downloads, disallowed redirect, hash mismatch, size overflow and low-storage behavior; incomplete files must not be marked installed.

## Gate 3 — body-mask rendering and coordinate mapping

Use a lawful, consented image set and test:

- One person centered, near each screen edge, small/distant, partially occluded and multiple people.
- Portrait and landscape orientation; status/navigation bars; edge-to-edge; split screen; picture-in-picture; dialogs/popups and scrollable feeds.
- People on photographs, video frames, thumbnails and collage tiles.
- Varied lighting, backlighting, low contrast, diverse skin tones, different clothing and hair/head coverings.

For each selected detection, inspect the overlay visually and with diagnostics:

- Body-skin and clothes classes are selected; face, hair, background and accessories remain visible by the current policy.
- The mask follows the selected body/clothing pixels and is not a rectangular crop.
- Dilation does not spread into adjacent UI or outside the candidate geometry.
- Screenshot-to-mask and mask-to-overlay coordinates align; no scaling, letterboxing or window-origin offset.
- A missing, empty, malformed or misaligned segmentation result stops protection and clears masks. There must be no rectangular fallback.
- No model output can bypass the 35% per-region and 45% combined final-pixel coverage limits, including EXPLICIT, SUGGESTIVE and SWIMWEAR categories.

## Gate 4 — deliberate safety fault injection

Use the debug instrumentation hooks or a dedicated test build. Never rely on a real false positive to test a stop mechanism.

| Injection | Required result |
|---|---|
| One EXPLICIT full-screen box | No full-window rectangle; final body mask is checked against hard limits; unsafe output stops/clears. |
| All nine Balanced fallback tiles positive | Union mask cannot exceed 45%; unsafe output stops/clears. |
| Many confirmed regions | No category exemption; same universal limits. |
| One mask above 35% | Reject and stop; do not shrink it. |
| Combined mask above 45% | Reject and stop before showing it. |
| Combined coverage at or above 35% for 3 seconds | Independent coverage watchdog stops and clears. |
| Inference worker deliberately blocked | 8-second deadline stops, clears and invalidates late result; process poison prevents Accessibility rebind from spawning another worker. Force-stop the app process before retrying. |
| Screenshot callback deliberately withheld | 5-second deadline stops and clears; process poison prevents Accessibility rebind from spawning another worker. Force-stop the app process before retrying. |
| Segmentation unavailable/throws/returns empty | Stop and clear; no rectangle fallback. |
| Session runs for its maximum | Stop after **5 minutes** and clear the overlay. |

Also test window change, rapid scroll, Android notification shade/Quick Settings, lock screen and `FLAG_SECURE` denial. Verify Stop remains reachable and masks are not drawn over system panels.

## Gate 5 — moderation quality

The current classifiers are general NSFW classifiers, not validated swimwear classifiers. Use lawful, consented/licensed test data with human labels covering explicit content, nudity, lingerie, suggestive poses, bikinis, one-piece swimsuits, swim trunks, rash guards, modest swimwear, ordinary beach/pool/fitness images, ordinary skin-exposing content, drawings, cartoons, memes, images with no people and multiple media regions.

Measure per-category precision/recall, false-negative/false-positive rates, person-detection recall, region recall, mask IoU against hand-labelled body/clothing pixels, and fraction of screen pixels redacted. Report Balanced and Fast separately. Do not infer swimwear accuracy from general NSFW scores or segmentation categories.

## Gate 6 — performance and stability

For each selectable profile, record:

- Model download size, hash verification and initialization time.
- First-inference and steady-state latency for detector, classifier and segmenter.
- P50/P95 screenshot-request-to-overlay-commit latency.
- Process PSS, native heap and peak RAM.
- CPU/GPU/NPU usage and whether the runtime is actually accelerated on the Exynos 2400e (do not assume NPU use).
- Battery drain, device temperature, thermal throttling and process restarts.
- Capture rate-limits, screenshot failures and events missed between samples.

The 256×256 Google segmenter has published reference benchmarks on a Pixel 6, not the Galaxy S24 FE. Do not transfer those values to this phone. Determine whether one segmentation per screenshot meets the 8-second deadline with the selected profile and screenshot interval.

## Stop criteria

Do not recommend ordinary/daily use if any mask exceeds a hard limit, Stop fails to remove the overlay, segmentation misaligns, the app leaves a persistent overlay, real models fail to initialize, relevant content is materially missed, false positives are disruptive, the app crashes, memory/thermal behavior is unstable, or the five-minute session does not stop reliably. ScreenVeil is a sampled screenshot filter, not a guaranteed frame-by-frame video filter.
