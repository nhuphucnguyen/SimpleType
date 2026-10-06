#!/usr/bin/env bash
#
# Provisions the Vietnamese Zipformer model + Silero VAD for SherpaAsrEngine and pushes them
# into the app's internal storage on a connected device/emulator.
#
# Normally not needed: Settings → Voice typing models downloads and unpacks the same files
# in-app (ModelManager). This script is a dev shortcut that skips the in-app download.
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
MODELS_DIR="files/models"              # relative to the app's data dir (run-as cwd)
DEST_SUBDIR="$MODELS_DIR/sherpa-vi"     # SherpaModel.VIETNAMESE.dirName; the VAD sits in MODELS_DIR

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

echo "==> Downloading model archive ($MODEL.tar.bz2)"
curl -fL --progress-bar "$BASE_URL/$MODEL.tar.bz2" -o model.tar.bz2

# Silero VAD: use the official snakers4 model (v5/v6, 3 inputs / 2 outputs), pinned to a release
# tag so builds are reproducible. The k2-fsa asr-models silero_vad.onnx is a 3-in/3-out variant that
# an older sherpa-onnx runtime rejected ("Unsupported silero vad model"), causing an instant exit(-1).
SILERO_VAD_TAG="${SILERO_VAD_TAG:-v6.2.3}"
echo "==> Downloading silero_vad.onnx (snakers4/silero-vad $SILERO_VAD_TAG)"
curl -fL --progress-bar "https://github.com/snakers4/silero-vad/raw/$SILERO_VAD_TAG/src/silero_vad/data/silero_vad.onnx" -o silero_vad.onnx

echo "==> Extracting"
tar xf model.tar.bz2

# Files SherpaModel.VIETNAMESE.files expects (flattened into one dir).
FILES=(
  "$MODEL/encoder.int8.onnx"
  "$MODEL/decoder.onnx"
  "$MODEL/joiner.int8.onnx"
  "$MODEL/tokens.txt"
)

for f in "${FILES[@]}" silero_vad.onnx; do
  [[ -f "$f" ]] || { echo "ERROR: expected file missing after extract: $f" >&2; exit 1; }
done

echo "==> Checking device + app"
adb get-state >/dev/null
adb shell "run-as $PKG true" 2>/dev/null || {
  echo "ERROR: cannot run-as $PKG. Is the *debug* app installed? (./gradlew installDebug)" >&2
  exit 1
}

# Stage in a world-readable tmp dir, then copy in via run-as (app's private storage).
push() {
  local src="$1" dest_dir="$2" name
  name="$(basename "$src")"
  adb push "$src" "/data/local/tmp/$name" >/dev/null
  adb shell "run-as $PKG cp /data/local/tmp/$name $dest_dir/$name"
  adb shell "rm -f /data/local/tmp/$name"
  echo "    ✓ $dest_dir/$name"
}

echo "==> Pushing model into $PKG/$DEST_SUBDIR"
adb shell "run-as $PKG mkdir -p $DEST_SUBDIR"
for f in "${FILES[@]}"; do push "$f" "$DEST_SUBDIR"; done
push silero_vad.onnx "$MODELS_DIR"

echo "==> Done. Installed files:"
adb shell "run-as $PKG ls -la $DEST_SUBDIR $MODELS_DIR/silero_vad.onnx"
