#!/usr/bin/env bash
# Install Android SDK packages before Gradle or android-emulator-runner uses them.
# This wrapper retries transient/truncated SDK downloads and clears only SDK
# Manager's temporary download/staging directories between attempts.
set -Eeuo pipefail

if (( $# == 0 )); then
  echo "Usage: $0 <sdkmanager-package> [<sdkmanager-package> ...]" >&2
  exit 2
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must point to an existing SDK directory." >&2
  exit 2
fi
SDK_ROOT="$(cd "$SDK_ROOT" && pwd -P)"
if [[ "$SDK_ROOT" == "/" ]]; then
  echo "ERROR: refusing to operate on SDK root '/'." >&2
  exit 2
fi

SDKMANAGER="${SDKMANAGER:-$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager}"
if [[ ! -x "$SDKMANAGER" ]]; then
  if command -v sdkmanager >/dev/null 2>&1; then
    SDKMANAGER="$(command -v sdkmanager)"
  else
    echo "ERROR: sdkmanager not found at $SDK_ROOT/cmdline-tools/latest/bin/sdkmanager or on PATH." >&2
    exit 2
  fi
fi

MAX_ATTEMPTS="${SCREENVEIL_SDKMANAGER_MAX_ATTEMPTS:-3}"
if ! [[ "$MAX_ATTEMPTS" =~ ^[1-5]$ ]]; then
  echo "ERROR: SCREENVEIL_SDKMANAGER_MAX_ATTEMPTS must be an integer from 1 through 5." >&2
  exit 2
fi
RETRY_DELAY_SECONDS="${SCREENVEIL_SDKMANAGER_RETRY_DELAY_SECONDS:-15}"
if ! [[ "$RETRY_DELAY_SECONDS" =~ ^[0-9]+$ ]] || (( RETRY_DELAY_SECONDS > 300 )); then
  echo "ERROR: SCREENVEIL_SDKMANAGER_RETRY_DELAY_SECONDS must be an integer from 0 through 300." >&2
  exit 2
fi

# Deduplicate while preserving the requested package order. This matters for the
# API 36 leg, where compileSdk and emulator API are both 36.
declare -A SEEN_PACKAGES=()
PACKAGES=()
for package in "$@"; do
  if [[ -z "$package" ]]; then
    echo "ERROR: empty SDK package path." >&2
    exit 2
  fi
  if [[ -z "${SEEN_PACKAGES[$package]+x}" ]]; then
    SEEN_PACKAGES["$package"]=1
    PACKAGES+=("$package")
  fi
done

LOG_PREFIX="sdkmanager-install"
last_status=1

for (( attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ )); do
  echo "SCREENVEIL_SDKMANAGER_ATTEMPT=$attempt/$MAX_ATTEMPTS"
  echo "SCREENVEIL_SDKMANAGER_ROOT=$SDK_ROOT"
  printf 'SCREENVEIL_SDKMANAGER_PACKAGES='
  printf '%s ' "${PACKAGES[@]}"
  printf '\n'

  # SDK Manager can leave incomplete download/staging data after a failed ZIP
  # transfer. Do not touch installed platform/build-tools/emulator directories.
  rm -rf -- "$SDK_ROOT/.temp" "$SDK_ROOT/.downloadIntermediates"

  log_file="${LOG_PREFIX}-attempt-${attempt}.log"
  if "$SDKMANAGER" --install --channel=0 --sdk_root="$SDK_ROOT" "${PACKAGES[@]}" 2>&1 | tee "$log_file"; then
    echo "SCREENVEIL_SDKMANAGER_INSTALL=PASS"
    echo "SCREENVEIL_SDKMANAGER_SUCCESS_ATTEMPT=$attempt"
    exit 0
  else
    last_status=$?
    echo "WARNING: sdkmanager attempt $attempt failed with exit code $last_status." >&2
    if (( attempt < MAX_ATTEMPTS )); then
      delay_seconds=$(( attempt * RETRY_DELAY_SECONDS ))
      echo "Retrying after ${delay_seconds}s; SDK Manager temporary download data will be cleared." >&2
      if (( delay_seconds > 0 )); then sleep "$delay_seconds"; fi
    fi
  fi
done

echo "::error::Android SDK installation failed after $MAX_ATTEMPTS attempts. See ${LOG_PREFIX}-attempt-*.log." >&2
echo "SCREENVEIL_SDKMANAGER_INSTALL=FAIL"
exit "$last_status"
