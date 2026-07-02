# SimpleType Project Context

SimpleType is an Android Input Method Editor (IME) focused on on-device voice typing for English and Vietnamese.

## Tech Stack
- **Language:** Kotlin (primary), Java, C++ (JNI for whisper.cpp)
- **UI:** Custom View-based keyboard (deprecated `KeyboardView` is NOT used)
- **Concurrency:** Coroutines for ASR and background tasks
- **ASR:** Whisper.cpp (via JNI), Vosk (current fallback/lightweight engine)
- **VAD:** Silero VAD
- **Input Logic:** Custom Telex engine for Vietnamese input

## Project Structure
- `dev.phucngu.simpletype.ime`: Main `InputMethodService` (`SimpleTypeIME`) and keyboard UI components.
- `dev.phucngu.simpletype.text`: Text processing logic, including `TelexEngine`.
- `dev.phucngu.simpletype.gesture`: Glide (swipe-to-type) decoder, dictionary, and commit rules. Pure Kotlin, JVM-unit-tested. See `docs/glide-typing.md` for the architecture, dictionary format, and how to add words.
- `dev.phucngu.simpletype.voice`: Voice pipeline management (`VoiceInputController`), ASR engine interfaces, and command handling.
- `dev.phucngu.simpletype.ui`: Supporting activities and UI utilities.

## Coding Standards
- **Kotlin:** Preferred for all new code. Use idiomatic Kotlin (null safety, extension functions).
- **Separation of Concerns:** Keep `SimpleTypeIME` as a thin orchestrator. Delegate specific logic to dedicated engines/handlers.
- **InputConnection:** Handle text insertion carefully. Use `setComposingText` for active edits (like Telex or partial ASR) and `commitText` for final results.
- **Resources:** All user-facing strings must be in `res/values/strings.xml`.
- **Error Handling:** Use `AsrListener` for communicating voice-related errors to the UI.
- **Test Driven Development** For bug fix, or changes in the engine, always do TDD style. Write a test first to see if the current engine fails it, then fix it. You don't need TDD on big feature.
- **Checkpoint along the way** When implementing big changes, commit at important checkpoints to make it easy to revert back if things go wrong, also to track progress.
- **Build debug APK when feature/bugfix is done** Always build the debug APK for the developer to install it remotely

## Build & Test Commands
- **Build APK:** `./gradlew assembleDebug`
- **Install:** `./gradlew installDebug`
- **Run Unit Tests:** `./gradlew test`
- **Run Lint:** `./gradlew lint`
- **Clean Project:** `./gradlew clean`
-

## Key Classes
- `SimpleTypeIME`: Entry point for the keyboard service.
- `LatinKeyboardView`: Custom view for rendering the keyboard layout.
- `TelexEngine`: Logic for Vietnamese Telex vowel/tone composition.
- `VoiceInputController`: Manages `AudioRecord` and the ASR lifecycle.
- `VoiceCommandHandler`: Executes voice commands like "delete that" or "undo".
