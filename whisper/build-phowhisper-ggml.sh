#!/usr/bin/env bash
# Convert vinai/PhoWhisper-base (Hugging Face) into a whisper.cpp ggml model for SimpleType.
#
# PhoWhisper is OpenAI Whisper-base fine-tuned on Vietnamese (BSD-3). whisper.cpp can't read the
# PyTorch weights directly, so we convert them to ggml with the converter that ships in the
# submodule (models/convert-h5-to-ggml.py), then optionally quantize to shrink the file.
#
# Requirements: python3 with `torch`, `transformers`, `numpy`; git; ~1 GB free disk.
#
#   pip install torch transformers numpy
#   ./whisper/build-phowhisper-ggml.sh
#
# Output: whisper/out/ggml-model.bin   (push it to the device — see whisper/README.md)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/whisper/.work"
OUT="$ROOT/whisper/out"
WHISPER_CPP="$ROOT/app/src/main/cpp/whisper.cpp"
CONVERTER="$WHISPER_CPP/models/convert-h5-to-ggml.py"
QUANTIZE="${QUANTIZE:-q5_1}"   # set QUANTIZE=none to keep f16

mkdir -p "$WORK" "$OUT"

if [ ! -f "$CONVERTER" ]; then
  echo "whisper.cpp submodule missing. Run: git submodule update --init --recursive" >&2
  exit 1
fi

# 1. OpenAI whisper repo — the converter reads its mel filters / assets.
if [ ! -d "$WORK/whisper" ]; then
  git clone --depth 1 https://github.com/openai/whisper "$WORK/whisper"
fi

# 2. PhoWhisper-base weights (HF transformers format).
if [ ! -d "$WORK/PhoWhisper-base" ]; then
  git lfs install || true
  git clone https://huggingface.co/vinai/PhoWhisper-base "$WORK/PhoWhisper-base"
fi

# 3. Convert HF -> ggml (writes ggml-model.bin into the output dir).
python3 "$CONVERTER" "$WORK/PhoWhisper-base" "$WORK/whisper" "$OUT"

# 4. Optional quantization (needs the whisper.cpp quantize tool built for the host).
if [ "$QUANTIZE" != "none" ]; then
  QBIN="$WHISPER_CPP/build/bin/quantize"
  if [ ! -x "$QBIN" ]; then
    echo ">> Building host quantize tool..."
    cmake -S "$WHISPER_CPP" -B "$WHISPER_CPP/build" -DWHISPER_BUILD_EXAMPLES=ON >/dev/null
    cmake --build "$WHISPER_CPP/build" --target quantize -j >/dev/null
  fi
  if [ -x "$QBIN" ]; then
    "$QBIN" "$OUT/ggml-model.bin" "$OUT/ggml-model-$QUANTIZE.bin" "$QUANTIZE"
    mv "$OUT/ggml-model-$QUANTIZE.bin" "$OUT/ggml-model.bin"
    echo ">> Quantized to $QUANTIZE."
  else
    echo ">> Could not build quantize tool; leaving f16 model." >&2
  fi
fi

echo
echo "Done: $OUT/ggml-model.bin"
echo "Next: push it to the device — see whisper/README.md"
