# SimpleType — Product Specification

**Android keyboard with on-device voice typing and voice commands (English + Vietnamese)**

Version 0.1 · Draft · June 10, 2026

---

## 1. Overview

SimpleType is a custom Android keyboard (IME) with advanced voice typing as its core differentiator. Speech recognition runs fully on-device — no audio leaves the phone — and supports English and Vietnamese, including voice commands for hands-free text editing ("delete that word", "new line", "undo").

### Goals

- A reliable, fast typing keyboard for Android (touch + voice)
- On-device, offline-capable speech-to-text for English and Vietnamese
- Voice-command editing during dictation
- Privacy-first: no keystrokes or audio sent to servers

### Non-goals (v1)

- iOS support
- Cloud-based recognition
- Languages beyond English and Vietnamese
- Swipe/gesture typing, GIF/sticker features

## 2. Target users

- Vietnamese-speaking users who switch between Vietnamese and English (code-switching is common)
- Users who prefer dictation over typing (accessibility, long-form messaging)
- Privacy-conscious users who reject cloud keyboards

## 3. Core features

### 3.1 Keyboard (IME)

| Feature | Detail |
|---|---|
| Layouts | QWERTY (EN), Telex/VNI input for Vietnamese |
| Field awareness | Adapts to input type (email, number, URL, password) via `EditorInfo.inputType` |
| Shift states | Lowercase, shift, caps-lock; symbols layer |
| Language switching | Subtype switching (EN ⇄ VI) via globe key |
| Password fields | Suggestions and voice input disabled |

### 3.2 Voice typing

| Feature | Detail |
|---|---|
| Activation | Mic key on keyboard; visual listening state (pulse/waveform) |
| Live transcription | Streaming partials shown as composing (underlined) text |
| Finalization | Auto-endpoint via VAD silence detection, or tap-to-stop |
| Punctuation | Automatic punctuation and capitalization |
| Offline | Fully on-device; works in airplane mode |
| Audio focus | Pauses media playback while dictating (`AudioManager`) |

### 3.3 Voice commands

Commands are detected on finalized utterances — an utterance matches a command only if the **entire utterance** is the command (pause-delimited via VAD).

**v1 command grammar (EN + VI equivalents):**

- "delete that" / "delete last word"
- "delete last sentence"
- "new line" / "new paragraph"
- "undo"
- "select all"
- "stop listening"
- "type \<words\>" — escape hatch: commits the literal words

**Disambiguation rules:**

1. Exact/normalized full-utterance match against grammar (lowercase, punctuation stripped)
2. Context check — e.g. "delete last word" requires non-empty field, else commit as text
3. Confidence threshold — low-confidence command matches commit as text
4. Every command is undoable (undo buffer) so false positives are cheap

**Later (v2+):** paraphrase handling ("scratch that", "get rid of it") via synonym grammar expansion or embedding similarity — no model training required.

## 4. Architecture

```
┌────────────────────────────────────────────────┐
│ InputMethodService (SimpleTypeIME)             │
│  ├─ Keyboard view (custom; KeyboardView is     │
│  │   deprecated) — touch input                 │
│  └─ Voice pipeline                             │
│      AudioRecord (16 kHz mono PCM)             │
│        → Silero VAD (speech start/end)         │
│        → ASR engine (whisper.cpp, JNI)         │
│        → Command matcher (grammar, plain code) │
│        → InputConnection (commit/delete/etc.)  │
└────────────────────────────────────────────────┘
```

- **IME plumbing:** `InputMethodService`, manifest service with `BIND_INPUT_METHOD` permission, `res/xml/method.xml` with EN/VI subtypes
- **Text output:** `InputConnection` — `setComposingText()` for partials, `commitText()` for finals, `deleteSurroundingText()` for command actions
- **Permissions:** `RECORD_AUDIO` requested at runtime via transparent activity (services can't show permission dialogs)
- **Engine abstraction:** ASR behind an interface so models can be swapped per language or upgraded

## 5. Speech models

All choices are commercially usable open-source models (verified June 2026):

| Model | License | Role |
|---|---|---|
| **PhoWhisper-small** (VinAI) | BSD 3-Clause | Primary Vietnamese engine — SOTA Vietnamese WER, trained on 844 h of accented Vietnamese |
| **Whisper-small/base** (OpenAI) | MIT | Primary English engine; also better than PhoWhisper on EN/VI code-switched speech |
| **Qwen3-ASR-0.6B** (Alibaba) | Apache 2.0 | Candidate single model for both languages — evaluate latency on mid-range devices |
| **Vosk + VI/EN models** | Apache 2.0 | Lightweight streaming fallback (~50 MB) if Whisper-class latency is too slow |
| Silero VAD | MIT | Voice activity detection |

**Excluded:** Moonshine (Vietnamese model is non-commercial Community License); Cohere transcribe model (license unverified, likely non-commercial).

**Runtime:** whisper.cpp via JNI (one runtime for both Whisper and PhoWhisper; model file swapped by active language subtype).

**No training or fine-tuning required.** Pretrained models handle transcription; command detection is text post-processing (plain code).

## 6. Privacy & security

- All recognition on-device; no audio or text transmitted
- No keystroke logging or analytics on typed content
- Voice input and learning disabled in password fields
- Models bundled in APK or downloaded once as on-demand packs (Play Asset Delivery) to manage APK size

## 7. Performance targets

| Metric | Target |
|---|---|
| Key press → character latency | < 50 ms |
| Dictation partial-result latency | < 1 s behind speech |
| End-of-utterance → final text | < 1.5 s |
| Model memory footprint (active) | < 600 MB on mid-range device |
| APK base size (without models) | < 30 MB |

## 8. Milestones

1. **M1 — Typing keyboard:** IME service, QWERTY + Telex, subtype switching, settings screen
2. **M2 — Voice typing (EN):** AudioRecord → VAD → whisper.cpp pipeline, composing-text streaming, mic UX
3. **M3 — Vietnamese:** PhoWhisper-small integration, language-aware engine switching
4. **M4 — Voice commands:** grammar matcher, undo buffer, EN + VI command sets, "type" escape
5. **M5 — Polish & ship:** latency tuning, battery profiling, Play Asset Delivery for models, Play Store listing

## 9. Open questions

- Qwen3-ASR-0.6B vs dual-Whisper: single-model simplicity worth the latency risk?
- Whisper streaming strategy (chunked decoding) vs accepting utterance-level latency
- Auto language detection vs manual subtype switching for dictation
- Minimum supported device spec (RAM floor for model inference)

## 10. References

- [Android: Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [PhoWhisper paper](https://arxiv.org/abs/2406.02555) · [GitHub](https://github.com/VinAIResearch/PhoWhisper)
- [Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR)
- [Vosk models](https://alphacephei.com/vosk/models) · [Vosk API](https://github.com/alphacep/vosk-api)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- Code-switching benchmark: [ViMedCSS](https://arxiv.org/pdf/2602.12911)
