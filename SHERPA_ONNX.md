# Voice typing with sherpa-onnx

This branch (`voice/sherpa-onnx`) runs all voice typing on
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): one offline transducer per language,
gated by Silero VAD, behind the existing `VoiceInputController` as an `AsrEngine`. It replaces
Vosk, which has been removed.

| Language | Model (`SherpaModel`) | Download | Output | Licence |
| --- | --- | --- | --- | --- |
| Vietnamese | `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` ([hynt/Zipformer-30M-RNNT-6000h](https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h)) | ~26 MB | UPPERCASE, no punctuation | CC-BY-NC-ND-4.0 |
| English | `sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8` (NVIDIA Parakeet TDT 110M) | ~108 MB | Cased + punctuated | CC-BY-4.0 |

## How it works

Both models are **offline / non-causal**, not true streaming models. So instead of feeding them
continuously, `SherpaAsrEngine` pushes mic PCM into Silero VAD and, each time the VAD reports
a completed speech segment, decodes that segment and emits one `onFinal`. On CPU the models
run many times faster than real time, so each phrase is transcribed within a fraction of a
second of the user pausing. This is "VAD-gated near-real-time", not word-by-word streaming.

```
AudioRecord (16 kHz mono PCM) → SherpaAsrEngine.feed()
    → Silero VAD (segments speech)
        → OfflineRecognizer (SherpaModel for the language) → SherpaText.format() → onFinal(text)
```

`SherpaText.format()` normalises each segment (≈ one pause-delimited sentence):

- **Vietnamese:** the Zipformer emits uppercase, punctuation-free words, so they are lowercased
  (Unicode-aware, so diacritics map correctly), the first letter capitalised and a period
  appended. There is no on-device Vietnamese punctuation-restoration model, so commas / `?` /
  `!` are not produced.
- **English:** Parakeet already emits casing and punctuation, which is kept as-is (only a
  missing first capital / terminal period is added).

## sherpa-onnx version

`app/build.gradle.kts` uses sherpa-onnx **1.13.8**. The project previously pinned 1.10.46 to
avoid an onnxruntime KleidiAI/SME2 illegal-instruction crash on Snapdragon 8 Elite Gen 5
(SM8850). That upstream issue is fixed in the newer runtime, so the project now follows 1.13.8.

### Silero VAD: use the official snakers4 model

The project uses the official Silero VAD model from
[snakers4/silero-vad](https://github.com/snakers4/silero-vad) (3-in/2-out), pinned to release
tag **v6.2.3** (`ModelManager.VAD_URL`, and `SILERO_VAD_TAG` in `fetch-sherpa-vi-model.sh`).
v5 and v6 share the same interface, so sherpa-onnx loads either without code changes
([k2-fsa/sherpa-onnx#3528](https://github.com/k2-fsa/sherpa-onnx/issues/3528)). One copy at
`files/models/silero_vad.onnx` is shared by both languages.

Don't use the `silero_vad.onnx` from the k2-fsa `asr-models` release: it's a 3-in/3-out variant
that an older pinned runtime rejected with `Unsupported silero vad model` → a silent `exit(-1)`
(the keyboard just vanished, with no crash log).

## ⚠️ Licensing

- The **Vietnamese** model is **CC-BY-NC-ND-4.0**: non-commercial and no-derivatives. It's fine
  for research / personal builds, but **not** for a commercial release, and you may not
  fine-tune or modify it under that licence. Revisit the model choice before shipping.
- The **English** Parakeet model is **CC-BY-4.0**: commercial use is fine, but a release must
  credit NVIDIA (e.g. an about/licences screen).
- sherpa-onnx and commons-compress are Apache-2.0; Silero VAD is MIT.

## Setup

### 1. Native library (build-time, required)

```bash
./scripts/fetch-sherpa-onnx-aar.sh
```

Downloads the prebuilt `sherpa-onnx-1.13.8.aar` into `app/libs/` (gitignored). The AAR bundles
the `com.k2fsa.sherpa.onnx` Kotlin API and native libs (`sherpa-onnx-jni`, `onnxruntime`, …)
for `arm64-v8a` and `x86_64`. Keep the version in sync between the script and `sherpaOnnxVersion`
in `app/build.gradle.kts`. Compiling the app requires this AAR (the engine imports
`com.k2fsa.sherpa.onnx.*`), so run it before building.

### 2. Models

**In-app (default):** open SimpleType → *Voice typing models* → *Download*. `ModelManager`
fetches the `.tar.bz2` from the k2-fsa `asr-models` release, unpacks the four model files
(`ModelArchive`, via commons-compress) into `files/models/sherpa-<lang>/`, and fetches the
pinned VAD once. Voice input picks the model up immediately — no keyboard restart needed.

**Bundled in the APK (local testing):** place the model files under
`app/src/main/assets/models/<dirName>/` and the VAD at `app/src/main/assets/models/silero_vad.onnx`
(all gitignored). `ModelManager.installFromAssetsIfBundled()` copies them into private storage
on first voice use. A clean CI/release build has no such assets, so this is a no-op.

```
assets/models/sherpa-vi/   encoder.int8.onnx  decoder.onnx       joiner.int8.onnx  tokens.txt
assets/models/sherpa-en/   encoder.int8.onnx  decoder.int8.onnx  joiner.int8.onnx  tokens.txt
assets/models/silero_vad.onnx
```

**Pushed via adb (Vietnamese, dev shortcut):** with the debug app installed and one device
connected, `./scripts/fetch-sherpa-vi-model.sh` downloads the model + VAD and
`adb run-as`-copies them into place.

To force a re-install, clear the installed copy:
`adb shell run-as dev.phucngu.simpletype rm -rf files/models`.

## Build & test

```bash
./gradlew test              # SherpaAudioTest, SherpaTextTest, ModelArchiveTest (pure JVM)
./gradlew assembleDebug     # requires app/libs/sherpa-onnx-1.13.8.aar present
```

## Files

| File | Purpose |
| --- | --- |
| `voice/SherpaModel.kt` | Per-language model spec: archive URL, file names, `modelType` |
| `voice/SherpaAsrEngine.kt` | `AsrEngine` impl: OfflineRecognizer + Silero VAD |
| `voice/SherpaText.kt` | Per-model casing / terminal period (unit-tested) |
| `voice/SherpaAudio.kt` | Pure PCM→float helper (unit-tested) |
| `voice/ModelArchive.kt` | Unpacks the `.tar.bz2` model archives (unit-tested) |
| `voice/ModelManager.kt` | Download / install models + VAD; `installFromAssetsIfBundled()` |
| `ime/SimpleTypeIME.kt` | `engineFor()` builds the sherpa engine for the active language |
| `scripts/fetch-sherpa-onnx-aar.sh` | Fetch the native AAR (1.13.8) |
| `scripts/fetch-sherpa-vi-model.sh` | Fetch + adb-push the vi model (+ Silero VAD v6.2.3) to the device |

## Known limitations / next steps

- **Not word-by-word streaming.** Partials aren't emitted mid-utterance; text appears per
  phrase on VAD endpoint. A truly streaming experience needs a causal/cache-aware model
  (e.g. `hynt/Zipformer-30M-RNNT-Streaming-6000h` for Vietnamese).
- **Heuristic Vietnamese punctuation only.** Sentence-case + a period per VAD segment; no
  commas / `?` / `!`, and a mid-thought pause produces a period.
- **Decoding runs on the audio thread.** Segments are short so this is fine for a POC, but a
  dedicated decode thread would avoid any chance of dropping mic frames on long segments.

## Debugging on Honor/Huawei devices

These devices **suppress third-party app logs in logcat**, so a crash can look invisible. Pull
crashes from DropBox instead:

```bash
adb shell dumpsys dropbox data_app_native_crash --print   # native SIGSEGV/SIGILL + backtrace
adb shell dumpsys dropbox data_app_crash --print          # Java/Kotlin exceptions
```

A clean `exit()` from native code (e.g. sherpa's `exit(-1)` on a bad model) leaves **no
tombstone and no dropbox entry** — if the process dies with nothing logged, suspect that. To
reproduce model-load failures without a device, `pip install sherpa-onnx==<version>` on a
desktop (the wheel bundles the same onnxruntime) and load the exact model files — it prints the
same fatal message.
