package dev.phucngu.simpletype.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.text.TelexEngine
import dev.phucngu.simpletype.ui.MicPermissionActivity
import dev.phucngu.simpletype.voice.AsrEngine
import dev.phucngu.simpletype.voice.AsrListener
import dev.phucngu.simpletype.voice.CommandMatcher
import dev.phucngu.simpletype.voice.ModelManager
import dev.phucngu.simpletype.voice.VoiceCommandHandler
import dev.phucngu.simpletype.voice.VoiceInputController
import dev.phucngu.simpletype.voice.VoiceLanguage
import dev.phucngu.simpletype.voice.VoskAsrEngine
import dev.phucngu.simpletype.voice.WhisperAsrEngine
import dev.phucngu.simpletype.voice.WhisperLib

open class SimpleTypeIME : InputMethodService(),
    LatinKeyboardListener,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store by lazy { ViewModelStore() }
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var metrics = KeyboardMetrics.DEFAULT

    // Compose states representing the keyboard UI state
    private var composeKeyboard by mutableStateOf(KeyboardLayouts.qwerty())
    private var composeMetrics by mutableStateOf(KeyboardMetrics.DEFAULT)
    private var composeSpaceLabel by mutableStateOf("")
    private var composeShifted by mutableStateOf(false)
    private var composeCapsLock by mutableStateOf(false)
    private var composeVoiceStatus by mutableStateOf<String?>(null)
    private var composeMicActive by mutableStateOf(false)
    private var composeOptionsExpanded by mutableStateOf(false)
    private var composeClipboardItems by mutableStateOf<List<ClipboardItem>>(emptyList())
    private var composeClipboardVisible by mutableStateOf(false)

    private lateinit var clipboardHistory: ClipboardHistoryManager

    private val telex = TelexEngine()
    private var language = VoiceLanguage.ENGLISH

    private enum class Layout { ALPHA, SYMBOLS, SYMBOLS_ALT }
    private var layout = Layout.ALPHA

    private var shifted = false
    private var capsLock = false
    private var shiftHeld = false
    private var passwordField = false
    private var directCommit = false

    private val voice: VoiceInputController by lazy {
        VoiceInputController(this, voiceListener)
    }
    private val modelManager by lazy { ModelManager(this) }
    private val engines = mutableMapOf<VoiceLanguage, AsrEngine>()

    private fun engineFor(lang: VoiceLanguage): AsrEngine = engines.getOrPut(lang) {
        // Vietnamese uses PhoWhisper (whisper.cpp) when the native lib and ggml model are both
        // present; otherwise — and always for English — fall back to the Vosk streaming engine.
        if (lang == VoiceLanguage.VIETNAMESE &&
            WhisperLib.available &&
            modelManager.isWhisperInstalled(lang)
        ) {
            WhisperAsrEngine(
                modelPath = modelManager.whisperModelFile(lang).path,
                name = "phowhisper-vi",
                language = "vi",
            )
        } else {
            val tag = if (lang == VoiceLanguage.ENGLISH) "vosk-en" else "vosk-vi"
            VoskAsrEngine(modelManager.modelDir(lang).path, tag)
        }
    }

    private val commandMatcher = CommandMatcher()
    private val commandHandler by lazy {
        VoiceCommandHandler(InputConnectionTextEditor { currentInputConnection })
    }

    // ---- Lifecycle ----

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()
        clipboardHistory = ClipboardHistoryManager(this)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.addPrimaryClipChangedListener {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text
                if (!text.isNullOrEmpty()) {
                    clipboardHistory.addItem(text.toString())
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        // Set ViewTree owners on the window's decor view so that Compose can find them during Recomposer resolution.
        getWindow()?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewTreeLifecycleOwner(this@SimpleTypeIME)
            setViewTreeViewModelStoreOwner(this@SimpleTypeIME)
            setViewTreeSavedStateRegistryOwner(this@SimpleTypeIME)
        }

        composeView.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorResource(R.color.kb_background))
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            ) {
                KeyboardScreen(
                    keyboard = composeKeyboard,
                    metrics = composeMetrics,
                    spaceLabel = composeSpaceLabel,
                    shifted = composeShifted,
                    capsLock = composeCapsLock,
                    voiceStatus = composeVoiceStatus,
                    micActive = composeMicActive,
                    optionsExpanded = composeOptionsExpanded,
                    clipboardItems = composeClipboardItems,
                    clipboardVisible = composeClipboardVisible,
                    listener = this@SimpleTypeIME,
                    onMicClick = { handleMic() },
                    onSetupClick = { openSettings() },
                    onClipboardClick = { showClipboard() },
                    onMenuClick = { toggleOptions() },
                    onClipboardClose = { hideClipboard() },
                    onClipboardSelect = { text ->
                        currentInputConnection?.commitText(text, 1)
                        hideClipboard()
                    },
                    onClipboardPin = { id ->
                        clipboardHistory.togglePin(id)
                        composeClipboardItems = clipboardHistory.getItems()
                    },
                    onClipboardDelete = { id ->
                        clipboardHistory.deleteItem(id)
                        composeClipboardItems = clipboardHistory.getItems()
                    }
                )
            }
        }

        applyKeyboardMetrics()
        applyLayout()
        return composeView
    }

    private fun applyKeyboardMetrics() {
        val prefs = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)
        metrics = KeyboardMetrics.load(prefs)
        composeMetrics = metrics
    }

    private fun openSettings() {
        startActivity(
            Intent(this, dev.phucngu.simpletype.ui.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        telex.reset()
        commandHandler.clearHistory()
        layout = Layout.ALPHA
        capsLock = false
        passwordField = isPasswordField(info)
        directCommit = info.inputType == InputType.TYPE_NULL
        hideStatus()
        composeOptionsExpanded = false
        hideClipboard()

        // Sync latest clipboard item
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.let { clip ->
            if (clip.itemCount > 0) {
                clip.getItemAt(0).text?.let {
                    if (it.isNotEmpty()) clipboardHistory.addItem(it.toString())
                }
            }
        }

        if (voice.isListening) voice.stop()
        setMicListening(false)

        val prefs = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)
        val langTag = prefs.getString("language", VoiceLanguage.ENGLISH.name)
        language = VoiceLanguage.valueOf(langTag ?: VoiceLanguage.ENGLISH.name)

        applyKeyboardMetrics()
        chooseLayoutForField(info)
        applyLayout()
        updateAutoCapitalize(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        if (voice.isListening) voice.stop()
        telex.reset()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        val ic = currentInputConnection ?: return
        val cursorAtComposingEnd =
            newSelStart == newSelEnd && candidatesEnd >= 0 && newSelEnd == candidatesEnd

        if (!cursorAtComposingEnd) {
            val wasComposing = !telex.isEmpty
            if (wasComposing) {
                ic.finishComposingText()
                telex.reset()
            }
            if (language == VoiceLanguage.VIETNAMESE && !passwordField && layout == Layout.ALPHA &&
                !directCommit) {
                pickupTelexContext(ic, newSelStart)
            }
            updateAutoCapitalize(currentInputEditorInfo)
        }
    }

    private fun pickupTelexContext(ic: InputConnection, cursor: Int) {
        val before = ic.getTextBeforeCursor(WORD_DELETE_LOOKBEHIND, 0) ?: ""
        if (before.isEmpty() || before.last().isWhitespace()) return

        var i = before.length
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val word = before.substring(i)

        if (word.all { it.isLetter() }) {
            telex.load(word)
            ic.setComposingRegion(cursor - word.length, cursor)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        voice.release()
        engines.values.forEach { it.release() }
        engines.clear()
        super.onDestroy()
    }

    // ---- Key handling ----

    override fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (key.code) {
            KeyCode.SHIFT -> cycleShift()
            KeyCode.DELETE -> handleDelete(ic)
            KeyCode.ENTER -> handleEnter(ic)
            KeyCode.SPACE -> handleSpace(ic)
            KeyCode.SYMBOLS -> switchLayout(Layout.SYMBOLS)
            KeyCode.SYMBOLS_ALT -> switchLayout(Layout.SYMBOLS_ALT)
            KeyCode.ALPHA -> switchLayout(Layout.ALPHA)
            KeyCode.EMOJI -> handleEmoji(ic)
            else -> if (key.isPrintable) handlePrintable(ic, key)
        }
    }

    override fun onKeyRepeat(key: Key) {
        val ic = currentInputConnection ?: return
        if (key.code == KeyCode.DELETE) handleDelete(ic)
    }

    override fun onSpaceSwipe(direction: Int) = toggleLanguage()

    override fun onShiftHold(active: Boolean) {
        shiftHeld = active
        if (capsLock) return
        shifted = active
        syncShiftToView()
    }

    private fun handlePrintable(ic: InputConnection, key: Key) {
        var c = key.code.toChar()
        if (c.isLetter() && (shifted || capsLock)) c = c.uppercaseChar()

        val useTelex = language == VoiceLanguage.VIETNAMESE && layout == Layout.ALPHA &&
            !passwordField && !directCommit && c.isLetter()

        if (useTelex) {
            telex.input(c)
            ic.setComposingText(telex.composing, 1)
        } else {
            finishComposing(ic)
            ic.commitText(c.toString(), 1)
        }
        consumeShift()
    }

    private fun handleDelete(ic: InputConnection) {
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        if (!telex.isEmpty) {
            if (shiftHeld) {
                telex.reset()
                ic.setComposingText("", 1)
                ic.finishComposingText()
                return
            }
            telex.backspace()
            if (telex.isEmpty) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                ic.setComposingText(telex.composing, 1)
            }
            return
        }
        if (shiftHeld) {
            deleteWordBeforeCursor(ic)
            return
        }
        ic.deleteSurroundingText(1, 0)
        updateAutoCapitalize(currentInputEditorInfo)
    }

    private fun deleteWordBeforeCursor(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(WORD_DELETE_LOOKBEHIND, 0) ?: ""
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    private fun handleSpace(ic: InputConnection) {
        finishComposing(ic)
        ic.commitText(" ", 1)
        updateAutoCapitalize(currentInputEditorInfo)
    }

    private fun handleEmoji(ic: InputConnection) {
        finishComposing(ic)
        ic.commitText("🙂", 1)
    }

    private fun handleEnter(ic: InputConnection) {
        finishComposing(ic)
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiline = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (!multiline && !noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
            updateAutoCapitalize(info)
        }
    }

    // ---- Shift / caps ----

    private fun cycleShift() {
        when {
            capsLock -> { capsLock = false; shifted = false }
            shifted -> { capsLock = true; shifted = false }
            else -> shifted = true
        }
        syncShiftToView()
    }

    private fun consumeShift() {
        if (shifted && !capsLock && !shiftHeld) {
            shifted = false
            syncShiftToView()
        }
    }

    private fun updateAutoCapitalize(info: EditorInfo?) {
        if (capsLock || shiftHeld || passwordField || layout != Layout.ALPHA) return
        info ?: return
        val capSentences = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        val capWords = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0
        val capChars = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0
        val isTextField = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        if (!isTextField || (!capSentences && !capWords && !capChars)) return

        val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val atStart = before.isEmpty() || before.endsWith("\n") ||
            before.trimEnd().let { it.isEmpty() || it.endsWith(".") || it.endsWith("?") || it.endsWith("!") }
        val newShift = capChars || atStart || (capWords && (before.isEmpty() || before.endsWith(" ")))
        if (newShift != shifted) {
            shifted = newShift
            syncShiftToView()
        }
    }

    private fun syncShiftToView() {
        composeShifted = shifted
        composeCapsLock = capsLock
    }

    private fun setMicListening(active: Boolean) {
        composeMicActive = active
    }

    // ---- Layout & language ----

    private fun switchLayout(target: Layout) {
        layout = target
        applyLayout()
    }

    private fun chooseLayoutForField(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        layout = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> Layout.SYMBOLS
            else -> Layout.ALPHA
        }
    }

    private fun applyLayout() {
        val targetKeyboard = when (layout) {
            Layout.ALPHA -> KeyboardLayouts.qwerty(metrics.showDedicatedNumberRow)
            Layout.SYMBOLS -> KeyboardLayouts.symbols()
            Layout.SYMBOLS_ALT -> KeyboardLayouts.symbolsAlt()
        }
        composeKeyboard = targetKeyboard
        composeSpaceLabel = languageLabel()
        syncShiftToView()
    }

    private fun toggleLanguage() {
        currentInputConnection?.let { finishComposing(it) }
        if (voice.isListening) {
            voice.stop()
            setMicListening(false)
            hideStatus()
        }

        language = if (language == VoiceLanguage.ENGLISH) {
            VoiceLanguage.VIETNAMESE
        } else {
            VoiceLanguage.ENGLISH
        }
        composeSpaceLabel = languageLabel()

        getSharedPreferences("simpletype_prefs", MODE_PRIVATE).edit()
            .putString("language", language.name)
            .apply()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        currentInputConnection?.let { finishComposing(it) }
        val tag = newSubtype?.languageTag?.takeIf { it.isNotEmpty() }
            ?: @Suppress("DEPRECATION") newSubtype?.locale ?: ""
        language = if (tag.startsWith("vi")) VoiceLanguage.VIETNAMESE else VoiceLanguage.ENGLISH
        composeSpaceLabel = languageLabel()
    }

    private fun languageLabel(): String = when (language) {
        VoiceLanguage.ENGLISH -> getString(R.string.subtype_en)
        VoiceLanguage.VIETNAMESE -> getString(R.string.subtype_vi)
    }

    // ---- Voice ----

    private fun handleMic() {
        if (passwordField) {
            showStatus(getString(R.string.voice_password_disabled))
            return
        }
        if (voice.isListening) {
            voice.stop()
            setMicListening(false)
            hideStatus()
            return
        }
        if (!voice.hasAudioPermission()) {
            requestMicPermission()
            return
        }
        currentInputConnection?.let { finishComposing(it) }
        voice.setEngine(engineFor(language))
        voice.start()
    }

    private fun requestMicPermission() {
        showStatus(getString(R.string.voice_need_permission))
        val intent = Intent(this, MicPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private val voiceListener = object : AsrListener {
        override fun onPartial(text: String) {
            setMicListening(true)
            showStatus(getString(R.string.voice_listening))
            currentInputConnection?.setComposingText(text, 1)
        }

        override fun onFinal(text: String, confidence: Float) {
            val ic = currentInputConnection ?: return
            ic.finishComposingText()
            val action = commandMatcher.match(text, confidence)
            if (commandHandler.handle(action, text) == VoiceCommandHandler.Result.STOP_LISTENING) {
                voice.stop()
                setMicListening(false)
                hideStatus()
            }
        }

        override fun onError(message: String) {
            setMicListening(false)
            val msg = when (message) {
                VoiceInputController.ENGINE_UNAVAILABLE -> getString(R.string.voice_unavailable)
                VoiceInputController.NEEDS_PERMISSION -> getString(R.string.voice_need_permission)
                else -> getString(R.string.voice_unavailable)
            }
            showStatus(msg)
        }
    }

    // ---- Helpers ----

    private fun finishComposing(ic: InputConnection) {
        if (!telex.isEmpty) {
            ic.finishComposingText()
            telex.reset()
        }
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when {
            cls == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) -> true
            cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }

    private fun showStatus(text: String) {
        composeVoiceStatus = text
    }

    private fun hideStatus() {
        composeVoiceStatus = null
    }

    private fun showClipboard() {
        composeOptionsExpanded = false
        composeClipboardVisible = true
        composeClipboardItems = clipboardHistory.getItems()
    }

    private fun hideClipboard() {
        composeClipboardVisible = false
    }

    private fun toggleOptions() {
        composeOptionsExpanded = !composeOptionsExpanded
    }

    private companion object {
        const val WORD_DELETE_LOOKBEHIND = 64
    }
}
