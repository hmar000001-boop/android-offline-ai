# Emulator CI script parsing fix

Both emulator jobs successfully created and booted their Android Virtual Devices. The first
script attempt failed because `set -o pipefail` is not a POSIX `/bin/sh` option.

A second attempt used a multiline `if/then/fi` block. That also failed, but for a different
reason: `reactivecircus/android-emulator-runner@v2` parses the `script` input by splitting it
on newlines and executes each non-empty line independently using `sh -c`. Consequently a
multiline compound shell block is broken into incomplete commands (`if ...; then` is executed
without its `then` body and `fi`). This behavior is visible in the action's `src/script-parser.ts`
implementation: it splits the input on `\n` and returns each trimmed line as a separate script.

The workflow now gives the action exactly one command:

```yaml
script: bash scripts/run-emulator-tests.sh
```

The Bash script runs Gradle, tees output to `emulator-tests.log`, and propagates Gradle's exit
status. A separate `if: always()` workflow step prints the log and uploads it with the emulator
reports. This keeps multiline shell logic in a real file instead of the action's line-splitting
`script` input.

The previous runs did not execute `connectedDebugAndroidTest`; emulator boot success is not
evidence that the instrumentation tests passed. A successful rerun is still required.
