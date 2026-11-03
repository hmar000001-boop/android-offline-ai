#!/usr/bin/env bash
# Remove legacy Kotlin fixture files left behind when an older source ZIP was
# overlaid onto an existing checkout. The fixture components are Java-only:
# they are declared in the instrumentation APK manifest and must not rely on
# the target APK's Kotlin runtime being present in the test process.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$ROOT/app/src/androidTest"
FIXTURE_ROOT="$TEST_ROOT/java/dev/screenveil/app/fixture"

if [[ ! -d "$TEST_ROOT" ]]; then
  echo "ERROR: instrumentation source directory not found: $TEST_ROOT" >&2
  exit 2
fi

# These are the exact legacy files reported by the Kotlin compiler in the
# 2026-11-03 CI log. Remove them even when a ZIP was extracted over an older
# checkout instead of replacing the checkout cleanly.
for class_name in ScreenVeilFixtureActivity ScreenVeilFixtureStateProvider; do
  legacy_file="$FIXTURE_ROOT/$class_name.kt"
  if [[ -f "$legacy_file" ]]; then
    rm -- "$legacy_file"
    echo "Removed stale Kotlin fixture: ${legacy_file#"$ROOT"/}"
  fi
done

# Require exactly one source declaration for each manifest-declared fixture,
# and require that declaration to be the Java source file used by this project.
for class_name in ScreenVeilFixtureActivity ScreenVeilFixtureStateProvider; do
  expected_file="$FIXTURE_ROOT/$class_name.java"
  if [[ ! -f "$expected_file" ]]; then
    echo "ERROR: expected Java fixture is missing: ${expected_file#"$ROOT"/}" >&2
    exit 3
  fi

  mapfile -t declarations < <(
    grep -RInE \
      --include='*.java' --include='*.kt' \
      "^[[:space:]]*(public[[:space:]]+)?(open[[:space:]]+)?class[[:space:]]+$class_name([[:space:]:<{]|$)" \
      "$TEST_ROOT" || true
  )

  if [[ "${#declarations[@]}" -ne 1 || "${declarations[0]%%:*}" != "$expected_file" ]]; then
    echo "ERROR: expected exactly one declaration of $class_name in ${expected_file#"$ROOT"/}." >&2
    printf 'Found declarations:\n' >&2
    if ((${#declarations[@]})); then
      printf '  %s\n' "${declarations[@]}" >&2
    else
      echo '  (none)' >&2
    fi
    exit 4
  fi
  echo "Verified single Java declaration: $class_name"
done
