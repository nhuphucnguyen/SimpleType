package dev.phucngu.simpletype.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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
import dev.phucngu.simpletype.ai.AiSettings
import dev.phucngu.simpletype.ai.AiTextFixer
import dev.phucngu.simpletype.ai.describe
import dev.phucngu.simpletype.gesture.GestureCommit
import dev.phucngu.simpletype.gesture.GestureDecoder
import dev.phucngu.simpletype.gesture.GestureDictionary
import dev.phucngu.simpletype.gesture.GesturePoint
import dev.phucngu.simpletype.gesture.KeyGeometry
import dev.phucngu.simpletype.ime.keyboard.layout.NumericKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.layout.QwertyKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.layout.SymbolKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode
import dev.phucngu.simpletype.ime.keyboard.selection.KeyboardLayoutSelector
import dev.phucngu.simpletype.ime.keyboard.selection.KeyboardLayoutType
import dev.phucngu.simpletype.ime.email.SavedEmail
import dev.phucngu.simpletype.ime.email.SavedEmails
import dev.phucngu.simpletype.ime.emoji.EmojiRecents
import dev.phucngu.simpletype.predict.NextWordPredictor
import dev.phucngu.simpletype.predict.TypingContext
import dev.phucngu.simpletype.text.AutoSwitchSession
import dev.phucngu.simpletype.text.LanguageAutoSwitch
import dev.phucngu.simpletype.text.TelexEngine
import dev.phucngu.simpletype.text.lastGraphemeLength
import dev.phucngu.simpletype.text.moveByGraphemes
import dev.phucngu.simpletype.ui.MicPermissionActivity
import dev.phucngu.simpletype.voice.AsrEngine
import dev.phucngu.simpletype.voice.AsrListener
import dev.phucngu.simpletype.voice.CommandMatcher
import dev.phucngu.simpletype.voice.DictationLog
import dev.phucngu.simpletype.voice.VoiceAction
import dev.phucngu.simpletype.voice.ModelManager
import dev.phucngu.simpletype.voice.VoiceCommandHandler
import dev.phucngu.simpletype.voice.VoiceInputController
import dev.phucngu.simpletype.voice.VoiceLanguage
import dev.phucngu.simpletype.voice.SherpaAsrEngine
import dev.phucngu.simpletype.voice.punct.Punctuator
import dev.phucngu.simpletype.voice.SherpaModel
import dev.phucngu.simpletype.voice.SegmentJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    private var composeKeyboard by mutableStateOf(QwertyKeyboardLayout.create())
    private var composeMetrics by mutableStateOf(KeyboardMetrics.DEFAULT)
    private var composeSpaceLabel by mutableStateOf("")
    private var composeShifted by mutableStateOf(false)
    private var composeCapsLock by mutableStateOf(false)
    private var composeEnterIconRes by mutableStateOf(R.drawable.ic_kb_enter)
    private var composeVoiceStatus by mutableStateOf<String?>(null)
    private var composeMicActive by mutableStateOf(false)
    private var composeOptionsExpanded by mutableStateOf(false)
    private var composeClipboardItems by mutableStateOf<List<ClipboardItem>>(emptyList())
    private var composeClipboardVisible by mutableStateOf(false)
    private var composeEmojiVisible by mutableStateOf(false)
    private var composeEmojiRecents by mutableStateOf<List<String>>(emptyList())
    private var composeSuggestions by mutableStateOf<List<String>>(emptyList())
    private var composeSelectedSuggestion by mutableStateOf<String?>(null)
    private var composeEmailSuggestions by mutableStateOf<List<String>>(emptyList())
    private var composePredictions by mutableStateOf<List<String>>(emptyList())
    private var composeGlideEnabled by mutableStateOf(false)
    private var composeAiFixVisible by mutableStateOf(false)
    private var composeAiFixState by mutableStateOf(AiFixController.State.IDLE)

    private val imeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selectionSyncJob: Job? = null
    /** The cursor the waiting [selectionSyncJob] was scheduled for. */
    private var pendingSyncCursor = 0
    private var glidePrefEnabled = true
    private val gestureDecoders = mutableMapOf<VoiceLanguage, GestureDecoder>()
    private var lastGlideWord: String? = null

    // Next-word predictions and completions (see [WordPredictions]).
    private val predictions by lazy { WordPredictions(filesDir) }
    private var predictionsAllowed = false
    private var predictionLearning = false
    private var historyGeneration = 0
    /** Uncased suggestions for [predictionContext]; cased for display by [showPredictions]. */
    private var rawPredictions: List<String> = emptyList()
    private var predictionContext: TypingContext? = null
    private var predictionsStartSentence = false
    /** A picked suggestion committed with a trailing space that punctuation may take back. */
    private var autoSpacedWord: String? = null

    private lateinit var clipboardHistory: ClipboardHistoryManager

    private val telex = TelexEngine()
    private var language = VoiceLanguage.ENGLISH

    // Automatic English/Vietnamese switching (see [LanguageAutoSwitch]).
    /** The words auto switch may still fix when a later word makes the language clear. */
    @Volatile private var autoSwitchSession: AutoSwitchSession? = null
    private var autoSwitchAllowed = false
    /** Letters tapped in English for the word being typed, checked by auto switch at its end. */
    private val englishKeys = StringBuilder()
    /** The last auto-switch fix, undone by a Backspace right after it. */
    private var lastAutoFix: AutoFix? = null
    /** A word whose fix was just undone: finishing it again keeps it as typed. */
    private var keptWord: String? = null
    /** Auto switch rests until this time (uptime ms) after a manual language switch. */
    private var autoSwitchPausedUntil = 0L

    /** [original] in [from] was replaced by [replacement], then [separator] was typed. */
    private class AutoFix(
        val original: String,
        val replacement: String,
        val separator: String,
        val from: VoiceLanguage,
    )

    private var layout = KeyboardLayoutType.ALPHA

    private var shifted = false
    // True while [shifted] was armed by auto-capitalisation rather than a Shift tap.
    private var autoShifted = false
    private var capsLock = false
    private var shiftHeld = false
    private var passwordField = false
    private var directCommit = false

    // Saved-email suggestions: active only while an email field is focused.
    private var emailField = false
    private var emailLearning = false
    /** Opt-in punctuation log for this field (see [DictationLog]). */
    private var dictationLogging = false
    private val dictationLog by lazy { DictationLog(java.io.File(filesDir, DictationLog.FILE_NAME)) }
    private var savedEmails: List<SavedEmail> = emptyList()
    /** Last-seen text of the email field, learned from when the field is left. */
    private var emailFieldText = ""

    private val voice: VoiceInputController by lazy {
        VoiceInputController(this, voiceListener)
    }
    private val modelManager by lazy { ModelManager(this) }
    private val engines = mutableMapOf<VoiceLanguage, AsrEngine>()

    private fun engineFor(lang: VoiceLanguage): AsrEngine = engines.getOrPut(lang) {
        // Populate the model dir from bundled assets if this build embeds it (local test
        // builds); no-op otherwise. One-time copy, then isAvailable sees the files.
        modelManager.installFromAssetsIfBundled(lang)
        SherpaAsrEngine(
            SherpaModel.forLanguage(lang),
            modelManager.modelDir(lang),
            modelManager.vadFile(),
            punctuatorLoader = if (lang == VoiceLanguage.VIETNAMESE) {
                { Punctuator.fromAssets(assets) }
            } else {
                null
            },
        )
    }

    private val aiFix by lazy {
        AiFixController(
            scope = imeScope,
            connection = { currentInputConnection },
            prepare = { finishComposing(it) },
            fixer = { text ->
                val lang = language
                AiTextFixer(AiSettings.load(aiPrefs())).fix(text, lang)
            },
            onStateChange = { composeAiFixState = it },
            onOutcome = { onAiFixOutcome(it) },
        )
    }
    private var statusHideJob: Job? = null

    private val commandMatcher = CommandMatcher()
    private val commandHandler by lazy {
        VoiceCommandHandler(InputConnectionTextEditor { currentInputConnection })
    }

    // ---- Lifecycle ----

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()
        imeScope.launch(Dispatchers.IO) {
            val dictionaries = mutableMapOf<VoiceLanguage, GestureDictionary>()
            for ((lang, asset) in GLIDE_DICTIONARY_ASSETS) {
                try {
                    val dictionary = assets.open(asset).use { GestureDictionary.parse(it) }
                    gestureDecoders[lang] = GestureDecoder(dictionary)
                    dictionaries[lang] = dictionary
                } catch (e: Exception) {
                    android.util.Log.w("SimpleTypeIME", "Failed to load gesture dictionary $asset", e)
                }
            }
            val english = dictionaries[VoiceLanguage.ENGLISH]
            val vietnamese = dictionaries[VoiceLanguage.VIETNAMESE]
            if (english != null && vietnamese != null) {
                autoSwitchSession = AutoSwitchSession(LanguageAutoSwitch(english.scores(), vietnamese.scores()))
            }
        }
        imeScope.launch {
            val loaded = withContext(Dispatchers.IO) { predictions.load(assets) }
            predictions.publish(loaded)
            refreshPredictions()
        }
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
                    enterIconRes = composeEnterIconRes,
                    voiceStatus = composeVoiceStatus,
                    micActive = composeMicActive,
                    optionsExpanded = composeOptionsExpanded,
                    clipboardItems = composeClipboardItems,
                    clipboardVisible = composeClipboardVisible,
                    listener = this@SimpleTypeIME,
                    glideEnabled = composeGlideEnabled,
                    suggestions = composeSuggestions,
                    selectedSuggestion = composeSelectedSuggestion,
                    onSuggestionClick = { onSuggestionSelected(it) },
                    emailSuggestions = composeEmailSuggestions,
                    onEmailSuggestionClick = { onEmailSuggestionSelected(it) },
                    predictions = composePredictions,
                    onPredictionClick = { onPredictionSelected(it) },
                    onMicClick = { handleMic() },
                    aiFixVisible = composeAiFixVisible,
                    aiFixState = composeAiFixState,
                    onAiFixClick = { handleAiFix() },
                    onSetupClick = { openSettings() },
                    onClipboardClick = { showClipboard() },
                    onMenuClick = { toggleOptions() },
                    onClipboardClose = { hideClipboard() },
                    onClipboardSelect = { text ->
                        aiFix.invalidate()
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
                    },
                    emojiVisible = composeEmojiVisible,
                    emojiRecents = composeEmojiRecents,
                    onEmojiSelect = { commitEmoji(it) },
                    onEmojiDelete = { currentInputConnection?.let { handleDelete(it) } },
                    onEmojiClose = { hideEmoji() },
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
        selectionSyncJob?.cancel()
        telex.reset()
        commandHandler.clearHistory()
        layout = KeyboardLayoutType.ALPHA
        composeEmojiVisible = false
        capsLock = false
        passwordField = isPasswordField(info)
        directCommit = info.inputType == InputType.TYPE_NULL
        composeEnterIconRes = EnterAction.resolve(info).iconRes
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
        aiFix.cancel()
        aiFix.invalidate()
        composeAiFixVisible = !passwordField && AiSettings.load(aiPrefs()).isConfigured

        val prefs = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)
        val langTag = prefs.getString("language", VoiceLanguage.ENGLISH.name)
        language = VoiceLanguage.valueOf(langTag ?: VoiceLanguage.ENGLISH.name)
        glidePrefEnabled = prefs.getBoolean(LatinKeyboardView.PREF_GLIDE, true)
        clearGlideSuggestions()

        emailField = !passwordField && prefs.getBoolean(SavedEmails.PREF_ENABLED, true) &&
            SavedEmails.isEmailField(info.inputType, info.hintText)
        // Incognito fields ask keyboards not to learn from what is typed.
        emailLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        savedEmails = if (emailField) SavedEmails.load(prefs) else emptyList()
        dictationLogging = !passwordField && emailLearning && prefs.getBoolean(DictationLog.PREF_ENABLED, false)
        emailFieldText = ""
        refreshEmailSuggestions()

        predictionsAllowed = prefs.getBoolean(WordPredictions.PREF_ENABLED, true) &&
            !passwordField && !emailField && !directCommit && SuggestionFields.showsSuggestions(info.inputType)
        predictionLearning = predictionsAllowed && emailLearning &&
            SuggestionFields.learnsWords(info.inputType)
        syncHistoryGeneration(prefs)
        autoSpacedWord = null

        // Everywhere Telex types: not in passwords and email addresses, which keep every key.
        autoSwitchAllowed = prefs.getBoolean(LanguageAutoSwitch.PREF_ENABLED, true) &&
            !passwordField && !emailField && !directCommit
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        lastAutoFix = null
        keptWord = null

        applyKeyboardMetrics()
        chooseLayoutForField(info)
        applyLayout()
        updateAutoCapitalize(info)
        refreshPredictions()
    }

    override fun onFinishInput() {
        learnEmailField()
        logDictatedField()
        savePredictionHistory()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        selectionSyncJob?.cancel()
        spaceCursorField = null
        stopTrackpad()
        if (voice.isListening) voice.stop()
        aiFix.cancel()
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
        trackpadSession?.let { session ->
            session.onSelection(newSelStart, newSelEnd)
            if (trackpadEnding && !session.cursor.awaitingHit) stopTrackpad()
        }
        val cursorAtComposingEnd =
            newSelStart == newSelEnd && candidatesEnd >= 0 && newSelEnd == candidatesEnd
        if (cursorAtComposingEnd) {
            // Our own composing edit: a re-sync still waiting was scheduled for a cursor that
            // has moved on since, and would pick up the word at the wrong place.
            selectionSyncJob?.cancel()
            return
        }

        // Every commit echoes back a burst of selection updates (our own
        // finishComposingText/setComposingRegion writes each trigger another one), and
        // the re-sync below performs blocking InputConnection reads plus writes that
        // extend the burst. Handling each update synchronously starves touch events,
        // which turns the next glide gesture into a sparse, mis-decoded path. Debounce
        // so a burst collapses into a single re-sync once the updates settle.
        selectionSyncJob?.cancel()
        selectionSyncJob = imeScope.launch {
            delay(SELECTION_SYNC_DEBOUNCE_MS)
            syncSelection(newSelStart)
        }
        pendingSyncCursor = newSelStart
    }

    /**
     * A key typed while a re-sync waits runs it first, while [pendingSyncCursor] is still where
     * the cursor is. Left to run after the key, it would pick up the word at that stale position
     * and the next letter would replace the space before it ("giải th" → "giảith|t").
     */
    private fun flushSelectionSync() {
        val job = selectionSyncJob ?: return
        if (!job.isActive) return
        job.cancel()
        syncSelection(pendingSyncCursor)
    }

    private fun syncSelection(cursor: Int) {
        val ic = currentInputConnection ?: return
        if (!telex.isEmpty) {
            ic.finishComposingText()
            telex.reset()
        }
        if (language == VoiceLanguage.VIETNAMESE && !passwordField && !emailField &&
            layout == KeyboardLayoutType.ALPHA &&
            !directCommit) {
            pickupTelexContext(ic, cursor)
        }
        updateAutoCapitalize(currentInputEditorInfo)
        refreshEmailSuggestions()
        refreshPredictions()
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
        imeScope.cancel()
        store.clear()
        voice.release()
        engines.values.forEach { it.release() }
        engines.clear()
        super.onDestroy()
    }

    // ---- Key handling ----

    override fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        flushSelectionSync()
        clearGlideSuggestions()
        // Only the key right after a fix can undo it.
        val pendingFix = lastAutoFix
        lastAutoFix = null
        when (key.code) {
            KeyCode.SHIFT -> cycleShift()
            KeyCode.DELETE -> if (!undoAutoFix(ic, pendingFix)) handleDelete(ic)
            KeyCode.ENTER -> handleEnter(ic)
            KeyCode.SPACE -> handleSpace(ic)
            KeyCode.SYMBOLS -> switchLayout(KeyboardLayoutType.SYMBOLS)
            KeyCode.SYMBOLS_ALT -> switchLayout(KeyboardLayoutType.SYMBOLS_ALT)
            KeyCode.ALPHA -> switchLayout(KeyboardLayoutType.ALPHA)
            KeyCode.EMOJI -> handleEmoji(ic)
            KeyCode.DOUBLE_ZERO -> {
                finishComposing(ic)
                ic.commitText("00", 1)
            }
            else -> if (key.isPrintable) handlePrintable(ic, key)
        }
        autoSpacedWord = null
        refreshPredictions()
    }

    override fun onKeyRepeat(key: Key) {
        val ic = currentInputConnection ?: return
        flushSelectionSync()
        clearGlideSuggestions()
        if (key.code == KeyCode.DELETE) handleDelete(ic)
        refreshPredictions()
    }

    override fun onSpaceSwipe(direction: Int) = toggleLanguage()

    override fun onSpaceCursorMove(steps: Int, lines: Int, dx: Float, dy: Float) {
        val ic = currentInputConnection ?: return
        val trackpad = trackpadSession?.takeIf { it.cursor.ready }
        // In a single-line field DPAD up/down moves focus to another view, so only send it
        // where there are lines to move between.
        val verticalLines = if (isMultiLineField(currentInputEditorInfo)) lines else 0
        if (trackpad == null && steps == 0 && verticalLines == 0) return
        if (trackpad != null && dx == 0f && dy == 0f) return
        clearGlideSuggestions()
        finishComposing(ic)
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        if (trackpad != null) {
            // The editor reports where its caret is: move it like a trackpad pointer.
            trackpad.onPointerDelta(dx, dy)
            return
        }
        if (steps != 0 && !moveCursorBySelection(ic, steps)) {
            sendCursorKeys(ic, steps, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        if (verticalLines != 0) {
            // The editor decides where a line move lands, so the cached cursor is stale after it.
            spaceCursorField = null
            sendCursorKeys(ic, verticalLines, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    /** Trackpad-style cursor for the current space-drag, while the editor reports its caret. */
    private var trackpadSession: TrackpadSession? = null
    private var trackpadEnding = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopTrackpadRunnable = Runnable { stopTrackpad() }
    /** Created on first use: most sessions never drag the cursor. */
    private var ghostCursor: GhostCursor? = null

    override fun onSpaceCursorStart() {
        stopTrackpad()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (info?.inputType == InputType.TYPE_NULL) return
        val field = readSpaceCursorField(ic)?.also { spaceCursorField = it }
        val session = TrackpadSession(
            ic, info, field?.text,
            lineMover = { lines ->
                isMultiLineField(currentInputEditorInfo).also { multi ->
                    if (multi) sendCursorKeys(ic, lines, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
                }
            },
            mainExecutor = mainExecutor,
            onPointer = { x, y, lineHeight, detached ->
                val anchor = window?.window?.decorView
                if (!detached) {
                    ghostCursor?.hide()
                } else if (!trackpadEnding && anchor != null) {
                    (ghostCursor ?: GhostCursor(this).also { ghostCursor = it }).show(anchor, x, y, lineHeight)
                }
            },
        )
        if (session.start()) trackpadSession = session
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        val session = trackpadSession ?: return
        session.onCursorAnchorInfo(cursorAnchorInfo)
        // After lift-off, keep listening only until a pending hit test is collapsed to a caret.
        if (trackpadEnding && !session.cursor.awaitingHit) stopTrackpad()
    }

    override fun onSpaceCursorEnd() {
        spaceCursorField = null
        ghostCursor?.hide()
        val session = trackpadSession ?: return
        if (session.cursor.awaitingHit) {
            trackpadEnding = true
            mainHandler.postDelayed(stopTrackpadRunnable, TRACKPAD_END_GRACE_MS)
        } else {
            stopTrackpad()
        }
    }

    private fun stopTrackpad() {
        mainHandler.removeCallbacks(stopTrackpadRunnable)
        ghostCursor?.hide()
        trackpadSession?.stop()
        trackpadSession = null
        trackpadEnding = false
    }

    /** The field's text and our cursor in it, read once per space-drag. */
    private class SpaceCursorField(val text: CharSequence, var cursor: Int)

    private var spaceCursorField: SpaceCursorField? = null

    /**
     * Moves the cursor [steps] characters with a single setSelection. One arrow key per character
     * makes the app process a backlog of key events during a fast drag, so the cursor trails the
     * finger and catches up after it stops. Returns false where the field's text isn't available
     * (terminals, editors that don't extract text), so the caller falls back to arrow keys.
     */
    private fun moveCursorBySelection(ic: InputConnection, steps: Int): Boolean {
        if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL) return false
        val field = spaceCursorField ?: readSpaceCursorField(ic)?.also { spaceCursorField = it } ?: return false
        val target = moveByGraphemes(field.text, field.cursor, steps)
        if (target != field.cursor) {
            ic.setSelection(target, target)
            field.cursor = target
        }
        return true
    }

    private fun readSpaceCursorField(ic: InputConnection): SpaceCursorField? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text ?: return null
        val cursor = extracted.selectionStart
        // Only whole-text extractions with a plain cursor: offsets into a partial extraction, or a
        // selection the arrow keys would collapse, aren't worth special-casing.
        if (extracted.startOffset != 0 || cursor != extracted.selectionEnd || cursor !in 0..text.length) return null
        return SpaceCursorField(text, cursor)
    }

    /**
     * An arrow key the editor can't act on (left/up at the start, right/down at the end) is
     * treated as focus navigation and moves focus to a neighbouring view, losing the cursor. So
     * each key is only sent while there is text on that side of the cursor. Up on the first line
     * still goes to the start of the text, as the editor does it itself.
     */
    private fun sendCursorKeys(ic: InputConnection, count: Int, backwardKey: Int, forwardKey: Int) {
        val backward = count < 0
        val keyCode = if (backward) backwardKey else forwardKey
        for (i in 0 until abs(count)) {
            if (!hasTextToward(ic, backward)) break
            sendDownUpKeyEvents(keyCode)
        }
    }

    private fun hasTextToward(ic: InputConnection, backward: Boolean): Boolean {
        val side = if (backward) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        // Editors that can't report text (null) keep the old behaviour rather than freezing the cursor.
        return side == null || side.isNotEmpty()
    }

    private fun isMultiLineField(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        return type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    }

    // ---- Glide (swipe-to-type) ----

    override fun onGlideTyped(path: List<GesturePoint>, geometry: KeyGeometry) {
        val ic = currentInputConnection ?: return
        val decoder = gestureDecoders[language] ?: return
        if (!composeGlideEnabled) return

        val candidates = decoder.decode(path, geometry)
        if (candidates.isEmpty()) return

        finishComposing(ic)
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        aiFix.invalidate()
        // Back-to-back glides never press space, so the previous glided word ends here.
        if (lastGlideWord != null) learnFinishedWord(ic)
        autoSpacedWord = null
        val capitalize = shifted || capsLock
        val before = ic.getTextBeforeCursor(1, 0)
        val text = GestureCommit.textToCommit(candidates[0].word, before, capitalize)
        ic.commitText(text, 1)

        lastGlideWord = text.trimStart()
        composeSuggestions = candidates.map { it.word }
        composeSelectedSuggestion = candidates[0].word
        consumeShift()
        updateAutoCapitalize(currentInputEditorInfo)
    }

    /**
     * Settles the last glide-committed word on the tapped candidate (replacing it when an
     * alternate was tapped), adds a space, and moves on to next-word predictions, like Gboard.
     */
    private fun onSuggestionSelected(word: String) {
        val ic = currentInputConnection ?: return
        val last = lastGlideWord ?: return
        val styled = GestureCommit.matchCapitalization(last, word)

        // In Vietnamese, onUpdateSelection may have picked the committed word back up as
        // a Telex composing region; release it before editing the surrounding text.
        if (!telex.isEmpty) {
            telex.reset()
            ic.finishComposingText()
        }

        val before = ic.getTextBeforeCursor(last.length, 0)?.toString() ?: return
        clearGlideSuggestions()
        if (!before.endsWith(last)) return
        if (predictionLearning) readTypingContext(ic)?.let { predictions.learn(language, it, styled) }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(last.length, 0)
        commitPickedWord(ic, styled)
        ic.endBatchEdit()
        afterWordPicked(styled)
    }

    // ---- Next-word predictions ----

    /** The word context at the cursor, or null where word suggestions don't apply. */
    private fun readTypingContext(ic: InputConnection): TypingContext? {
        val before = ic.getTextBeforeCursor(PREDICTION_LOOKBEHIND, 0) ?: return null
        return TypingContext.parse(before, complete = before.length < PREDICTION_LOOKBEHIND)
    }

    /** Recomputes the strip's predictions for the text before the cursor. */
    private fun refreshPredictions() {
        val ic = currentInputConnection
        val before = if (predictionsAllowed && ic != null) ic.getTextBeforeCursor(PREDICTION_LOOKBEHIND, 0) else null
        val ctx = before?.let { TypingContext.parse(it, complete = it.length < PREDICTION_LOOKBEHIND) }
        predictionContext = ctx
        // Auto-shift waits for the space after "." (it may be a URL), but a picked word gets
        // that space anyway, so it starts a sentence.
        predictionsStartSentence = before?.lastOrNull()?.let { it in ".?!" } == true &&
            (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        rawPredictions = ctx?.let { predictions.suggest(language, it) } ?: emptyList()
        showPredictions()
    }

    /** Publishes [rawPredictions] cased for the current shift state. */
    private fun showPredictions() {
        val partial = predictionContext?.partial.orEmpty()
        val capitalize = shifted || predictionsStartSentence
        composePredictions = rawPredictions.map {
            NextWordPredictor.applyCase(it, partial, capitalize = capitalize, allCaps = capsLock)
        }
    }

    /** Replaces the word being typed with the tapped prediction, followed by a space. */
    private fun onPredictionSelected(word: String) {
        val ic = currentInputConnection ?: return
        aiFix.invalidate()
        finishComposing(ic)
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        val ctx = readTypingContext(ic) ?: return
        if (predictionLearning) predictions.learn(language, ctx, word)
        ic.beginBatchEdit()
        if (ctx.partial.isNotEmpty()) {
            ic.deleteSurroundingText(ctx.partial.length, 0)
        } else if (GestureCommit.needsLeadingSpace(ic.getTextBeforeCursor(1, 0))) {
            ic.commitText(" ", 1)
        }
        commitPickedWord(ic, word)
        ic.endBatchEdit()
        afterWordPicked(word)
    }

    private fun commitPickedWord(ic: InputConnection, word: String) {
        ic.commitText("$word ", 1)
    }

    private fun afterWordPicked(word: String) {
        autoSpacedWord = word
        consumeShift()
        updateAutoCapitalize(currentInputEditorInfo)
        refreshPredictions()
    }

    /**
     * Learns the word right before the cursor, as the user finishes it with a space,
     * punctuation or enter. Picked predictions are learned when tapped instead.
     */
    private fun learnFinishedWord(ic: InputConnection) {
        if (!predictionLearning) return
        val ctx = readTypingContext(ic) ?: return
        if (ctx.partial.isNotEmpty()) predictions.learn(language, ctx, ctx.partial)
    }

    /**
     * Gboard-style: punctuation typed right after a picked word goes where the
     * automatic space was ("word ." -> "word.").
     */
    private fun absorbAutoSpace(ic: InputConnection, c: Char) {
        val word = autoSpacedWord ?: return
        if (c !in AUTO_SPACE_ABSORBERS) return
        val before = ic.getTextBeforeCursor(word.length + 1, 0)?.toString() ?: return
        if (before == "$word ") ic.deleteSurroundingText(1, 0)
    }

    private fun savePredictionHistory() {
        if (syncHistoryGeneration(prefs())) return
        val saves = predictions.pendingSaves()
        if (saves.isEmpty()) return
        imeScope.launch(Dispatchers.IO) { predictions.write(saves) }
    }

    /**
     * Drops the in-memory history when Settings cleared learned words since we last looked.
     * Returns true when it did.
     */
    private fun syncHistoryGeneration(prefs: android.content.SharedPreferences): Boolean {
        val generation = prefs.getInt(WordPredictions.PREF_HISTORY_GENERATION, 0)
        if (generation == historyGeneration) return false
        historyGeneration = generation
        predictions.forgetInMemory()
        return true
    }

    // ---- Saved-email suggestions ----

    /** Re-reads the email field and offers saved addresses matching what is being typed. */
    private fun refreshEmailSuggestions() {
        val before = if (emailField) snapshotEmailField() else null
        composeEmailSuggestions = if (before == null) emptyList()
        else SavedEmails.suggest(savedEmails, SavedEmails.currentToken(before))
    }

    /** Records the email field's current text; returns the part before the cursor. */
    private fun snapshotEmailField(): String? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(EMAIL_FIELD_LOOKAROUND, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(EMAIL_FIELD_LOOKAROUND, 0)?.toString().orEmpty()
        emailFieldText = before + after
        return before
    }

    /** Replaces the partly typed address before the cursor with the tapped one. */
    private fun onEmailSuggestionSelected(address: String) {
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        val before = ic.getTextBeforeCursor(EMAIL_FIELD_LOOKAROUND, 0)?.toString().orEmpty()
        val token = SavedEmails.currentToken(before)
        ic.beginBatchEdit()
        if (token.isNotEmpty()) ic.deleteSurroundingText(token.length, 0)
        ic.commitText(address, 1)
        ic.endBatchEdit()
        consumeShift()
        composeEmailSuggestions = emptyList()
    }

    /** Saves the addresses left in an email field as it loses focus. */
    private fun learnEmailField() {
        if (!emailField) return
        // The connection is usually still readable here; if not, the last snapshot is used.
        snapshotEmailField()
        emailField = false
        composeEmailSuggestions = emptyList()
        if (!emailLearning) return
        val addresses = SavedEmails.extract(emailFieldText)
        if (addresses.isEmpty()) return
        val now = System.currentTimeMillis()
        val prefs = prefs()
        var updated = SavedEmails.load(prefs)
        addresses.forEach { updated = SavedEmails.record(updated, it, now) }
        SavedEmails.save(prefs, updated)
    }

    /** Records what the user kept of this field's dictation, if the log is on. */
    private fun logDictatedField() {
        if (!dictationLogging) return
        val ic = currentInputConnection
        val kept = ic?.getTextBeforeCursor(DICTATION_LOG_LOOKAROUND, 0)?.toString()?.let { before ->
            before + ic.getTextAfterCursor(DICTATION_LOG_LOOKAROUND, 0)?.toString().orEmpty()
        }
        runCatching { dictationLog.finishField(kept) }
    }

    private fun clearGlideSuggestions() {
        composeSuggestions = emptyList()
        composeSelectedSuggestion = null
        lastGlideWord = null
    }

    private fun updateGlideEnabled() {
        composeGlideEnabled = glidePrefEnabled &&
            layout == KeyboardLayoutType.ALPHA &&
            !passwordField && !directCommit
    }

    override fun onShiftHold(active: Boolean) {
        shiftHeld = active
        if (capsLock) return
        shifted = active
        autoShifted = false
        syncShiftToView()
    }

    private fun handlePrintable(ic: InputConnection, key: Key) {
        aiFix.invalidate()
        var c = key.code.toChar()
        if (c.isLetter() && (shifted || capsLock)) c = c.uppercaseChar()

        // Telex would mangle addresses (e.g. "ee" -> "ê"), so email fields type plain letters.
        val useTelex = language == VoiceLanguage.VIETNAMESE &&
            layout == KeyboardLayoutType.ALPHA &&
            !passwordField && !emailField && !directCommit && c.isLetter()

        if (useTelex) {
            telex.input(c)
            ic.setComposingText(telex.composing, 1)
        } else {
            if (c in AUTO_SWITCH_WORD_ENDS) autoSwitchWord(ic, c.toString())
            finishComposing(ic)
            if (!c.isLetterOrDigit() && !TypingContext.isWordChar(c)) {
                learnFinishedWord(ic)
                absorbAutoSpace(ic, c)
            }
            ic.commitText(c.toString(), 1)
            if (c.isLetter() && language == VoiceLanguage.ENGLISH) englishKeys.append(c) else englishKeys.setLength(0)
            if (!c.isLetter() && c !in AUTO_SWITCH_WORD_ENDS) autoSwitchSession?.forget()
        }
        consumeShift()
    }

    private fun handleDelete(ic: InputConnection) {
        aiFix.invalidate()
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
            englishKeys.setLength(0)
            autoSwitchSession?.forget()
            deleteWordBeforeCursor(ic)
            return
        }
        if (englishKeys.isNotEmpty()) englishKeys.setLength(englishKeys.length - 1)
        autoSwitchSession?.forget()
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0) ?: ""
        ic.deleteSurroundingText(lastGraphemeLength(before).coerceAtLeast(1), 0)
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
        aiFix.invalidate()
        autoSwitchWord(ic, " ")
        finishComposing(ic)
        learnFinishedWord(ic)
        ic.commitText(" ", 1)
        updateAutoCapitalize(currentInputEditorInfo)
    }

    private fun handleEmoji(ic: InputConnection) {
        finishComposing(ic)
        clearGlideSuggestions()
        composeOptionsExpanded = false
        composeClipboardVisible = false
        composeEmojiRecents = EmojiRecents.load(prefs())
        composeEmojiVisible = true
    }

    private fun prefs() = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)

    private fun hideEmoji() {
        composeEmojiVisible = false
    }

    private fun commitEmoji(emoji: String) {
        aiFix.invalidate()
        val ic = currentInputConnection ?: return
        finishComposing(ic)
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        ic.commitText(emoji, 1)
        composeEmojiRecents = EmojiRecents.record(composeEmojiRecents, emoji)
        EmojiRecents.save(prefs(), composeEmojiRecents)
    }

    private fun handleEnter(ic: InputConnection) {
        aiFix.invalidate()
        autoSwitchWord(ic, separator = null)
        finishComposing(ic)
        learnFinishedWord(ic)
        val info = currentInputEditorInfo
        when (val enter = EnterAction.resolve(info)) {
            is EnterAction.Perform -> ic.performEditorAction(enter.actionId)
            EnterAction.Newline -> {
                ic.commitText("\n", 1)
                updateAutoCapitalize(info)
            }
            EnterAction.KeyEvent -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                updateAutoCapitalize(info)
            }
        }
    }

    // ---- Shift / caps ----

    private fun cycleShift() {
        when {
            capsLock -> { capsLock = false; shifted = false }
            // An auto-capitalised shift is only a suggestion; tapping Shift declines it.
            shifted && autoShifted -> shifted = false
            shifted -> { capsLock = true; shifted = false }
            else -> shifted = true
        }
        autoShifted = false
        syncShiftToView()
    }

    private fun consumeShift() {
        if (shifted && !capsLock && !shiftHeld) {
            shifted = false
            autoShifted = false
            syncShiftToView()
        }
    }

    private fun updateAutoCapitalize(info: EditorInfo?) {
        if (capsLock || shiftHeld || passwordField || layout != KeyboardLayoutType.ALPHA) return
        info ?: return
        val capSentences = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        val capWords = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0
        val capChars = info.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0
        val isTextField = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        if (!isTextField || (!capSentences && !capWords && !capChars)) return

        val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val trimmed = before.trimEnd()
        // A period only ends a sentence when followed by whitespace; otherwise it is likely part of
        // a URL/filename (e.g. "example.com") and the next character must stay lowercase.
        val hasTrailingSpace = before != trimmed
        val endsSentence = trimmed.isEmpty() ||
            trimmed.endsWith("?") || trimmed.endsWith("!") ||
            (trimmed.endsWith(".") && hasTrailingSpace)
        val atStart = before.isEmpty() || before.endsWith("\n") || endsSentence
        val newShift = capChars || atStart || (capWords && (before.isEmpty() || before.endsWith(" ")))
        if (newShift != shifted) {
            shifted = newShift
            autoShifted = newShift
            syncShiftToView()
        }
    }

    private fun syncShiftToView() {
        composeShifted = shifted
        composeCapsLock = capsLock
        showPredictions()
    }

    private fun setMicListening(active: Boolean) {
        composeMicActive = active
    }

    // ---- Layout & language ----

    private fun switchLayout(target: KeyboardLayoutType) {
        layout = target
        applyLayout()
    }

    private fun chooseLayoutForField(info: EditorInfo) {
        layout = KeyboardLayoutSelector.forInputType(info.inputType)
    }

    private fun applyLayout() {
        val targetKeyboard = when (layout) {
            KeyboardLayoutType.ALPHA -> QwertyKeyboardLayout.create(metrics.showDedicatedNumberRow)
            KeyboardLayoutType.SYMBOLS -> SymbolKeyboardLayout.primary()
            KeyboardLayoutType.SYMBOLS_ALT -> SymbolKeyboardLayout.alternate()
            KeyboardLayoutType.NUMERIC -> NumericKeyboardLayout.create()
        }
        composeKeyboard = targetKeyboard
        composeSpaceLabel = languageLabel()
        syncShiftToView()
        updateGlideEnabled()
    }

    private fun toggleLanguage() {
        currentInputConnection?.let { finishComposing(it) }
        if (voice.isListening) {
            voice.stop()
            setMicListening(false)
            hideStatus()
        }
        // The user chose the language: auto switch stays out of the way for a moment.
        englishKeys.setLength(0)
        autoSwitchSession?.forget()
        lastAutoFix = null
        autoSwitchPausedUntil = SystemClock.uptimeMillis() + MANUAL_SWITCH_PAUSE_MS

        setLanguage(
            if (language == VoiceLanguage.ENGLISH) VoiceLanguage.VIETNAMESE else VoiceLanguage.ENGLISH
        )
    }

    private fun setLanguage(target: VoiceLanguage) {
        language = target
        composeSpaceLabel = languageLabel()
        clearGlideSuggestions()
        updateGlideEnabled()
        refreshPredictions()
        prefs().edit().putString("language", language.name).apply()
    }

    // ---- Auto switch ----

    /**
     * At a word's end, replaces a word typed in the wrong language and switches the keyboard to
     * the other one. [separator] is the key ending the word, which a Backspace right after takes
     * back along with the fix; null (enter) leaves nothing to undo.
     */
    private fun autoSwitchWord(ic: InputConnection, separator: String?) {
        val session = autoSwitchSession
        val keys = englishKeys.toString()
        englishKeys.setLength(0)
        val kept = keptWord
        keptWord = null
        if (session == null || !autoSwitchAllowed || layout != KeyboardLayoutType.ALPHA ||
            SystemClock.uptimeMillis() < autoSwitchPausedUntil) {
            session?.forget()
            return
        }
        val spaceAfter = separator == " "

        if (!telex.isEmpty) {
            val original = telex.composing
            if (original == kept) {
                session.forget()
                return
            }
            val outcome = session.finishVietnamese(telex.typedKeys, telex.rawKeys, telex.telexForm, original, spaceAfter)
            if (outcome.word == original && outcome.earlier.isEmpty()) return
            if (outcome.word != original) ic.setComposingText(outcome.word, 1)
            ic.finishComposingText()
            telex.reset()
            applyOutcome(ic, original, outcome, separator)
        } else if (language == VoiceLanguage.ENGLISH && keys.isNotEmpty() && keys != kept) {
            val before = ic.getTextBeforeCursor(keys.length + AUTO_SWITCH_LOOKBEHIND, 0)?.toString()
            if (before == null || !before.endsWith(keys)) {
                session.forget()
                return
            }
            val head = before.dropLast(keys.length)
            // Only a whole word: after . _ - / or a digit it is part of a name or a path (obj.dir).
            if (head.isNotEmpty() && !head.last().isWhitespace() && head.last() !in AUTO_SWITCH_WORD_STARTS) {
                session.forget()
                return
            }
            val previous = head.trimEnd().takeLastWhile { it.isLetter() }.ifEmpty { null }
            val outcome = session.finishEnglish(keys, previous, spaceAfter)
            if (outcome.word == keys && outcome.earlier.isEmpty()) return
            if (outcome.word != keys) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(keys.length, 0)
                ic.commitText(outcome.word, 1)
                ic.endBatchEdit()
            }
            applyOutcome(ic, keys, outcome, separator)
        } else {
            session.forget()
        }
    }

    /** Rewrites the earlier words of [outcome] and switches language; the current word is in place. */
    private fun applyOutcome(ic: InputConnection, original: String, outcome: AutoSwitchSession.Outcome, separator: String?) {
        val rewrote = outcome.earlier.isNotEmpty() && rewriteEarlierWords(ic, outcome)
        val to = when (outcome.switchToVietnamese) {
            true -> VoiceLanguage.VIETNAMESE
            false -> VoiceLanguage.ENGLISH
            null -> null
        }
        // Backspace undoes a fix of this word alone; a fix that reached back is kept.
        if (separator != null && !rewrote && original != outcome.word) {
            lastAutoFix = AutoFix(original, outcome.word, separator, language)
        }
        if (to != null && to != language) setLanguage(to)
    }

    /**
     * Replaces the words before the current one as [outcome] says, if the text still shows them
     * joined by single spaces. Returns whether it did.
     */
    private fun rewriteEarlierWords(ic: InputConnection, outcome: AutoSwitchSession.Outcome): Boolean {
        val before = ic.getTextBeforeCursor(AUTO_SWITCH_LOOKBEHIND * 2, 0)?.toString() ?: return false
        if (!before.endsWith(outcome.word)) return false
        // Words before the current one, nearest first, as on screen.
        val words = ArrayList<String>()
        var end = before.length - outcome.word.length
        val reach = outcome.earlier.maxOf { it.wordsBack }
        while (words.size < reach) {
            if (end == 0 || before[end - 1] != ' ') return false
            var start = end - 1
            while (start > 0 && !before[start - 1].isWhitespace()) start--
            if (start == end - 1) return false
            words += before.substring(start, end - 1)
            end = start
        }
        val replaced = words.toMutableList()
        for (rewrite in outcome.earlier) {
            val word = words[rewrite.wordsBack - 1]
            // An opening bracket or quote may sit before the word: "(thí".
            if (!word.endsWith(rewrite.from) || word.dropLast(rewrite.from.length).any { it !in AUTO_SWITCH_WORD_STARTS }) return false
            replaced[rewrite.wordsBack - 1] = word.dropLast(rewrite.from.length) + rewrite.to
        }
        val oldText = words.reversed().joinToString(" ") + " " + outcome.word
        val newText = replaced.reversed().joinToString(" ") + " " + outcome.word
        ic.beginBatchEdit()
        ic.deleteSurroundingText(oldText.length, 0)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
        return true
    }

    /** Backspace right after a fix: puts the word back as it was, in its language. */
    private fun undoAutoFix(ic: InputConnection, fix: AutoFix?): Boolean {
        if (fix == null || !telex.isEmpty) return false
        val expected = fix.replacement + fix.separator
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(fix.original, 1)
        ic.endBatchEdit()
        keptWord = fix.original
        setLanguage(fix.from)
        // Typing on continues the restored word ("cos" + t → "cost").
        if (fix.from == VoiceLanguage.ENGLISH) englishKeys.append(fix.original)
        return true
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        currentInputConnection?.let { finishComposing(it) }
        val tag = newSubtype?.languageTag?.takeIf { it.isNotEmpty() }
            ?: @Suppress("DEPRECATION") newSubtype?.locale ?: ""
        language = if (tag.startsWith("vi")) VoiceLanguage.VIETNAMESE else VoiceLanguage.ENGLISH
        composeSpaceLabel = languageLabel()
        clearGlideSuggestions()
        updateGlideEnabled()
        refreshPredictions()
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
        if (voice.isListening) {
            setMicListening(true)
            showStatus(getString(language.listeningStatusRes()))
        }
    }

    // ---- AI fix ----

    private fun aiPrefs() = getSharedPreferences(AiSettings.PREFS_NAME, MODE_PRIVATE)

    private fun handleAiFix() {
        if (passwordField) {
            showTransientStatus(getString(R.string.ai_password_disabled))
            return
        }
        // Fix what has been dictated so far rather than racing new segments into the field.
        if (voice.isListening) {
            voice.stop()
            setMicListening(false)
        }
        clearGlideSuggestions()
        aiFix.onButton()
        if (aiFix.state == AiFixController.State.WORKING) {
            statusHideJob?.cancel()
            showStatus(getString(R.string.ai_status_working))
        } else if (composeVoiceStatus == getString(R.string.ai_status_working)) {
            hideStatus() // cancelled
        }
    }

    private fun onAiFixOutcome(outcome: AiFixController.Outcome) {
        val msg = when (outcome) {
            AiFixController.Outcome.Fixed -> getString(R.string.ai_status_fixed)
            AiFixController.Outcome.Unchanged -> getString(R.string.ai_status_unchanged)
            AiFixController.Outcome.Empty -> getString(R.string.ai_status_empty)
            AiFixController.Outcome.Stale -> getString(R.string.ai_status_stale)
            AiFixController.Outcome.Undone -> getString(R.string.ai_status_undone)
            AiFixController.Outcome.UndoUnavailable -> getString(R.string.ai_status_undo_unavailable)
            is AiFixController.Outcome.Failed -> outcome.error.describe(this)
        }
        showTransientStatus(msg, if (outcome is AiFixController.Outcome.Failed) ERROR_STATUS_MS else STATUS_MS)
    }

    /** Shows [text] in the status slot, then clears it unless something replaced it meanwhile. */
    private fun showTransientStatus(text: String, durationMs: Long = STATUS_MS) {
        showStatus(text)
        statusHideJob?.cancel()
        statusHideJob = imeScope.launch {
            delay(durationMs)
            if (composeVoiceStatus == text) hideStatus()
        }
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
            showStatus(getString(language.listeningStatusRes()))
            currentInputConnection?.setComposingText(text, 1)
        }

        override fun onFinal(text: String, confidence: Float, join: SegmentJoin) {
            val ic = currentInputConnection ?: return
            aiFix.invalidate()
            ic.finishComposingText()
            val action = commandMatcher.match(text, confidence)
            if (dictationLogging && language == VoiceLanguage.VIETNAMESE && action is VoiceAction.CommitText) {
                dictationLog.segment(action.text, join)
            }
            if (commandHandler.handle(action, text, join) == VoiceCommandHandler.Result.STOP_LISTENING) {
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
        composeEmojiVisible = false
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
        /** How long after lift-off to wait for a pending hit test before giving up. */
        const val TRACKPAD_END_GRACE_MS = 300L
        const val WORD_DELETE_LOOKBEHIND = 64
        /** Long enough for the longest ZWJ emoji sequences (family/flag tags run ~14 chars). */
        const val GRAPHEME_LOOKBEHIND = 32
        const val SELECTION_SYNC_DEBOUNCE_MS = 75L
        const val STATUS_MS = 2500L
        const val ERROR_STATUS_MS = 5000L
        const val EMAIL_FIELD_LOOKAROUND = 256
        const val DICTATION_LOG_LOOKAROUND = 4000
        /** Enough for the two words of context the predictor uses, plus the word in progress. */
        const val PREDICTION_LOOKBEHIND = 96
        /** Keys besides space and enter that end a word for auto switch. */
        const val AUTO_SWITCH_WORD_ENDS = ",.;:!?)\""
        /** Characters a word may follow and still be checked (besides whitespace). */
        const val AUTO_SWITCH_WORD_STARTS = "([\"'"
        /** Enough to see the word before the one being checked. */
        const val AUTO_SWITCH_LOOKBEHIND = 32
        const val MANUAL_SWITCH_PAUSE_MS = 5000L
        /** Punctuation that sits right after a word, taking the place of an automatic space. */
        const val AUTO_SPACE_ABSORBERS = ".,?!;:)"
        val GLIDE_DICTIONARY_ASSETS = mapOf(
            VoiceLanguage.ENGLISH to "dictionaries/en.txt",
            VoiceLanguage.VIETNAMESE to "dictionaries/vi.txt",
        )
    }
}
