# Quick start — 0.3.2

1. Replace your GitHub repository contents with the entire contents of this project.
2. Push to GitHub and open Actions.
3. Confirm both model steps succeed: `Export Viddexa Nano` and `Download and verify independent Owen XS safety classifier`.
4. Download the debug APK artifact from the newest successful run.
5. Uninstall the previous debug app if Android reports a signature conflict, then install the new APK.
6. Enable Airplane mode (including Wi-Fi off).
7. Choose a photo and tap Analyze on device.
8. Confirm both models report scores:
   - Viddexa: normal/upstream-safe, hentai, porn, sexy, drawing.
   - Owen: NSFL, NSFW, SFW.
9. Remember: SFW/normal is not a safety verdict. The overall policy remains UNVERIFIED until every enabled category has complete validated coverage.

For a diagnostic report, capture the result text, both model names/revisions, scores, inference times if shown, and whether Airplane mode was enabled. Do not send explicit test images.
