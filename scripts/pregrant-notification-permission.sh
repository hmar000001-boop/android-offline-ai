#!/usr/bin/env bash
# Grant and verify the target application's notification runtime permission before
# AndroidJUnitRunner starts. Runtime permission changes from instrumentation can
# terminate the app/test UID, so this must run from the emulator host script.
set -euo pipefail

PACKAGE_NAME="${1:-dev.screenveil.app}"
USER_ID="${ANDROID_USER_ID:-0}"
PERMISSION="android.permission.POST_NOTIFICATIONS"

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not available on PATH; cannot pre-grant $PERMISSION." >&2
  exit 2
fi

if [[ -z "$PACKAGE_NAME" || -z "$USER_ID" ]]; then
  echo "ERROR: package name and Android user ID must be non-empty." >&2
  exit 2
fi

echo "Granting $PERMISSION to $PACKAGE_NAME for Android user $USER_ID..."
set +e
grant_output="$(adb shell pm grant --user "$USER_ID" "$PACKAGE_NAME" "$PERMISSION" 2>&1)"
grant_status=$?
set -e
if [[ -n "$grant_output" ]]; then
  printf '%s\n' "$grant_output"
fi
if (( grant_status != 0 )); then
  echo "ERROR: pm grant failed (exit $grant_status) for $PACKAGE_NAME/$PERMISSION." >&2
  exit "$grant_status"
fi

# IMPORTANT: check-permission is a `dumpsys package` subcommand, NOT a `pm`
# subcommand. It prints PackageManager.PERMISSION_GRANTED (0) or DENIED (-1).
echo "Verifying $PERMISSION using dumpsys package..."
set +e
check_output="$(adb shell dumpsys package check-permission "$PERMISSION" "$PACKAGE_NAME" "$USER_ID" 2>&1)"
check_status=$?
set -e
normalized_check="$(printf '%s' "$check_output" | tr -d '\r[:space:]')"
if (( check_status != 0 )) || [[ "$normalized_check" != "0" ]]; then
  echo "ERROR: $PERMISSION was not verified as granted." >&2
  echo "  command: adb shell dumpsys package check-permission $PERMISSION $PACKAGE_NAME $USER_ID" >&2
  echo "  exit: $check_status" >&2
  echo "  raw output: ${check_output:-<empty>}" >&2
  exit 1
fi

echo "$PERMISSION is GRANTED for $PACKAGE_NAME (user $USER_ID; PackageManager result 0)."
