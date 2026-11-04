#!/usr/bin/env bash
# Invoked as one command by reactivecircus/android-emulator-runner.
set -euo pipefail

printf '%s\n' 'SCREENVEIL_CI_SOURCE_MARKER=R6_V5_PACKAGE_DUMP_DIAGNOSTIC'
printf 'UTC=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf 'ANDROID_SERIAL=%s\n' "${ANDROID_SERIAL:-<unset>}"
# Enable AGP install-time runtime-permission grants before every Gradle invocation,
# including the diagnostic script's own APK installation tasks.
export SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1
printf '%s\n' 'SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1'
sha256sum scripts/run-emulator-tests.sh scripts/install-test-app-with-grants.sh app/build.gradle.kts

# Remove stale .kt fixture files left by overlaying a new source archive on an
# older checkout, then fail early if any duplicate fixture declaration remains.
bash scripts/prepare-instrumentation-sources.sh

# Include the exact pinned person-detector and body-segmentation artifacts in the
# instrumentation APK. The tests verify real CPU runtime/model plumbing, not accuracy.
MODEL_TEST_ASSETS="app/src/androidTest/assets/ci-models"
rm -rf "$MODEL_TEST_ASSETS"
mkdir -p "$MODEL_TEST_ASSETS"
if ! python3 scripts/verify-model-pins.py \
    --download \
    --only google-efficientdet-lite0-f32 \
    --only google-selfie-multiclass-256-tflite \
    --output-dir "$MODEL_TEST_ASSETS"; then
  echo 'Pinned EfficientDet/Selfie model download or verification failed; emulator tests must not run without both verified artifacts.' >&2
  exit 1
fi

set +e
bash scripts/diagnose-instrumentation-runtime.sh 2>&1 | tee instrumentation-runtime-diagnostics.log
diagnostic_status=("${PIPESTATUS[@]}")
set -e
diagnostic_command_status="${diagnostic_status[0]:-1}"
diagnostic_tee_status="${diagnostic_status[1]:-1}"

# The diagnostic invokes Gradle several times. Refuse to proceed unless the app module confirms
# AGP's installOptions hook was configured; otherwise the connected test may reinstall the APK
# without the runtime-permission grant.
if ! grep -q '^SCREENVEIL_GRADLE_INSTALL_OPTIONS=-g$' instrumentation-runtime-diagnostics.log; then
  echo 'ERROR: Gradle did not confirm SCREENVEIL_CI_GRANT_RUNTIME_PERMISSIONS=1 / AGP installOptions=-g.' >&2
  FAILURE_DIR='build/emulator-preflight-failure'
  mkdir -p "$FAILURE_DIR"
  {
    echo 'SCREENVEIL_CI_SOURCE_MARKER=R6_V5_PACKAGE_DUMP_DIAGNOSTIC'
    echo 'SCREENVEIL_AGP_INSTALL_GRANT_HOOK=NOT_CONFIRMED'
    echo "UTC=$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "DIAGNOSTIC_COMMAND_EXIT=$diagnostic_command_status"
    echo "DIAGNOSTIC_TEE_EXIT=$diagnostic_tee_status"
  } > "$FAILURE_DIR/summary.txt"
  cp instrumentation-runtime-diagnostics.log "$FAILURE_DIR/" 2>/dev/null || true
  adb logcat -b all -d -v threadtime > "$FAILURE_DIR/logcat-full.txt" 2>&1 || true
  exit 20
fi
printf '%s\n' 'SCREENVEIL_AGP_INSTALL_GRANT_HOOK=CONFIRMED'

# The diagnostic script assembles and installs both APKs and probes the test provider.
# Update the target APK with ADB's documented -g flag as a preflight; AGP's connected-test
# installation is independently configured with the same -g option below.
set +e
bash scripts/install-test-app-with-grants.sh \
  dev.screenveil.app \
  app/build/outputs/apk/debug/app-debug.apk \
  2>&1 | tee permission-preflight.log
permission_status=("${PIPESTATUS[@]}")
set -e
permission_command_status="${permission_status[0]:-1}"
permission_tee_status="${permission_status[1]:-1}"

if (( permission_command_status != 0 || permission_tee_status != 0 )); then
  FAILURE_DIR='build/emulator-preflight-failure'
  mkdir -p "$FAILURE_DIR"
  {
    echo 'SCREENVEIL_CI_SOURCE_MARKER=R6_V5_PACKAGE_DUMP_DIAGNOSTIC'
    echo "UTC=$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "EMULATOR_SERIAL=${ANDROID_SERIAL:-unknown}"
    echo "ANDROID_API_LEVEL=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
    echo "PERMISSION_PREFLIGHT_COMMAND_EXIT=$permission_command_status"
    echo "PERMISSION_TEE_EXIT=$permission_tee_status"
  } > "$FAILURE_DIR/summary.txt"
  adb logcat -b all -d -v threadtime > "$FAILURE_DIR/logcat-full.txt" 2>&1 || true
  adb shell dumpsys package dev.screenveil.app > "$FAILURE_DIR/target-package.txt" 2>&1 || true
  adb shell dumpsys activity exit-info dev.screenveil.app > "$FAILURE_DIR/target-exit-info.txt" 2>&1 || true
  cp permission-preflight.log "$FAILURE_DIR/permission-preflight.log" 2>/dev/null || true
  if (( permission_command_status != 0 )); then exit "$permission_command_status"; fi
  exit "$permission_tee_status"
fi

# Let AGP install the target and test APKs normally. The app module's Installation.installOptions
# adds `-g` to AGP's own install command as well, so its internal install cannot undo the grant
# verified by the host preflight. @Before performs a final in-process permission assertion.
set +e
gradle --no-daemon connectedDebugAndroidTest --stacktrace 2>&1 | tee emulator-tests.log
test_status=("${PIPESTATUS[@]}")
set -e
gradle_status="${test_status[0]:-1}"
tee_status="${test_status[1]:-1}"

if (( gradle_status != 0 || tee_status != 0 )); then
  FAILURE_DIR='build/emulator-failure-diagnostics'
  mkdir -p "$FAILURE_DIR"
  {
    echo 'SCREENVEIL_CI_SOURCE_MARKER=R6_V5_PACKAGE_DUMP_DIAGNOSTIC'
    echo "UTC=$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "EMULATOR_SERIAL=${ANDROID_SERIAL:-unknown}"
    echo "ANDROID_API_LEVEL=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
    echo "DEVICE_BUILD=$(adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r' || true)"
    echo "GRADLE_EXIT=$gradle_status"
    echo "TEE_EXIT=$tee_status"
  } > "$FAILURE_DIR/summary.txt"
  adb logcat -b all -d -v threadtime > "$FAILURE_DIR/logcat-full.txt" 2>&1 || true
  adb logcat -b crash -d -v threadtime > "$FAILURE_DIR/logcat-crash-buffer.txt" 2>&1 || true
  adb shell dumpsys activity exit-info dev.screenveil.app > "$FAILURE_DIR/target-exit-info.txt" 2>&1 || true
  adb shell dumpsys activity exit-info dev.screenveil.app.test > "$FAILURE_DIR/test-exit-info.txt" 2>&1 || true
  adb shell dumpsys package check-permission android.permission.POST_NOTIFICATIONS dev.screenveil.app 0 > "$FAILURE_DIR/notification-permission-check-query-diagnostic.txt" 2>&1 || true
  adb shell dumpsys dropbox --print data_app_crash > "$FAILURE_DIR/dropbox-app-crash.txt" 2>&1 || true
  adb shell dumpsys dropbox --print data_app_native_crash > "$FAILURE_DIR/dropbox-native-crash.txt" 2>&1 || true
  grep -E -C 8 \
    'FATAL EXCEPTION|Fatal signal|DEBUG  |AndroidRuntime|SIGSEGV|SIGABRT|Canvas: trying to use a recycled bitmap|ScreenProtectionAccessibilityService|ScreenVeil redaction|Instrumentation run failed|Process crashed' \
    "$FAILURE_DIR/logcat-full.txt" > "$FAILURE_DIR/logcat-relevant.txt" || true
  adb shell dumpsys activity processes > "$FAILURE_DIR/activity-processes.txt" 2>&1 || true
  adb shell dumpsys window windows > "$FAILURE_DIR/window-windows.txt" 2>&1 || true
  adb shell dumpsys accessibility > "$FAILURE_DIR/accessibility.txt" 2>&1 || true
  adb shell dumpsys meminfo dev.screenveil.app > "$FAILURE_DIR/target-meminfo.txt" 2>&1 || true
  adb shell dumpsys meminfo dev.screenveil.app.test > "$FAILURE_DIR/test-meminfo.txt" 2>&1 || true
  adb shell pidof dev.screenveil.app > "$FAILURE_DIR/target-pid.txt" 2>&1 || true
  adb shell pidof dev.screenveil.app.test > "$FAILURE_DIR/test-pid.txt" 2>&1 || true
  cp permission-preflight.log "$FAILURE_DIR/permission-preflight.log" 2>/dev/null || true
fi

if (( gradle_status != 0 )); then exit "$gradle_status"; fi
if (( tee_status != 0 )); then exit "$tee_status"; fi
if (( diagnostic_command_status != 0 )); then exit "$diagnostic_command_status"; fi
exit "$diagnostic_tee_status"
