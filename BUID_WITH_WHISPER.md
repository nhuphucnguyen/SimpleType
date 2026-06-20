# Building SimpleType with Whisper (PhoWhisper Vietnamese voice typing)

SimpleType's Vietnamese voice typing runs **PhoWhisper-base** (VinAI's Vietnamese fine-tune of
OpenAI Whisper) on-device via **whisper.cpp** (JNI + ggml CPU backend). English voice typing stays
on Vosk.

This native path is **opt-in**. The default build needs no NDK: `WhisperAsrEngine.isAvailable` is
false when `libwhisper_jni.so` (or the model) is absent, and Vietnamese voice falls back to Vosk
automatically. Everything below only matters when you build with **`-PwithWhisper`**.

> **The one thing you must not break:** the native code has to be compiled in **Release**. A Debug
> ggml build SIGSEGVs on-device. This is wired into `build.gradle.kts` so a normal `-PwithWhisper`
> build is already correct — see [The Release build requirement](#the-release-build-requirement).

---

## 1. Pieces in the repo

| Path | What it is |
| --- | --- |
| `app/src/main/cpp/whisper.cpp/` | whisper.cpp (git submodule, pinned to a release tag) |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge: `initContext` / `transcribe` / `freeContext` |
| `app/src/main/cpp/CMakeLists.txt` | builds `libwhisper_jni.so` (+ ggml from the submodule) |
| `app/build.gradle.kts` | wires the native build behind `-PwithWhisper`; **forces Release** |
| `voice/WhisperLib.kt` | Kotlin `external` declarations + safe `loadLibrary` |
| `voice/WhisperAsrEngine.kt` | utterance-based `AsrEngine` (buffer PCM → transcribe on stop) |
| `whisper/build-phowhisper-ggml.sh` | HF → ggml model conversion helper |
| `whisper/README.md` | original quickstart (this file supersedes/expands it) |

Runtime flow: capture thread feeds 16 kHz mono PCM to `WhisperAsrEngine`; on stop it flattens the
buffer to float and calls `WhisperLib.transcribe`, which runs `whisper_full` and returns one
`onFinal`. The model is loaded once per session and reused.

---

## 2. Prerequisites

- **Android NDK** `27.3.13750724` (the default; override with `-PndkVersion=…`).
- **CMake** `3.22.1` (bundled with the Android SDK: `SDK Manager → SDK Tools → CMake`).
- For **model conversion only**: Python 3 with `torch`, `transformers`, `numpy` (a venv is fine —
  see below). Not needed to build or run the app once `ggml-model.bin` exists.

Install NDK + CMake via Android Studio's SDK Manager, or accept that Gradle will prompt for them.

---

## 3. One-time setup

### 3a. Get the submodule

```bash
git submodule update --init --recursive
```

### 3b. Convert PhoWhisper-base → ggml

```bash
# isolated Python env (kept out of git via whisper/.venv in .gitignore)
python3 -m venv whisper/.venv
whisper/.venv/bin/pip install torch transformers numpy

# converter calls `python3`; put the venv first on PATH, and the SDK cmake on PATH for the
# quantize step
export PATH="$PWD/whisper/.venv/bin:$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
./whisper/build-phowhisper-ggml.sh          # -> whisper/out/ggml-model.bin  (q5_1, ~57 MB)
# keep f16 (~141 MB) instead: QUANTIZE=none ./whisper/build-phowhisper-ggml.sh
```

> **Known gotcha — the quantize step.** `build-phowhisper-ggml.sh` builds a host tool with
> `--target quantize`, but recent whisper.cpp renamed it to **`whisper-quantize`**, so the script's
> quantize step fails and leaves you with the f16 model. If that happens, quantize manually:
>
> ```bash
> cd app/src/main/cpp/whisper.cpp
> cmake --build build --target whisper-quantize -j
> ./build/bin/whisper-quantize ../../../../whisper/out/ggml-model.bin /tmp/q.bin q5_1
> mv /tmp/q.bin ../../../../whisper/out/ggml-model.bin
> ```
>
> The f16 model also works fine in the app — it's just bigger.

The model weights are a Git LFS object. If `whisper/.work/PhoWhisper-base/pytorch_model.bin` comes
out as a ~134-byte text pointer (no `git-lfs` installed), download it directly instead:
`curl -L -o whisper/.work/PhoWhisper-base/pytorch_model.bin https://huggingface.co/vinai/PhoWhisper-base/resolve/main/pytorch_model.bin`

### 3c. Put the model on the device

The app loads from its private storage at `files/models/whisper-vi/ggml-model.bin`:

```bash
adb push whisper/out/ggml-model.bin /data/local/tmp/ggml-model.bin
adb shell run-as dev.phucngu.simpletype mkdir -p files/models/whisper-vi
adb shell run-as dev.phucngu.simpletype \
  cp /data/local/tmp/ggml-model.bin files/models/whisper-vi/ggml-model.bin
adb shell rm /data/local/tmp/ggml-model.bin
```

Restart the keyboard. With the lib built and the model present, Vietnamese voice uses PhoWhisper;
otherwise it stays on Vosk.

> There is **no in-app download yet** — `ModelManager.WHISPER_VI_MODEL_URL` is a placeholder, so the
> model must be adb-pushed. Wiring a runtime download (mirroring the Vosk flow) is the main open TODO.

---

## 4. Building the app

```bash
./gradlew assembleDebug  -PwithWhisper                          # build the APK
./gradlew installDebug   -PwithWhisper                          # build + install
# different NDK installed?
./gradlew installDebug   -PwithWhisper -PndkVersion=<version>
```

Without `-PwithWhisper` the build is unchanged and needs no NDK. Gradle compiles the changed
native sources incrementally, in Release, with no extra flags from you.

---

## The Release build requirement

**This is the load-bearing detail of the whole integration.**

### Symptom
With a Debug native build, the IME process dies with a native **SIGSEGV** every time it tries to
transcribe — the keyboard hangs for a moment, then disappears, with no text. The tombstone points
into ggml:

```
#00 __memcpy_aarch64_simd
#01 ggml_compute_forward_mul_mat   (or ggml_vec_dot_f16 / ggml_compute_forward_flash_attn_ext)
...
#   whisper_full_with_state → whisper_full → Java_..._transcribe
```

(near-null fault address = ggml reading a work buffer that the unoptimized build mis-sized).

### Cause
AGP compiles `externalNativeBuild` as **`CMAKE_BUILD_TYPE=Debug`** for the debug app variant.
ggml's `-O0` arm64 CPU kernels fault on-device. Release (`-O3 -DNDEBUG`) is the configuration
whisper.cpp is actually tested with — and it's much faster too.

### Fix (already in the repo)
`app/build.gradle.kts` forces Release for the native build, even for the debug app variant. It lives
in **`defaultConfig.externalNativeBuild.cmake`**:

```kotlin
defaultConfig {
    // ...
    if (project.hasProperty("withWhisper")) {
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }
}
```

> **Why not the other `cmake {}` block?** The `android.externalNativeBuild.cmake {}` block (further
> down) only declares `path`/`version` — it has **no `arguments` property**. Putting `arguments`
> there fails to compile with *"Unresolved reference 'arguments'"*. CMake arguments belong in
> `defaultConfig` (or a buildType), as above.

### Verify it's actually Release
After a build, the native output dir is keyed by build type — you want `Release`, not `Debug`:

```bash
grep CMAKE_BUILD_TYPE: app/.cxx/Release/*/arm64-v8a/CMakeCache.txt   # -> Release
# if you instead see app/.cxx/Debug/...  the override didn't apply and it will crash.
```

---

## 5. Updating whisper.cpp (the lifecycle)

Gradle builds the native code but **never pulls upstream** — you move the submodule yourself, then
rebuild. The Release fix lives in `build.gradle.kts`, so it carries over to every future version
automatically.

```bash
# 1. Move the submodule to a new upstream RELEASE TAG (avoid untagged dev commits).
git -C app/src/main/cpp/whisper.cpp fetch --tags origin
git -C app/src/main/cpp/whisper.cpp checkout v1.9.2          # the version you want
git -C app/src/main/cpp/whisper.cpp submodule update --init --recursive
git add app/src/main/cpp/whisper.cpp                         # record the new pointer

# 2. Clear the native build cache (a version jump can change CMake structure / cache vars that
#    the incremental build misses — a stale mismatch is exactly what produces confusing crashes).
rm -rf app/.cxx app/build/intermediates/cxx

# 3. Rebuild.
./gradlew installDebug -PwithWhisper -PndkVersion=27.3.13750724
```

### After the bump, sanity-check:
- **Build type** — `grep CMAKE_BUILD_TYPE: app/.cxx/Release/*/arm64-v8a/CMakeCache.txt` → `Release`.
- **JNI API drift** — `whisper_jni.cpp` uses `whisper_context_default_params`,
  `whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_default_params`, and the
  segment getters. If upstream changes any signature, the build fails to compile — fix the JNI.
- **Model compatibility** — a new whisper.cpp normally still reads the existing `ggml-model.bin`.
  Only if the ggml format bumps do you re-run the conversion (§3b) and re-push (§3c). An app
  rebuild never touches the on-device model.
- **Tooling renames** — example target names drift (e.g. `quantize` → `whisper-quantize`); only
  relevant to the conversion script, not the app build.

---

## 6. Troubleshooting

**Keyboard vanishes / no text on Vietnamese voice → native SIGSEGV in ggml.**
First suspect: the native build is Debug. Confirm with the `CMakeCache.txt` check above. This is the
issue the Release flag fixes.

**Isolate "device/whisper.cpp bug" vs "our app".** Run whisper.cpp's own CLI directly on the device
— no IME, JNI, or audio thread involved. This is how the Debug-vs-Release cause was pinned down:

```bash
cd app/src/main/cpp/whisper.cpp
NDK="$HOME/Library/Android/sdk/ndk/27.3.13750724"
export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=OFF \
  -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF
cmake --build build-android --target whisper-cli -j
adb push build-android/bin/whisper-cli /data/local/tmp/whisper-cli
adb push ../../../../whisper/out/ggml-model.bin /data/local/tmp/ggml-model.bin
adb push samples/jfk.wav /data/local/tmp/jfk.wav
adb shell chmod 755 /data/local/tmp/whisper-cli
adb shell "cd /data/local/tmp && ./whisper-cli -m ggml-model.bin -f jfk.wav -t 1 -l vi"
```
A Release CLI transcribes without crashing; a `-DCMAKE_BUILD_TYPE=Debug` CLI reproduces the crash.
(`GGML_OPENMP=OFF` keeps the CLI self-contained — otherwise it needs `libomp.so` on the device.)

**Gradle "Script compilation error" / stale config cache.** If a build script change doesn't take or
you get a phantom error on a line you already fixed, the configuration cache is stale:
```bash
rm -rf .gradle/configuration-cache
./gradlew installDebug -PwithWhisper --no-configuration-cache
```

**Watch the on-device logs while testing:**
```bash
adb logcat -c && adb logcat | grep -E "WhisperJNI|WhisperAsrEngine|libwhisper|signal 11|ggml_compute"
```

**Flash attention (CPU wildcard).** whisper.cpp defaults `flash_attn = true`. It's a GPU-oriented
path with no benefit on the CPU backend we run, and its CPU kernel was a repeated crash site during
debugging. If you ever see crashes specifically in `ggml_compute_forward_flash_attn_ext`, disable it
in `whisper_jni.cpp` after `cparams.use_gpu = false;`:
```cpp
cparams.flash_attn = false;
```

---

## 7. Quick reference

| Task | Command |
| --- | --- |
| Build APK (whisper) | `./gradlew assembleDebug -PwithWhisper` |
| Build + install | `./gradlew installDebug -PwithWhisper` |
| Custom NDK | add `-PndkVersion=<version>` |
| Convert model | `./whisper/build-phowhisper-ggml.sh` → `whisper/out/ggml-model.bin` |
| Confirm Release build | `grep CMAKE_BUILD_TYPE: app/.cxx/Release/*/arm64-v8a/CMakeCache.txt` |
| Bump whisper.cpp | `git -C app/src/main/cpp/whisper.cpp checkout <tag>` then `rm -rf app/.cxx` then rebuild |
| Default build (no whisper) | `./gradlew assembleDebug` (NDK-free, falls back to Vosk) |
