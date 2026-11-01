#!/usr/bin/env bash
# ScreenVeil Gradle bootstrapper. Downloads the pinned Gradle distribution
# and verifies its SHA-256 before executing it. See gradle/wrapper/gradle-wrapper.properties.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="8.14.6"
SHA256="7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/screenveil-wrapper/gradle-${VERSION}"
BIN="$BASE/gradle-${VERSION}/bin/gradle"
if [[ ! -x "$BIN" ]]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-${VERSION}-bin.zip"
  TMP="$ZIP.part"
  echo "Downloading Gradle ${VERSION} from services.gradle.org..."
  curl --fail --location --retry 3 --retry-delay 2 --output "$TMP" "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$TMP" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL="$(shasum -a 256 "$TMP" | awk '{print $1}')"
  else
    rm -f "$TMP"
    echo "Neither sha256sum nor shasum is available" >&2
    exit 1
  fi
  ACTUAL="$(printf '%s' "$ACTUAL" | tr '[:upper:]' '[:lower:]')"
  if [[ "$ACTUAL" != "$SHA256" ]]; then
    rm -f "$TMP"
    echo "Gradle distribution checksum mismatch" >&2
    exit 1
  fi
  mv "$TMP" "$ZIP"
  unzip -q "$ZIP" -d "$BASE"
fi
exec "$BIN" -p "$ROOT" "$@"
