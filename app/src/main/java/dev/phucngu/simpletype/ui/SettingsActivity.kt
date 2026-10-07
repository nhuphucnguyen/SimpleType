package dev.phucngu.simpletype.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.HapticPlayer
import dev.phucngu.simpletype.ime.keyboard.layout.QwertyKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.CursorSpeed
import dev.phucngu.simpletype.ime.KeyboardMetrics
import dev.phucngu.simpletype.ime.LatinKeyboard
import dev.phucngu.simpletype.ime.LatinKeyboardListener
import dev.phucngu.simpletype.ime.LatinKeyboardView
import dev.phucngu.simpletype.ui.theme.SimpleTypeTheme
import dev.phucngu.simpletype.voice.ModelManager
import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlin.concurrent.thread

class SettingsActivity : ComponentActivity() {

    private val models by lazy { ModelManager(this) }
    private val haptics by lazy { HapticPlayer(this) }

    private val imeEnabled = mutableStateOf(false)
    private val imeSelected = mutableStateOf(false)
    private val enModelText = mutableStateOf("")
    private val enModelEnabled = mutableStateOf(true)
    private val viModelText = mutableStateOf("")
    private val viModelEnabled = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimpleTypeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        imeEnabled = imeEnabled.value,
                        imeSelected = imeSelected.value,
                        enModelText = enModelText.value,
                        enModelEnabled = enModelEnabled.value,
                        viModelText = viModelText.value,
                        viModelEnabled = viModelEnabled.value,
                        onDownloadClick = { lang -> downloadModel(lang) },
                        onEnableClick = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onSelectClick = {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showInputMethodPicker()
                        },
                        haptics = haptics
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateImeStatus()
        updateModelStatus()
    }

    // The input-method picker is a dialog, so the activity isn't resumed after choosing a
    // keyboard there; refresh when focus returns instead.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateImeStatus()
    }

    private fun updateImeStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imeEnabled.value = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        imeSelected.value = current?.startsWith("$packageName/") == true
    }

    private fun updateModelStatus() {
        if (models.isInstalled(VoiceLanguage.ENGLISH)) {
            enModelText.value = getString(R.string.model_installed)
            enModelEnabled.value = false
        } else {
            enModelText.value = getString(R.string.action_download_en)
            enModelEnabled.value = true
        }

        if (models.isInstalled(VoiceLanguage.VIETNAMESE)) {
            viModelText.value = getString(R.string.model_installed)
            viModelEnabled.value = false
        } else {
            viModelText.value = getString(R.string.action_download_vi)
            viModelEnabled.value = true
        }
    }

    private fun downloadModel(language: VoiceLanguage) {
        if (language == VoiceLanguage.ENGLISH) {
            enModelEnabled.value = false
            enModelText.value = getString(R.string.model_downloading, 0)
        } else {
            viModelEnabled.value = false
            viModelText.value = getString(R.string.model_downloading, 0)
        }

        thread {
            try {
                models.download(language) { percent ->
                    runOnUiThread {
                        if (language == VoiceLanguage.ENGLISH) {
                            enModelText.value = getString(R.string.model_downloading, percent)
                        } else {
                            viModelText.value = getString(R.string.model_downloading, percent)
                        }
                    }
                }
                runOnUiThread {
                    if (language == VoiceLanguage.ENGLISH) {
                        enModelText.value = getString(R.string.model_installed)
                        enModelEnabled.value = false
                    } else {
                        viModelText.value = getString(R.string.model_installed)
                        viModelEnabled.value = false
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (language == VoiceLanguage.ENGLISH) {
                        enModelText.value = getString(R.string.model_download_failed, t.message ?: "error")
                        enModelEnabled.value = true
                    } else {
                        viModelText.value = getString(R.string.model_download_failed, t.message ?: "error")
                        viModelEnabled.value = true
                    }
                }
            }
        }
    }
}

// ----- Reusable M3 Expressive building blocks ----------------------------------------------

/** A tonal-surface card with the expressive 28dp corner. */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Small primary-container value badge shown next to a slider label. */
@Composable
private fun ValueChip(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun M3Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null
    )
}

/**
 * Lays [content] out at [targetWidth] (e.g. the real screen width) and draws it uniformly scaled
 * down to the available width, so a miniature keeps the true width/height ratio. Touches are
 * mapped through the scale by the graphics layer.
 */
@Composable
private fun ScaledToWidth(targetWidth: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val target = targetWidth.roundToPx()
        val scale = (constraints.maxWidth.toFloat() / target).coerceAtMost(1f)
        val placeable = measurables.first().measure(Constraints.fixedWidth(target))
        val x = ((constraints.maxWidth - target * scale) / 2f).roundToInt()
        layout(constraints.maxWidth, (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(x, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            ValueChip(valueLabel)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        M3Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VoiceModelChip(
    label: String,
    statusText: String,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            label,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            // Installed → checkmark.
            installed -> Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(20.dp)
            )
            // Idle and downloadable → download affordance.
            enabled -> Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(20.dp)
            )
            // In progress (download running) → live status text.
            else -> Text(
                statusText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = onContainer.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ----- Settings screen ----------------------------------------------------------------------

private enum class SettingsPage { HOME, LAYOUT, TYPING, VOICE }

/** Which hint (if any) is drawn in the corner of letter keys; maps onto two [KeyboardMetrics] flags. */
private enum class KeyHints { NONE, NUMBERS, SYMBOLS }

/** Tappable home-screen row that opens a settings sub-page. */
@Composable
private fun NavRow(iconRes: Int, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
            Text(
                summary,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Sub-page frame: back button + title, an optional [pinned] area that stays put (e.g. the
 * keyboard preview), then the scrolling [content].
 */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.settings_back)
                )
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
        if (pinned != null) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                content = pinned
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                content()
                Spacer(modifier = Modifier.height(24.dp))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    enModelText: String,
    enModelEnabled: Boolean,
    viModelText: String,
    viModelEnabled: Boolean,
    onDownloadClick: (VoiceLanguage) -> Unit,
    onEnableClick: () -> Unit,
    onSelectClick: () -> Unit,
    haptics: HapticPlayer
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("simpletype_prefs", Context.MODE_PRIVATE) }
    var currentMetrics by remember { mutableStateOf(KeyboardMetrics.load(prefs)) }

    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean(LatinKeyboardView.PREF_HAPTIC, true)) }
    var glideEnabled by remember { mutableStateOf(prefs.getBoolean(LatinKeyboardView.PREF_GLIDE, true)) }
    var hapticLevel by remember {
        mutableStateOf(
            (Math.round(prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT) * 5 / 100f) - 1).coerceIn(0, 4)
        )
    }
    val hapticLevels = stringArrayResource(R.array.haptic_levels)
    var cursorSpeed by remember {
        mutableStateOf(prefs.getFloat(LatinKeyboardView.PREF_CURSOR_SPEED, CursorSpeed.DEFAULT))
    }
    val installedText = stringResource(R.string.model_installed)

    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    BackHandler(enabled = page != SettingsPage.HOME) { page = SettingsPage.HOME }

    fun applyMetrics(
        rowHeightDp: Float = currentMetrics.rowHeightDp,
        gapHorizontalDp: Float = currentMetrics.gapHorizontalDp,
        gapVerticalDp: Float = currentMetrics.gapVerticalDp,
        bottomPaddingDp: Float = currentMetrics.bottomPaddingDp,
        showNumberRow: Boolean = currentMetrics.showNumberRow,
        showDedicatedNumberRow: Boolean = currentMetrics.showDedicatedNumberRow,
        showSymbolHints: Boolean = currentMetrics.showSymbolHints,
    ) {
        val m = KeyboardMetrics.of(
            rowHeightDp, gapHorizontalDp, gapVerticalDp, bottomPaddingDp,
            showNumberRow, showDedicatedNumberRow, showSymbolHints
        )
        currentMetrics = m
        KeyboardMetrics.save(prefs, m)
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // Sub-pages slide in from the right; going back slides home in from the left.
            val forward = targetState != SettingsPage.HOME
            (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
        },
        label = "settings page",
    ) { current ->
        when (current) {
            SettingsPage.HOME -> HomePage(
                imeEnabled = imeEnabled,
                imeSelected = imeSelected,
                enInstalled = enModelText == installedText,
                viInstalled = viModelText == installedText,
                onEnableClick = onEnableClick,
                onSelectClick = onSelectClick,
                onOpen = { page = it },
            )

            SettingsPage.LAYOUT -> SubPage(
                title = stringResource(R.string.settings_page_layout),
                onBack = { page = SettingsPage.HOME },
                pinned = { KeyboardPreview(currentMetrics) },
            ) {
                SettingsCard {
                    LabeledSlider(
                        label = stringResource(R.string.size_label_key_height),
                        valueLabel = stringResource(R.string.size_value_dp, currentMetrics.rowHeightDp.toInt()),
                        value = currentMetrics.rowHeightDp,
                        onValueChange = { applyMetrics(rowHeightDp = it) },
                        valueRange = KeyboardMetrics.ROW_HEIGHT_MIN..KeyboardMetrics.ROW_HEIGHT_MAX,
                    )
                    LabeledSlider(
                        label = stringResource(R.string.size_label_gap_v),
                        valueLabel = stringResource(R.string.size_value_dp, currentMetrics.gapVerticalDp.toInt()),
                        value = currentMetrics.gapVerticalDp,
                        onValueChange = { applyMetrics(gapVerticalDp = it) },
                        valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
                    )
                    LabeledSlider(
                        label = stringResource(R.string.size_label_gap_h),
                        valueLabel = stringResource(R.string.size_value_dp, currentMetrics.gapHorizontalDp.toInt()),
                        value = currentMetrics.gapHorizontalDp,
                        onValueChange = { applyMetrics(gapHorizontalDp = it) },
                        valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
                    )
                    LabeledSlider(
                        label = stringResource(R.string.size_label_lift),
                        valueLabel = stringResource(R.string.size_value_dp, currentMetrics.bottomPaddingDp.toInt()),
                        value = currentMetrics.bottomPaddingDp,
                        onValueChange = { applyMetrics(bottomPaddingDp = it) },
                        valueRange = KeyboardMetrics.BOTTOM_PAD_MIN..KeyboardMetrics.BOTTOM_PAD_MAX,
                    )
                }

                SettingsCard {
                    Column {
                        Text(stringResource(R.string.key_hints_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.key_hints_desc),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    val hints = when {
                        currentMetrics.showSymbolHints -> KeyHints.SYMBOLS
                        currentMetrics.showNumberRow -> KeyHints.NUMBERS
                        else -> KeyHints.NONE
                    }
                    val hintLabels = listOf(
                        KeyHints.NONE to stringResource(R.string.key_hints_none),
                        KeyHints.NUMBERS to stringResource(R.string.key_hints_numbers),
                        KeyHints.SYMBOLS to stringResource(R.string.key_hints_symbols),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        hintLabels.forEachIndexed { index, (option, label) ->
                            SegmentedButton(
                                selected = hints == option,
                                onClick = {
                                    applyMetrics(
                                        showNumberRow = option == KeyHints.NUMBERS,
                                        showSymbolHints = option == KeyHints.SYMBOLS,
                                    )
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, hintLabels.size),
                            ) { Text(label) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow(
                        title = stringResource(R.string.size_dedicated_number_row),
                        desc = stringResource(R.string.size_dedicated_number_row_desc),
                        checked = currentMetrics.showDedicatedNumberRow,
                        onCheckedChange = { checked -> applyMetrics(showDedicatedNumberRow = checked) },
                    )
                }

                PillButton(
                    text = stringResource(R.string.reset_layout),
                    onClick = {
                        applyMetrics(
                            rowHeightDp = KeyboardMetrics.DEFAULT.rowHeightDp,
                            gapHorizontalDp = KeyboardMetrics.DEFAULT.gapHorizontalDp,
                            gapVerticalDp = KeyboardMetrics.DEFAULT.gapVerticalDp,
                            bottomPaddingDp = KeyboardMetrics.DEFAULT.bottomPaddingDp,
                            showNumberRow = KeyboardMetrics.DEFAULT.showNumberRow,
                            showDedicatedNumberRow = KeyboardMetrics.DEFAULT.showDedicatedNumberRow,
                            showSymbolHints = KeyboardMetrics.DEFAULT.showSymbolHints,
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsPage.TYPING -> SubPage(
                title = stringResource(R.string.settings_page_typing),
                onBack = { page = SettingsPage.HOME },
            ) {
                SettingsCard {
                    ToggleRow(
                        title = stringResource(R.string.glide_typing),
                        desc = stringResource(R.string.glide_typing_desc),
                        checked = glideEnabled,
                        onCheckedChange = { checked ->
                            glideEnabled = checked
                            prefs.edit().putBoolean(LatinKeyboardView.PREF_GLIDE, checked).apply()
                        },
                    )
                }

                SettingsCard {
                    Column {
                        Text(stringResource(R.string.cursor_speed_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.cursor_speed_desc),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    LabeledSlider(
                        label = stringResource(R.string.cursor_speed_label),
                        valueLabel = stringResource(
                            R.string.cursor_speed_value,
                            // Quarter steps are exact in Float: show "1", "1.5", "2.25" (no trailing zeros).
                            if (cursorSpeed % 1f == 0f) cursorSpeed.toInt().toString() else cursorSpeed.toString(),
                        ),
                        value = cursorSpeed,
                        onValueChange = {
                            // Snap to the slider's increments so stored values stay tidy (e.g. 1.25, not 1.2499).
                            val snapped = Math.round(it / CursorSpeed.INCREMENT) * CursorSpeed.INCREMENT
                            cursorSpeed = snapped
                            prefs.edit().putFloat(LatinKeyboardView.PREF_CURSOR_SPEED, snapped).apply()
                        },
                        valueRange = CursorSpeed.MIN..CursorSpeed.MAX,
                        steps = ((CursorSpeed.MAX - CursorSpeed.MIN) / CursorSpeed.INCREMENT).toInt() - 1,
                    )
                }

                SettingsCard {
                    ToggleRow(
                        title = stringResource(R.string.size_haptic),
                        desc = stringResource(R.string.size_haptic_desc),
                        checked = hapticEnabled,
                        onCheckedChange = { checked ->
                            hapticEnabled = checked
                            prefs.edit().putBoolean(LatinKeyboardView.PREF_HAPTIC, checked).apply()
                            if (checked) haptics.tap((hapticLevel + 1) * 100 / 5 / 100f)
                        },
                    )
                    if (hapticEnabled) {
                        val strengthName = hapticLevels.getOrNull(hapticLevel) ?: stringResource(R.string.haptic_level_mid)
                        LabeledSlider(
                            label = stringResource(R.string.vibration_strength),
                            valueLabel = strengthName,
                            value = hapticLevel.toFloat(),
                            onValueChange = {
                                val newLevel = it.toInt()
                                hapticLevel = newLevel
                                val percent = (newLevel + 1) * 100 / 5
                                prefs.edit().putInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, percent).apply()
                                haptics.tap(percent / 100f)
                            },
                            valueRange = 0f..4f,
                            steps = 3,
                        )
                    }
                }

                PillButton(
                    text = stringResource(R.string.reset_typing),
                    onClick = {
                        glideEnabled = true
                        hapticEnabled = true
                        hapticLevel = 2
                        cursorSpeed = CursorSpeed.DEFAULT
                        prefs.edit()
                            .putBoolean(LatinKeyboardView.PREF_GLIDE, true)
                            .putBoolean(LatinKeyboardView.PREF_HAPTIC, true)
                            .putInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT)
                            .putFloat(LatinKeyboardView.PREF_CURSOR_SPEED, CursorSpeed.DEFAULT)
                            .apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsPage.VOICE -> SubPage(
                title = stringResource(R.string.settings_page_voice),
                onBack = { page = SettingsPage.HOME },
            ) {
                SettingsCard {
                    Column {
                        Text(stringResource(R.string.voice_models_title), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.voice_models_intro),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceModelChip(
                            label = stringResource(R.string.subtype_en),
                            statusText = enModelText,
                            installed = enModelText == installedText,
                            enabled = enModelEnabled,
                            onClick = { onDownloadClick(VoiceLanguage.ENGLISH) }
                        )
                        VoiceModelChip(
                            label = stringResource(R.string.subtype_vi),
                            statusText = viModelText,
                            installed = viModelText == installedText,
                            enabled = viModelEnabled,
                            onClick = { onDownloadClick(VoiceLanguage.VIETNAMESE) }
                        )
                    }
                }
            }
        }
    }
}

/** Live keyboard miniature, rendered at true screen width and scaled down to keep its proportions. */
@Composable
private fun KeyboardPreview(metrics: KeyboardMetrics) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colorResource(R.color.kb_background))
            .padding(8.dp)
    ) {
        // The real keyboard spans the full screen width; render at that width and scale
        // down, otherwise the fixed-dp key height makes preview keys look too narrow.
        ScaledToWidth(LocalConfiguration.current.screenWidthDp.dp) {
            LatinKeyboard(
                keyboard = QwertyKeyboardLayout.create(metrics.showDedicatedNumberRow),
                metrics = metrics,
                spaceLabel = stringResource(R.string.subtype_en),
                shifted = false,
                capsLock = false,
                listener = object : LatinKeyboardListener {
                    override fun onKey(key: Key) {}
                    override fun onKeyRepeat(key: Key) {}
                    override fun onSpaceSwipe(direction: Int) {}
                    override fun onShiftHold(active: Boolean) {}
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HomePage(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    enInstalled: Boolean,
    viInstalled: Boolean,
    onEnableClick: () -> Unit,
    onSelectClick: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    stringResource(R.string.settings_eyebrow),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.settings_brand),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "S",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Setup banner: only until SimpleType is both enabled and the active keyboard.
        if (!imeEnabled || !imeSelected) {
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        stringResource(if (imeEnabled) R.string.status_not_selected else R.string.status_not_enabled),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            if (imeEnabled) R.string.settings_status_not_selected_sub
                            else R.string.settings_status_inactive_sub
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        text = stringResource(R.string.settings_input_settings),
                        onClick = onEnableClick,
                        modifier = Modifier.weight(1f)
                    )
                    PillButton(
                        text = stringResource(R.string.action_select),
                        onClick = onSelectClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Try it out: at the top so the keyboard opens below it, not over it.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.action_try),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
            var tryText by rememberSaveable { mutableStateOf("") }
            TextField(
                value = tryText,
                onValueChange = { tryText = it },
                placeholder = { Text(stringResource(R.string.try_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        NavRow(
            iconRes = R.drawable.ic_keyboard,
            title = stringResource(R.string.settings_page_layout),
            summary = stringResource(R.string.settings_page_layout_sub),
            onClick = { onOpen(SettingsPage.LAYOUT) },
        )
        NavRow(
            iconRes = R.drawable.ic_touch,
            title = stringResource(R.string.settings_page_typing),
            summary = stringResource(R.string.settings_page_typing_sub),
            onClick = { onOpen(SettingsPage.TYPING) },
        )
        val installed = listOfNotNull(
            stringResource(R.string.subtype_en).takeIf { enInstalled },
            stringResource(R.string.subtype_vi).takeIf { viInstalled },
        )
        NavRow(
            iconRes = R.drawable.ic_kb_mic,
            title = stringResource(R.string.settings_page_voice),
            summary = if (installed.isEmpty()) stringResource(R.string.voice_summary_none)
            else stringResource(R.string.voice_summary_installed, installed.joinToString(", ")),
            onClick = { onOpen(SettingsPage.VOICE) },
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}
