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
        → OfflineRecognizer (zipformer-vi transducer) → formatUtterance() → onFinal(text)
```

The Zipformer emits **uppercase, punctuation-free** words. `SherpaAsrEngine.formatUtterance()`
lowercases (Unicode-aware, so Vietnamese diacritics map correctly), capitalizes the first
letter, and appends a terminal period — a heuristic that works because each VAD segment is a
pause-delimited phrase ≈ one sentence. There is no on-device Vietnamese punctuation-restoration
model (sherpa-onnx only ships English and Chinese+English ones), so commas / `?` / `!` are not
produced.

## ⚠️ Version pin — why sherpa-onnx 1.10.46

`app/build.gradle.kts` pins `sherpaOnnxVersion = "1.10.46"` (onnxruntime **1.17.1**) **on
purpose — do not bump it casually.** Newer sherpa-onnx (1.11+) bundles an onnxruntime built
with KleidiAI, whose **SME2** matmul kernels (`smstart` / `ptrue pn.b` / multi-vector `ld1w`)
**SIGILL** on Snapdragon 8 Elite Gen 5 (SM8850): that CPU advertises `sme` but not `sme2`, yet
onnxruntime dispatches the SME2 path → illegal instruction on the first VAD call. 1.10.46's
onnxruntime predates KleidiAI and uses the NEON path. The vi Zipformer transducer loads and
decodes correctly on 1.17.1.

Consequence: this pin trades away newer-opset support and SME acceleration. If a future device
or model needs a newer onnxruntime, the SME2 crash must be solved another way (e.g. an
onnxruntime built **without** `--use_kleidiai`), not by bumping to a stock prebuilt AAR.

### Silero VAD must be v5

sherpa-onnx 1.10.46 accepts only Silero **v4** (4-in/3-out) or **v5** (3-in/2-out) VAD models.
The `silero_vad.onnx` published in the k2-fsa `asr-models` release is a 3-in/3-out variant that
1.10.46 rejects with `Unsupported silero vad model` → a silent `exit(-1)` (the keyboard just
vanishes, no crash log). Use the **v5** model from
[snakers4/silero-vad](https://github.com/snakers4/silero-vad) — `fetch-sherpa-vi-model.sh`
already points there.

## ⚠️ Licensing

The Vietnamese model is **CC-BY-NC-ND-4.0**: non-commercial and no-derivatives. It's fine for
research / personal builds, but **not** for a commercial release, and you may not fine-tune or
modify it under that licence. Revisit the model choice before shipping. (sherpa-onnx itself is
Apache-2.0.)

## Setup

The native AAR and the model are both large and kept out of git. There are two ways to get the
model onto a device.

### 1. Native library (build-time, required either way)

```bash
./scripts/fetch-sherpa-onnx-aar.sh
```

Downloads the prebuilt `sherpa-onnx-1.10.46.aar` into `app/libs/` (gitignored). The AAR bundles
the `com.k2fsa.sherpa.onnx` Kotlin API and native libs (`sherpa-onnx-jni`, `onnxruntime`, …)
for `arm64-v8a` and `x86_64`. Keep the version in sync between the script and `sherpaOnnxVersion`
in `app/build.gradle.kts`. Compiling the app requires this AAR (the engine imports
`com.k2fsa.sherpa.onnx.*`), so run it before building.

### 2a. Model bundled in the APK (recommended for local testing)

Place the five model files under `app/src/main/assets/models/sherpa-vi/` (gitignored — the
NC-ND model must not be committed):

```
encoder.int8.onnx   decoder.onnx   joiner.int8.onnx   tokens.txt   silero_vad.onnx (v5)
```

`ModelManager.installSherpaViFromAssetsIfBundled()` copies them into the app's private storage
(`files/models/sherpa-vi/`) on first Vietnamese voice use — **no adb/device push needed**, the
phone never has to download anything. A clean CI/release build (no assets) makes this a no-op,
so the app falls back to Vosk. Build & install the debug APK and it works offline:

```bash
./gradlew installDebug
```

To re-provision after changing a bundled file, clear the stale copy so the app re-copies:
`adb shell run-as dev.phucngu.simpletype rm -rf files/models/sherpa-vi`.

### 2b. Model pushed via adb (no bundling)

With the debug app installed and one device connected:

```bash
./scripts/fetch-sherpa-vi-model.sh
```

Downloads the model `.tar.bz2` + the **v5** `silero_vad.onnx` and `adb run-as`-copies the five
files into `files/models/sherpa-vi/`. Use this if you don't want to embed the model in the APK.

`SherpaAsrEngine.isAvailable` checks for exactly these five files; once present, Vietnamese
voice input uses sherpa-onnx automatically (restart the keyboard if it was already running).

## Build & test

```bash
./gradlew test              # runs SherpaAudioTest (pure JVM, no native libs needed)
./gradlew assembleDebug     # requires app/libs/sherpa-onnx-1.10.46.aar present
```

## Files

| File | Purpose |
| --- | --- |
| `voice/SherpaAsrEngine.kt` | `AsrEngine` impl: OfflineRecognizer + Silero VAD; `formatUtterance()` casing/period |
| `voice/SherpaAudio.kt` | Pure PCM→float helper (unit-tested) |
| `voice/ModelManager.kt` | `sherpaViDir()` + `installSherpaViFromAssetsIfBundled()` |
| `ime/SimpleTypeIME.kt` | `engineFor()` selects sherpa for Vietnamese |
| `scripts/fetch-sherpa-onnx-aar.sh` | Fetch the native AAR (1.10.46) |
| `scripts/fetch-sherpa-vi-model.sh` | Fetch + adb-push the model (+ v5 VAD) to the device |

## Known limitations / next steps

- **Not word-by-word streaming.** Partials aren't emitted mid-utterance; text appears per
  phrase on VAD endpoint. A truly streaming experience needs a causal/cache-aware model.
- **Heuristic punctuation only.** Sentence-case + a period per VAD segment; no commas / `?` /
  `!`, and a mid-thought pause produces a period. No Vietnamese punctuation model exists
  on-device.
- **onnxruntime pinned to 1.17.1** to dodge the SME2 SIGILL — see the version-pin note above.
- **Decoding runs on the audio thread.** Segments are short so this is fine for a POC, but a
  dedicated decode thread would avoid any chance of dropping mic frames on long segments.
- **Engine is cached per language for the process.** If you install the model after first
  using voice input, restart the keyboard so `engineFor` re-evaluates `isAvailable`.

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
