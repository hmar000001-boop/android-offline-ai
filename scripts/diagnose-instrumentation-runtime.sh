#!/usr/bin/env bash
# Diagnostic-only investigation of Kotlin runtime availability in the Android-test APK.
# This script does not modify application or test source files.
set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$ROOT_DIR/build/instrumentation-runtime-diagnostics"
mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.txt"
: > "$SUMMARY"

record() {
  printf '%s\n' "$*" | tee -a "$SUMMARY"
}

run_capture() {
  local label="$1"
  local output="$2"
  shift 2

  record ""
  record "===== $label ====="
  record "COMMAND: $*"
  "$@" 2>&1 | tee "$output"
  local -a statuses=("${PIPESTATUS[@]}")
  local command_status="${statuses[0]:-1}"
  local tee_status="${statuses[1]:-1}"
  record "COMMAND_EXIT_CODE=$command_status"
  record "TEE_EXIT_CODE=$tee_status"
  return "$command_status"
}

record "ScreenVeil instrumentation runtime diagnostic"
record "UTC: $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
record "Gradle: $(gradle --version 2>&1 | grep -m1 '^Gradle ' || true)"
record "Java: $(java -version 2>&1 | head -n1)"
record "ANDROID_HOME=${ANDROID_HOME:-<unset>}"

# A. Resolve the exact runtime configuration used by the debug instrumentation APK.
run_capture \
  "debugAndroidTestRuntimeClasspath dependency tree" \
  "$OUT_DIR/debugAndroidTestRuntimeClasspath.txt" \
  gradle --no-daemon --no-build-cache :app:dependencies \
    --configuration debugAndroidTestRuntimeClasspath || true

run_capture \
  "Kotlin stdlib dependency insight" \
  "$OUT_DIR/kotlin-stdlib-dependency-insight.txt" \
  gradle --no-daemon --no-build-cache :app:dependencyInsight \
    --configuration debugAndroidTestRuntimeClasspath \
    --dependency org.jetbrains.kotlin:kotlin-stdlib || true

run_capture \
  "debugAndroidTestCompileClasspath dependency tree" \
  "$OUT_DIR/debugAndroidTestCompileClasspath.txt" \
  gradle --no-daemon --no-build-cache :app:dependencies \
    --configuration debugAndroidTestCompileClasspath || true

# B. Rebuild the test APK with both task-output reuse and the Gradle build cache disabled.
run_capture \
  "Rebuild instrumentation APK without build cache" \
  "$OUT_DIR/assembleDebugAndroidTest-no-cache.txt" \
  gradle --no-daemon --no-build-cache --rerun-tasks \
    :app:assembleDebugAndroidTest || true

APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$APK" ]]; then
  APK="$(find "$ROOT_DIR/app/build/outputs/apk/androidTest" -type f -name '*.apk' 2>/dev/null | sort | head -n1 || true)"
fi

if [[ -n "$APK" && -f "$APK" ]]; then
  cp "$APK" "$OUT_DIR/instrumentation-test.apk"
  record "INSTRUMENTATION_APK=$APK"
  record "INSTRUMENTATION_APK_BYTES=$(wc -c < "$APK" | tr -d ' ')"
  sha256sum "$APK" | tee "$OUT_DIR/instrumentation-test.apk.sha256"
else
  record "INSTRUMENTATION_APK_NOT_FOUND=true"
fi

# C. Inspect DEX definitions, not just Gradle's dependency graph.
APKANALYZER="$(command -v apkanalyzer || true)"
if [[ -z "$APKANALYZER" && -n "${ANDROID_HOME:-}" ]]; then
  APKANALYZER="$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer 2>/dev/null | sort -V | tail -n1 || true)"
fi

if [[ -n "$APK" && -f "$APK" && -n "$APKANALYZER" ]]; then
  record "APKANALYZER=$APKANALYZER"
  run_capture \
    "DEX file list" \
    "$OUT_DIR/dex-list.txt" \
    "$APKANALYZER" dex list "$APK" || true

  run_capture \
    "DEX defined classes" \
    "$OUT_DIR/dex-defined-classes.txt" \
    "$APKANALYZER" dex packages --defined-only "$APK" || true

  run_capture \
    "DEX all class references" \
    "$OUT_DIR/dex-all-classes-and-references.txt" \
    "$APKANALYZER" dex packages "$APK" || true

  if grep -Fq 'kotlin.jvm.internal.Intrinsics' "$OUT_DIR/dex-defined-classes.txt"; then
    record "DEX_DEFINES_KOTLIN_INTRINSICS=YES"
  else
    record "DEX_DEFINES_KOTLIN_INTRINSICS=NO_OR_NOT_LISTED"
  fi

  run_capture \
    "Direct lookup of kotlin.jvm.internal.Intrinsics in DEX" \
    "$OUT_DIR/intrinsics-class-bytecode.txt" \
    "$APKANALYZER" dex code --class kotlin.jvm.internal.Intrinsics "$APK" || true
else
  record "APKANALYZER_NOT_FOUND_OR_APK_MISSING=true"
fi

# Capture the merged instrumentation manifest to verify the provider is actually declared.
if [[ -n "$APK" && -f "$APK" && -n "$APKANALYZER" ]]; then
  run_capture \
    "Merged instrumentation manifest" \
    "$OUT_DIR/instrumentation-manifest.txt" \
    "$APKANALYZER" manifest print "$APK" || true
  run_capture \
    "Instrumentation application ID" \
    "$OUT_DIR/instrumentation-application-id.txt" \
    "$APKANALYZER" manifest application-id "$APK" || true
fi

# D. Reproduce provider process startup independently of AndroidJUnitRunner.
# The provider is exported in the test APK manifest, so `content call` causes Android
# to create its process and invoke ContentProvider.call() without starting the test suite.
if command -v adb >/dev/null 2>&1 && [[ -n "$APK" && -f "$APK" && -n "$APKANALYZER" ]]; then
  TEST_APP_ID="$("$APKANALYZER" manifest application-id "$APK" 2>/dev/null | tail -n1 | tr -d '\r' || true)"
  AUTHORITY="dev.screenveil.app.test.screenveil-fixture-state"
  PROVIDER_URI="content://$AUTHORITY"

  record "TEST_APP_ID=${TEST_APP_ID:-<unknown>}"
  record "PROVIDER_URI=$PROVIDER_URI"

  if [[ -n "$TEST_APP_ID" ]]; then
    run_capture \
      "Install target and instrumentation APKs" \
      "$OUT_DIR/install-test-apks.txt" \
      gradle --no-daemon installDebug installDebugAndroidTest || true

    run_capture \
      "Installed target package path" \
      "$OUT_DIR/target-package-path.txt" \
      adb shell pm path dev.screenveil.app || true
    run_capture \
      "Installed instrumentation package path" \
      "$OUT_DIR/test-package-path.txt" \
      adb shell pm path "$TEST_APP_ID" || true

    adb logcat -c >/dev/null 2>&1 || true
    run_capture \
      "Force-stop instrumentation package before provider probe" \
      "$OUT_DIR/provider-force-stop.txt" \
      adb shell am force-stop "$TEST_APP_ID" || true

    run_capture \
      "Independent ContentProvider startup probe" \
      "$OUT_DIR/provider-content-call.txt" \
      adb shell content call --uri "$PROVIDER_URI" --method read_fixture_state || true

    sleep 2
    run_capture \
      "Instrumentation package process after provider probe" \
      "$OUT_DIR/provider-process-state.txt" \
      adb shell pidof "$TEST_APP_ID" || true

    adb logcat -d -v threadtime > "$OUT_DIR/provider-probe-logcat.txt" 2>&1 || true
    grep -E -C 4 \
      'AndroidRuntime|NoClassDefFoundError|ClassNotFoundException|ScreenVeilFixtureStateProvider|dev\.screenveil\.app\.test' \
      "$OUT_DIR/provider-probe-logcat.txt" \
      > "$OUT_DIR/provider-probe-logcat-relevant.txt" || true

    if grep -Eq \
      'NoClassDefFoundError.*kotlin/jvm/internal/Intrinsics|ClassNotFoundException.*kotlin\.jvm\.internal\.Intrinsics' \
      "$OUT_DIR/provider-probe-logcat.txt"; then
      record "PROVIDER_PROBE_RESULT=CRASHED_MISSING_KOTLIN_INTRINSICS"
    elif grep -q 'Result: Bundle' "$OUT_DIR/provider-content-call.txt"; then
      record "PROVIDER_PROBE_RESULT=CONTENT_PROVIDER_CALL_RETURNED_BUNDLE"
    else
      record "PROVIDER_PROBE_RESULT=INCONCLUSIVE_CHECK_LOGS"
    fi
  else
    record "PROVIDER_PROBE_SKIPPED=COULD_NOT_READ_TEST_APPLICATION_ID"
  fi
else
  record "PROVIDER_PROBE_SKIPPED=ADB_OR_APK_UNAVAILABLE"
fi

record ""
record "Diagnostic complete. No production source or dependencies were modified by this script."
