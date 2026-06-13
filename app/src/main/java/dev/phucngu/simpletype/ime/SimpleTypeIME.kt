package dev.phucngu.simpletype.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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

/**
 * The SimpleType input method.
 *
 * Renders a custom [LatinKeyboardView] and translates key events into [InputConnection]
 * edits: direct commits in English, and underlined composing text driven by [TelexEngine]
 * in Vietnamese. Adapts to the target field via [EditorInfo] (enter-key action, sentence
 * auto-capitalisation, password handling) and hosts the on-device voice pipeline
 * ([VoiceInputController]) behind the mic key.
 *
 * Touch typing (M1) is fully functional here; the voice path is wired but reports
 * "model not installed" until an ASR engine is bundled (M2/M3).
 */
open class SimpleTypeIME : InputMethodService(), LatinKeyboardView.Listener {

    private lateinit var keyboardView: LatinKeyboardView
    private var metrics = KeyboardMetrics.DEFAULT
    private var statusView: TextView? = null
    private var micButton: ImageButton? = null

    // Root padded for the nav-bar inset; bottomPaddingPx is the user-adjustable lift above it.
    private var keyboardRoot: View? = null
    private var bottomPaddingPx = 0

    private val telex = TelexEngine()
    private var language = VoiceLanguage.ENGLISH

    private enum class Layout { ALPHA, SYMBOLS, SYMBOLS_ALT }
    private var layout = Layout.ALPHA

    private var shifted = false
    private var capsLock = false
    private var shiftHeld = false
    private var passwordField = false

    private val voice: VoiceInputController by lazy {
        VoiceInputController(this, voiceListener)
    }
    private val modelManager by lazy { ModelManager(this) }
    private val engines = mutableMapOf<VoiceLanguage, AsrEngine>()

    /** Cached on-device ASR engine for [lang]; the model is loaded lazily on first use. */
    private fun engineFor(lang: VoiceLanguage): AsrEngine = engines.getOrPut(lang) {
        val tag = if (lang == VoiceLanguage.ENGLISH) "vosk-en" else "vosk-vi"
        VoskAsrEngine(modelManager.modelDir(lang).path, tag)
    }

    // Voice-command pipeline: finalized utterances are matched to a command (or text) and
    // executed against the field, with every edit kept undoable.
    private val commandMatcher = CommandMatcher()
    private val commandHandler by lazy {
        VoiceCommandHandler(InputConnectionTextEditor { currentInputConnection })
    }

    // ---- Lifecycle ----

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = root.findViewById(R.id.keyboard)
        statusView = root.findViewById(R.id.voice_status)
        keyboardView.listener = this

        // Toolbar (top) hosts the mic; the bottom strip hosts language switching and collapse.
        micButton = root.findViewById<ImageButton>(R.id.toolbar_mic).apply {
            setOnClickListener { handleMic() }
        }
        // Toolbar menu opens the app's settings screen (keyboard sizing, voice models, …).
        root.findViewById<ImageButton>(R.id.toolbar_menu).setOnClickListener { openSettings() }

        applyBottomInset(root.findViewById(R.id.keyboard_root))
        applyKeyboardMetrics()
        applyLayout()
        return root
    }

    /** Load the user's keyboard sizing preferences and push them to the view and bottom padding. */
    private fun applyKeyboardMetrics() {
        val prefs = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)
        metrics = KeyboardMetrics.load(prefs)
        keyboardView.applyMetrics(metrics)
        keyboardView.showNumberRow = metrics.showNumberRow
        bottomPaddingPx = (metrics.bottomPaddingDp * resources.displayMetrics.density).toInt()
        keyboardRoot?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, dev.phucngu.simpletype.ui.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Keep the bottom row clear of the navigation bar and rounded screen corners: pad the
     * keyboard's bottom by the nav-bar inset plus a user-adjustable gap, so corner keys are never
     * clipped by the display curve or gesture bar, and the keys can be lifted into thumb reach.
     */
    private fun applyBottomInset(root: View) {
        keyboardRoot = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottomPaddingPx + navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        telex.reset()
        commandHandler.clearHistory()
        layout = Layout.ALPHA
        capsLock = false
        passwordField = isPasswordField(info)
        hideStatus()
        if (voice.isListening) voice.stop()
        setMicListening(false)

        // Persist language choice across fields and restarts.
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
        if (voice.isListening) voice.stop()
        telex.reset()
    }

    /**
     * The cursor sits at the end of the Telex composing region after each keystroke. If a selection
     * change reports it anywhere else — the user tapped to move it, made a selection, or the
     * composing span was dropped — finish the composing word so further typing starts fresh at the
     * new cursor instead of rewriting the old word in place.
     */
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
            // Pick up context if the cursor moved to the end of a word.
            if (language == VoiceLanguage.VIETNAMESE && !passwordField && layout == Layout.ALPHA) {
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

    /** Swiping across the space bar toggles EN ⇄ VI (only two languages, so direction is moot). */
    override fun onSpaceSwipe(direction: Int) = toggleLanguage()

    /**
     * Shift held as a modifier (one finger holds Shift, another taps keys). Keep shift active for
     * the whole hold so repeated Delete taps delete words and letters come out capitalised; restore
     * when released. Caps lock takes precedence and is left untouched.
     */
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
            !passwordField && c.isLetter()

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
        // If a selection exists, let the platform delete it.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        if (!telex.isEmpty) {
            telex.backspace()
            if (telex.isEmpty) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                ic.setComposingText(telex.composing, 1)
            }
            return
        }
        // Shift turns Delete into delete-word-backwards (caps lock keeps single-char). Keep shift
        // armed so repeated taps keep deleting words; consuming it here (or letting
        // updateAutoCapitalize reset it) would make only the first tap a word-delete.
        if (shifted && !capsLock) {
            deleteWordBeforeCursor(ic)
            return
        }
        ic.deleteSurroundingText(1, 0)
        updateAutoCapitalize(currentInputEditorInfo)
    }

    /** Delete any trailing whitespace plus the word before the cursor; falls back to one char. */
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

    // Placeholder until a full emoji panel exists: commit a single smiley so the key is live.
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

    /** One-shot shift is cleared after a character is typed; caps lock and a held Shift persist. */
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
        keyboardView.shifted = shifted
        keyboardView.capsLock = capsLock
    }

    /** Tint the toolbar mic red while the recogniser is listening, chrome colour otherwise. */
    private fun setMicListening(active: Boolean) {
        val tint = if (active) R.color.kb_mic_active else R.color.kb_chrome_icon
        micButton?.setColorFilter(ContextCompat.getColor(this, tint))
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
        keyboardView.keyboard = when (layout) {
            Layout.ALPHA -> KeyboardLayouts.qwerty(metrics.showDedicatedNumberRow)
            Layout.SYMBOLS -> KeyboardLayouts.symbols()
            Layout.SYMBOLS_ALT -> KeyboardLayouts.symbolsAlt()
        }
        keyboardView.spaceLabel = languageLabel()
        syncShiftToView()
    }

    /** Primary language switch: swipe the space bar. Toggles EN ⇄ VI and persists across fields. */
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
        keyboardView.spaceLabel = languageLabel()

        getSharedPreferences("simpletype_prefs", MODE_PRIVATE).edit()
            .putString("language", language.name)
            .apply()
    }

    /**
     * Honour an explicit system input-method-subtype change too (e.g. if the user enables the
     * Vietnamese subtype and switches via the system globe), keeping our state in sync.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        currentInputConnection?.let { finishComposing(it) }
        val tag = newSubtype?.languageTag?.takeIf { it.isNotEmpty() }
            ?: @Suppress("DEPRECATION") newSubtype?.locale ?: ""
        language = if (tag.startsWith("vi")) VoiceLanguage.VIETNAMESE else VoiceLanguage.ENGLISH
        keyboardView.spaceLabel = languageLabel()
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
            // A whole utterance matches a command only if it is one in full (spec §3.3);
            // otherwise it is committed as text. Either way the handler keeps it undoable.
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
        statusView?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        statusView?.visibility = View.GONE
    }

    private companion object {
        /** How far back to scan for the start of the word when shift-deleting. */
        const val WORD_DELETE_LOOKBEHIND = 64
    }
}
