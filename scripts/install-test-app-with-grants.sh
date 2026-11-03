#!/usr/bin/env bash
# Install/update the ScreenVeil target APK with runtime permissions granted by ADB.
# This runs on the emulator host, before AndroidJUnitRunner starts.
# `adb install -g` is preferable here to mutating permissions through UiAutomation
# in the instrumented process: it grants runtime permissions as part of installation.
set -euo pipefail

PACKAGE_NAME="${1:-dev.screenveil.app}"
APK_PATH="${2:-app/build/outputs/apk/debug/app-debug.apk}"
USER_ID="${ANDROID_USER_ID:-0}"
PERMISSION="android.permission.POST_NOTIFICATIONS"

printf '%s\n' 'SCREENVEIL_CI_PERMISSION_SETUP=R6_V4_ADB_INSTALL_GRANT'
printf 'UTC=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf 'PACKAGE=%s\nAPK=%s\nUSER_ID=%s\n' "$PACKAGE_NAME" "$APK_PATH" "$USER_ID"

if [[ -z "$PACKAGE_NAME" || -z "$APK_PATH" || -z "$USER_ID" ]]; then
  echo 'ERROR: package, APK path and Android user ID must be non-empty.' >&2
  exit 2
fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: target APK does not exist: $APK_PATH" >&2
  exit 2
fi
if ! command -v adb >/dev/null 2>&1; then
  echo 'ERROR: adb is not available on PATH.' >&2
  exit 2
fi

# android-emulator-runner sets ANDROID_SERIAL. Resolve a single device if the script
# is invoked locally without it, and always pass -s to avoid accidentally using another device.
SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  mapfile -t ONLINE_DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if (( ${#ONLINE_DEVICES[@]} != 1 )); then
    echo "ERROR: ANDROID_SERIAL is unset and expected exactly one online adb device; found ${#ONLINE_DEVICES[@]}" >&2
    adb devices -l >&2 || true
    exit 2
  fi
  SERIAL="${ONLINE_DEVICES[0]}"
fi
ADB=(adb -s "$SERIAL")
printf 'ADB_SERIAL=%s\n' "$SERIAL"

set +e
state_output="$("${ADB[@]}" get-state 2>&1)"
state_status=$?
set -e
if (( state_status != 0 )) || [[ "$(printf '%s' "$state_output" | tr -d '\r[:space:]')" != 'device' ]]; then
  echo 'ERROR: selected adb device is not ready.' >&2
  echo "  serial: $SERIAL" >&2
  echo "  exit: $state_status" >&2
  echo "  output: ${state_output:-<empty>}" >&2
  exit 3
fi

api_level="$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r[:space:]' || true)"
printf 'ANDROID_API_LEVEL=%s\n' "${api_level:-unknown}"
if [[ ! "$api_level" =~ ^[0-9]+$ ]] || (( api_level < 33 )); then
  echo "ERROR: POST_NOTIFICATIONS runtime permission test requires API 33+; got ${api_level:-unknown}." >&2
  exit 3
fi

# Verify the file passed to adb is actually the intended target APK. This avoids the dangerous
# case where a stale/misnamed APK is installed while a previously installed package is checked.
APKANALYZER="$(command -v apkanalyzer || true)"
if [[ -z "$APKANALYZER" && -n "${ANDROID_HOME:-}" ]]; then
  APKANALYZER="$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [[ -z "$APKANALYZER" ]]; then
  echo 'ERROR: apkanalyzer is unavailable; cannot verify the target APK application ID.' >&2
  exit 2
fi
actual_package="$("$APKANALYZER" manifest application-id "$APK_PATH" 2>&1 | tr -d '\r' | tail -n 1)"
if [[ "$actual_package" != "$PACKAGE_NAME" ]]; then
  echo "ERROR: APK application ID mismatch; expected $PACKAGE_NAME, found ${actual_package:-<empty>}." >&2
  exit 2
fi
printf 'VERIFIED_APK_APPLICATION_ID=%s\n' "$actual_package"
sha256sum "$APK_PATH"

# ADB's documented -g install flag grants every runtime permission declared in the APK.
# The app manifest declares POST_NOTIFICATIONS. Updating with -r retains app data while
# replacing the installed APK; the package may restart, but app/fixture data is not cleared.
set +e
install_output="$("${ADB[@]}" install -r -g "$APK_PATH" 2>&1)"
install_status=$?
set -e
if [[ -n "$install_output" ]]; then
  printf '%s\n' "$install_output"
fi
if (( install_status != 0 )) || ! grep -q 'Success' <<<"$install_output"; then
  echo 'ERROR: adb install -r -g did not complete successfully.' >&2
  echo "  serial: $SERIAL" >&2
  echo "  exit: $install_status" >&2
  echo "  apk: $APK_PATH" >&2
  echo '  package manager state:' >&2
  "${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" 2>&1 | tail -n 100 >&2 || true
  exit 4
fi

package_path="$("${ADB[@]}" shell pm path "$PACKAGE_NAME" 2>&1 || true)"
if ! grep -q '^package:' <<<"$package_path"; then
  echo "ERROR: target package is not installed after adb install." >&2
  echo "  pm path output: ${package_path:-<empty>}" >&2
  exit 5
fi
printf 'INSTALLED_PACKAGE_PATH=%s\n' "$(printf '%s' "$package_path" | tr '\r' ' ')"

# Verify the actual per-user PackageManager permission state. AOSP DumpHelper documents:
# dumpsys package check-permission <permission> <package> [<user>], returning 0 or -1.
set +e
check_output="$("${ADB[@]}" shell dumpsys package check-permission "$PERMISSION" "$PACKAGE_NAME" "$USER_ID" 2>&1)"
check_status=$?
set -e
normalized_check="$(printf '%s' "$check_output" | tr -d '\r[:space:]')"
if (( check_status != 0 )) || [[ "$normalized_check" != '0' ]]; then
  echo "ERROR: $PERMISSION is not verified as granted for $PACKAGE_NAME, user $USER_ID." >&2
  echo "  command: adb -s $SERIAL shell dumpsys package check-permission $PERMISSION $PACKAGE_NAME $USER_ID" >&2
  echo "  exit: $check_status" >&2
  echo "  raw output: ${check_output:-<empty>}" >&2
  echo '  package runtime-permission details:' >&2
  "${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" 2>&1 | grep -A 35 -B 5 -E 'requested permissions|runtime permissions|POST_NOTIFICATIONS|User 0:' >&2 || true
  echo '  notification app-op details:' >&2
  "${ADB[@]}" shell cmd appops get "$PACKAGE_NAME" POST_NOTIFICATION 2>&1 >&2 || true
  exit 6
fi

test_package_path="$("${ADB[@]}" shell pm path dev.screenveil.app.test 2>&1 || true)"
if ! grep -q '^package:' <<<"$test_package_path"; then
  echo 'ERROR: instrumentation APK is not installed; refusing to launch AndroidJUnitRunner.' >&2
  echo "  pm path output: ${test_package_path:-<empty>}" >&2
  exit 7
fi
printf 'INSTALLED_TEST_PACKAGE_PATH=%s\n' "$(printf '%s' "$test_package_path" | tr '\r' ' ')"
printf '%s\n' "$PERMISSION is GRANTED (PackageManager result 0)."
printf '%s\n' 'SCREENVEIL_CI_PERMISSION_SETUP_RESULT=PASS'
