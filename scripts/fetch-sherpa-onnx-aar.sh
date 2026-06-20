#!/usr/bin/env bash
#
# Downloads the prebuilt sherpa-onnx Android AAR into app/libs/.
#
# The AAR bundles the com.k2fsa.sherpa.onnx Kotlin API plus the native libraries
# (libsherpa-onnx-jni.so, libonnxruntime.so) for arm64-v8a and x86_64 — so no NDK
# build is required. The .aar is large (~native libs) and is NOT committed; run this
# once after cloning (and whenever SHERPA_ONNX_VERSION changes).
#
# Source: https://github.com/k2-fsa/sherpa-onnx/releases
#
set -euo pipefail

# Keep in sync with `sherpaOnnxVersion` in app/build.gradle.kts.
SHERPA_ONNX_VERSION="${SHERPA_ONNX_VERSION:-1.12.34}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="$SCRIPT_DIR/../app/libs"
AAR_NAME="sherpa-onnx-${SHERPA_ONNX_VERSION}.aar"
DEST="$LIBS_DIR/$AAR_NAME"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_ONNX_VERSION}/${AAR_NAME}"

mkdir -p "$LIBS_DIR"

if [[ -f "$DEST" ]]; then
  echo "✓ $AAR_NAME already present in app/libs/"
  exit 0
fi

echo "Downloading $AAR_NAME ..."
echo "  $URL"
curl -fL --progress-bar "$URL" -o "$DEST.tmp"
mv "$DEST.tmp" "$DEST"
echo "✓ Saved to app/libs/$AAR_NAME"
