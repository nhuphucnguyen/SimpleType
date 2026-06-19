# PhoWhisper (Vietnamese) voice typing — whisper.cpp integration

SimpleType's Vietnamese voice typing runs **PhoWhisper-base** (VinAI's Vietnamese fine-tune of
OpenAI Whisper, BSD-3) on-device through **whisper.cpp**. English voice typing keeps the existing
Vosk engine.

This native path is **opt-in** so the default build needs no NDK. Until the steps below are done,
`WhisperAsrEngine.isAvailable` is false and Vietnamese voice **falls back to Vosk** automatically —
nothing breaks.

## Pieces in the repo

| Path | What it is |
| --- | --- |
| `app/src/main/cpp/whisper.cpp/` | whisper.cpp (git submodule) |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge (`initContext` / `transcribe` / `freeContext`) |
| `app/src/main/cpp/CMakeLists.txt` | builds `libwhisper_jni.so` |
| `voice/WhisperLib.kt` | Kotlin `external` declarations + safe `loadLibrary` |
| `voice/WhisperAsrEngine.kt` | utterance-based `AsrEngine` (accumulate PCM → transcribe on stop) |
| `whisper/build-phowhisper-ggml.sh` | HF → ggml conversion helper |

## 1. Get the submodule

```bash
git submodule update --init --recursive
```

## 2. Convert PhoWhisper-base → ggml

```bash
pip install torch transformers numpy
./whisper/build-phowhisper-ggml.sh          # -> whisper/out/ggml-model.bin
# keep f16 instead of quantizing: QUANTIZE=none ./whisper/build-phowhisper-ggml.sh
```

f16 is ~145 MB; `q5_1` (default) is ~60 MB.

## 3. Install the NDK and build with `-PwithWhisper`

The native build needs the Android NDK + CMake. Install them in Android Studio
(SDK Manager → SDK Tools → NDK, CMake), then:

```bash
./gradlew assembleDebug -PwithWhisper
# different NDK installed? -PwithWhisper -PndkVersion=<your version>
```

Without `-PwithWhisper` the build is unchanged (no NDK required).

## 4. Put the model on the device

The model loads from the app's private storage at
`files/models/whisper-vi/ggml-model.bin`. For a debug build:

```bash
adb push whisper/out/ggml-model.bin /data/local/tmp/ggml-model.bin
adb shell run-as dev.phucngu.simpletype mkdir -p files/models/whisper-vi
adb shell run-as dev.phucngu.simpletype \
  cp /data/local/tmp/ggml-model.bin files/models/whisper-vi/ggml-model.bin
```

Restart the keyboard. With the lib built and the model present, Vietnamese voice typing now uses
PhoWhisper; otherwise it stays on Vosk.

## TODO — runtime download

`ModelManager.WHISPER_VI_MODEL_URL` is a placeholder. Once the converted model is hosted, wire a
download (mirroring the Vosk flow) and surface it on the Vietnamese voice-model chip in Settings,
so users don't need adb.

## Notes

- Whisper is utterance-based: audio is buffered while speaking and decoded once on stop (one
  `onFinal`), unlike Vosk's streaming partials. The capture pipeline already calls
  `endOfUtterance()` on stop, so no controller changes were needed.
- `whisper/.work/` and `whisper/out/` are build artifacts and are git-ignored.
