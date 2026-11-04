#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"

if ! grep -Fq 'implementation("androidx.fragment:fragment:1.9.1")' "$GRADLE_FILE"; then
  echo "ERROR: Activity Result API compatibility dependency is missing or not pinned to Fragment 1.9.1." >&2
  exit 1
fi
if ! grep -Fq 'version { strictly("1.9.1") }' "$GRADLE_FILE"; then
  echo "ERROR: Fragment 1.9.1 is not strictly constrained." >&2
  exit 1
fi

echo "PASS: androidx.fragment:fragment is explicitly and strictly pinned to 1.9.1."
