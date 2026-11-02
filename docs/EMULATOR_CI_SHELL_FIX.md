# Emulator CI shell compatibility fix

The first API 34 and API 36 emulator jobs booted successfully, but the test command did not run.
`reactivecircus/android-emulator-runner@v2` executes its `script` input with `/bin/sh`;
`set -o pipefail` is a Bash extension and caused `/bin/sh: Illegal option -o pipefail`.

The emulator script now uses POSIX-compatible `if ...; then ... else ... fi`, writes Gradle
output to `emulator-tests.log`, prints that file to the Actions log, and returns the original
Gradle exit code. This preserves failure reporting without requiring Bash or a shell pipeline.

The emulator boot itself succeeded for both API levels in the supplied run. The instrumentation
tests were not reached in that run. A successful rerun is still required to validate compilation
and the Android service lifecycle.
