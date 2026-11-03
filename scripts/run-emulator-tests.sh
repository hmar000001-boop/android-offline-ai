#!/usr/bin/env bash
# Invoked as one command by reactivecircus/android-emulator-runner.
# Diagnose test APK runtime packaging before executing the instrumentation suite.
set -euo pipefail

# Remove stale .kt fixture files left by overlaying a new source archive on an
# older checkout, then fail early if any duplicate fixture declaration remains.
bash scripts/prepare-instrumentation-sources.sh

# Include the exact pinned segmentation model in the instrumentation APK. This is a real model
# initialization/inference smoke test, not a substitute for the separate full manual model audit.
SEGMENTER_TEST_ASSETS="app/src/androidTest/assets/ci-models"
rm -rf "$SEGMENTER_TEST_ASSETS"
mkdir -p "$SEGMENTER_TEST_ASSETS"
if ! python3 scripts/verify-model-pins.py \
    --download \
    --only google-selfie-multiclass-256-tflite \
    --output-dir "$SEGMENTER_TEST_ASSETS"; then
  echo "Pinned body-segmentation model download/verification failed; emulator tests must not run without it." >&2
  exit 1
fi

set +e
bash scripts/diagnose-instrumentation-runtime.sh 2>&1 | tee instrumentation-runtime-diagnostics.log
diagnostic_status=("${PIPESTATUS[@]}")
set -e

diagnostic_command_status="${diagnostic_status[0]:-1}"
diagnostic_tee_status="${diagnostic_status[1]:-1}"

# Keep running the real suite even if a diagnostic command was unavailable or failed.
# The provider probe is intentionally independent so its crash can be distinguished
# from AndroidJUnitRunner startup and the individual test cases.
set +e
gradle --no-daemon connectedDebugAndroidTest --stacktrace 2>&1 | tee emulator-tests.log
test_status=("${PIPESTATUS[@]}")
set -e

gradle_status="${test_status[0]:-1}"
tee_status="${test_status[1]:-1}"

if (( gradle_status != 0 || tee_status != 0 )); then
  FAILURE_DIR="build/emulator-failure-diagnostics"
  mkdir -p "$FAILURE_DIR"
  {
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
fi

if (( gradle_status != 0 )); then
  exit "$gradle_status"
fi
if (( tee_status != 0 )); then
  exit "$tee_status"
fi
if (( diagnostic_command_status != 0 )); then
  exit "$diagnostic_command_status"
fi
exit "$diagnostic_tee_status"
