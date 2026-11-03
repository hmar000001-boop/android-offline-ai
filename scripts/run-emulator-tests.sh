#!/usr/bin/env bash
# Invoked as one command by reactivecircus/android-emulator-runner.
# Diagnose test APK runtime packaging before executing the instrumentation suite.
set -u

# Remove stale .kt fixture files left by overlaying a new source archive on an
# older checkout, then fail early if any duplicate fixture declaration remains.
bash scripts/prepare-instrumentation-sources.sh

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
