# Changelog

## 0.2.2 — Direct local media selection

- Replaced the Share-sheet-first demo flow with in-app **Choose photo** and **Choose video** actions using Android's system Photo Picker.
- Kept Share parsing as a separate reusable adapter, but removed share intent-filters from the demo activity.
- Added local image preview decoding with a 1600-pixel maximum edge, EXIF orientation, and a 100-megapixel source-image guard.
- Added video preview and bounded sampling of up to six frames on supported devices. API 26 and unknown-dimension sources are skipped rather than decoded unbounded.
- Added local global/category settings, custom category IDs, threshold controls, and persistence.
- Hardened analyzer failure handling and explicit category-coverage accounting.
- Added normalized evidence regions/time ranges for future redaction.
- Kept the model runtime unconfigured; the app reports `NOT_ANALYZED` / `UNVERIFIED`.
