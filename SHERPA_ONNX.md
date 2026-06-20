# Vietnamese voice typing with sherpa-onnx

This branch (`voice/sherpa-onnx`) adds an on-device ASR engine backed by
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) running a Vietnamese Zipformer
transducer, gated by Silero VAD. It plugs in behind the existing `VoiceInputController`
as a drop-in `AsrEngine`, selected for Vietnamese when its model is installed (otherwise the
build falls back to the bundled Vosk model).

## How it works

The Vietnamese model (`sherpa-onnx-zipformer-vi-30M-int8-2026-02-09`, from
[hynt/Zipformer-30M-RNNT-6000h](https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h)) is an
**offline / non-causal** Zipformer — not a true streaming model. So instead of feeding it
continuously, `SherpaAsrEngine` pushes mic PCM into Silero VAD and, each time the VAD reports
a completed speech segment, decodes that segment and emits one `onFinal`. On CPU the model
runs ~10x faster than real time, so each phrase is transcribed within a fraction of a second
of the user pausing. This is "VAD-gated near-real-time", not word-by-word streaming.

```
AudioRecord (16 kHz mono PCM) → SherpaAsrEngine.feed()
    → Silero VAD (segments speech)
        → OfflineRecognizer (zipformer-vi transducer) → onFinal(text)
```

## ⚠️ Licensing

The Vietnamese model is **CC-BY-NC-ND-4.0**: non-commercial and no-derivatives. It's fine for
research / personal builds, but **not** for a commercial release, and you may not fine-tune or
modify it under that licence. Revisit the model choice before shipping. (sherpa-onnx itself is
Apache-2.0.)

## One-time setup

Two large binaries are kept out of git and fetched on demand.

### 1. Native library (build-time)

```bash
./scripts/fetch-sherpa-onnx-aar.sh
```

Downloads the prebuilt `sherpa-onnx-<version>.aar` into `app/libs/`. The AAR bundles the
`com.k2fsa.sherpa.onnx` Kotlin API and the native libs (`sherpa-onnx-jni`, `onnxruntime`) for
`arm64-v8a` and `x86_64`. Keep the version in sync between the script and `sherpaOnnxVersion`
in `app/build.gradle.kts`.

Build & install the debug app as usual:

```bash
./gradlew installDebug
```

### 2. ASR model (run-time, on device)

With the debug app installed and one device/emulator connected:

```bash
./scripts/fetch-sherpa-vi-model.sh
```

Downloads the model `.tar.bz2` + `silero_vad.onnx`, and `adb run-as` copies these five files
into the app's private storage at `files/models/sherpa-vi/`:

```
encoder.int8.onnx   decoder.onnx   joiner.int8.onnx   tokens.txt   silero_vad.onnx
```

`SherpaAsrEngine.isAvailable` checks for exactly these files; once present, Vietnamese voice
input uses sherpa-onnx automatically (restart the keyboard if it was already running).

## Build & test

```bash
./gradlew test              # runs SherpaAudioTest (pure JVM, no native libs needed)
./gradlew assembleDebug     # requires app/libs/sherpa-onnx-*.aar present
```

Note: compiling the app requires the AAR from step 1 (the engine imports
`com.k2fsa.sherpa.onnx.*`). Run `fetch-sherpa-onnx-aar.sh` before building.

## Files

| File | Purpose |
| --- | --- |
| `voice/SherpaAsrEngine.kt` | `AsrEngine` impl: OfflineRecognizer + Silero VAD |
| `voice/SherpaAudio.kt` | Pure PCM→float helper (unit-tested) |
| `voice/ModelManager.kt` | `sherpaViDir()` — model location |
| `ime/SimpleTypeIME.kt` | `engineFor()` selects sherpa for Vietnamese |
| `scripts/fetch-sherpa-onnx-aar.sh` | Fetch the native AAR |
| `scripts/fetch-sherpa-vi-model.sh` | Fetch + push the model to the device |

## Known limitations / next steps

- **Not word-by-word streaming.** Partials aren't emitted mid-utterance; text appears per
  phrase on VAD endpoint. A truly streaming experience needs a causal/cache-aware model.
- **Decoding runs on the audio thread.** Segments are short so this is fine for a POC, but a
  dedicated decode thread would avoid any chance of dropping mic frames on long segments.
- **Engine is cached per language for the process.** If you install the model after first
  using voice input, restart the keyboard so `engineFor` re-evaluates `isAvailable`.
