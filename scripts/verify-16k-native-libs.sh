#!/usr/bin/env bash
# Fail the build if any packaged 64-bit native library is incompatible with 16 KB pages.
# This validates both ELF PT_LOAD alignment and APK ZIP alignment of uncompressed .so files.
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 2
fi
command -v unzip >/dev/null || { echo 'ERROR: unzip is required' >&2; exit 2; }
command -v readelf >/dev/null || { echo 'ERROR: readelf (binutils) is required' >&2; exit 2; }
ZIPALIGN_BIN="$(command -v zipalign || true)"
if [[ -z "$ZIPALIGN_BIN" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    ZIPALIGN_BIN="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name zipalign -print | sort -V | tail -n 1)"
  fi
fi
[[ -n "$ZIPALIGN_BIN" && -x "$ZIPALIGN_BIN" ]] || {
  echo 'ERROR: Android zipalign was not found on PATH or under ANDROID_HOME/ANDROID_SDK_ROOT/build-tools.' >&2
  exit 2
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
unzip -q "$APK" 'lib/*/*.so' -d "$TMP_DIR"

checked=0
for ABI in arm64-v8a x86_64; do
  ABI_DIR="$TMP_DIR/lib/$ABI"
  [[ -d "$ABI_DIR" ]] || continue
  shopt -s nullglob
  for SO in "$ABI_DIR"/*.so; do
    checked=$((checked + 1))
    mapfile -t ALIGNMENTS < <(readelf -lW "$SO" | awk '$1 == "LOAD" { print $NF }')
    if [[ ${#ALIGNMENTS[@]} -eq 0 ]]; then
      echo "ERROR: no PT_LOAD segments found in $SO" >&2
      exit 3
    fi
    for ALIGN in "${ALIGNMENTS[@]}"; do
      VALUE=$((ALIGN))
      if (( VALUE < 16384 )); then
        echo "ERROR: 16 KB ELF alignment failure: $SO LOAD alignment=$ALIGN" >&2
        exit 4
      fi
    done
    echo "16KB_ELF_OK abi=$ABI library=$(basename "$SO") loads=${#ALIGNMENTS[@]}"
  done
done

if (( checked == 0 )); then
  echo 'ERROR: APK contains no 64-bit native libraries; unexpected for this build.' >&2
  exit 5
fi

"$ZIPALIGN_BIN" -c -P 16 -v 4 "$APK"
echo "16KB_NATIVE_ALIGNMENT_OK apk=$APK libraries=$checked"
