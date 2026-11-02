#!/usr/bin/env bash
# Invoked as one command by reactivecircus/android-emulator-runner.
# Keep pipeline output visible and return Gradle's exit status to the action.
set -u

set +e
gradle --no-daemon connectedDebugAndroidTest --stacktrace 2>&1 | tee emulator-tests.log
pipeline_status=("${PIPESTATUS[@]}")
set -e

gradle_status="${pipeline_status[0]}"
tee_status="${pipeline_status[1]}"

if (( gradle_status != 0 )); then
  exit "$gradle_status"
fi
exit "$tee_status"
