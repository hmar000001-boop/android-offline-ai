# Security policy

ScreenVeil handles sensitive screen pixels. Please do not open public issues containing screenshots, personal content, model files, tokens, or private device information.

## Reporting

Report security issues privately to the repository maintainer before public disclosure. Include affected version, Android version, reproduction steps and logs with private screen data removed.

## Security design

- Screen pixels stay on-device and are never uploaded.
- Temporary model input images are written only to private app cache and deleted after inference.
- The model is stored in private app files and must pass a pinned SHA-256 check.
- The model downloader uses HTTPS and a fixed upstream repository URL.
- The capture service is not exported and requires explicit MediaProjection authorization.
- Overlay permission is requested through Android system settings.
- No Accessibility Service, boot receiver, ad SDK, analytics SDK, account system or remote command interface is included.
- CI uses read-only repository permissions and does not require secrets.

## Important limitation

ScreenVeil is experimental and is not a security boundary. It cannot protect content that Android does not expose to MediaProjection, and its AI model can miss or misclassify material. Do not rely on it as the sole child-safety, workplace-safety or other high-assurance control.
