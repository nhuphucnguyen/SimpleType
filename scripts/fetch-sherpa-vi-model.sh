#!/usr/bin/env bash
#
# Provisions the Vietnamese Zipformer model + Silero VAD for SherpaAsrEngine and pushes them
# into the app's internal storage on a connected device/emulator.
#
# Why adb push instead of an in-app download: the model is distributed as a .tar.bz2, which
# the JDK can't unpack without extra dependencies, and bundling ~80 MB in the APK is wasteful.
# The debug app is `debuggable`, so `run-as` lets us write straight into its files dir.
#
# Prerequisites:
#   - A debug build of dev.phucngu.simpletype already installed (./gradlew installDebug)
#   - adb on PATH with exactly one device/emulator connected
#
# Model: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09  (CC-BY-NC-ND-4.0, non-commercial)
#   https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
#
set -euo pipefail

PKG="dev.phucngu.simpletype"
MODEL="sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"
BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
DEST_SUBDIR="files/models/sherpa-vi"   # relative to the app's data dir (run-as cwd)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

echo "==> Downloading model archive ($MODEL.tar.bz2)"
curl -fL --progress-bar "$BASE_URL/$MODEL.tar.bz2" -o model.tar.bz2

# Silero VAD: use the v5 model (3 inputs / 2 outputs). The k2-fsa asr-models silero_vad.onnx
# is a 3-in/3-out variant that the previously pinned sherpa-onnx runtime rejects ("Unsupported silero vad
# model"), causing an instant exit(-1) on this device. The official v5 model is compatible.
echo "==> Downloading silero_vad.onnx (known-good v5 model)"
curl -fL --progress-bar "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx" -o silero_vad.onnx

echo "==> Extracting"
tar xf model.tar.bz2

# Files SherpaAsrEngine.REQUIRED_FILES expects (flattened into one dir).
FILES=(
  "$MODEL/encoder.int8.onnx"
  "$MODEL/decoder.onnx"
  "$MODEL/joiner.int8.onnx"
  "$MODEL/tokens.txt"
  "silero_vad.onnx"
)

for f in "${FILES[@]}"; do
  [[ -f "$f" ]] || { echo "ERROR: expected file missing after extract: $f" >&2; exit 1; }
done

echo "==> Checking device + app"
adb get-state >/dev/null
adb shell "run-as $PKG true" 2>/dev/null || {
  echo "ERROR: cannot run-as $PKG. Is the *debug* app installed? (./gradlew installDebug)" >&2
  exit 1
}

echo "==> Pushing model into $PKG/$DEST_SUBDIR"
adb shell "run-as $PKG mkdir -p $DEST_SUBDIR"
for f in "${FILES[@]}"; do
  name="$(basename "$f")"
  # Stage in a world-readable tmp dir, then copy in via run-as (app's private storage).
  adb push "$f" "/data/local/tmp/$name" >/dev/null
  adb shell "run-as $PKG cp /data/local/tmp/$name $DEST_SUBDIR/$name"
  adb shell "rm -f /data/local/tmp/$name"
  echo "    ✓ $name"
done

echo "==> Done. Installed files:"
adb shell "run-as $PKG ls -la $DEST_SUBDIR"
