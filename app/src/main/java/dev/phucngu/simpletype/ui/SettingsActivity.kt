package dev.phucngu.simpletype.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    private val imeEnabledStatus = mutableStateOf("")
    private val imeEnabled = mutableStateOf(false)
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
                        imeEnabledStatus = imeEnabledStatus.value,
                        imeEnabled = imeEnabled.value,
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

    private fun updateImeStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        imeEnabled.value = isEnabled
        imeEnabledStatus.value = getString(
            if (isEnabled) R.string.status_enabled else R.string.status_not_enabled
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    imeEnabledStatus: String,
    imeEnabled: Boolean,
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
    var hapticLevel by remember {
        mutableStateOf(
            (Math.round(prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT) * 5 / 100f) - 1).coerceIn(0, 4)
        )
    }
    val hapticLevels = stringArrayResource(R.array.haptic_levels)
    val installedText = stringResource(R.string.model_installed)

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

        // Status + setup card
        SettingsCard {
            // Active banner
            val bannerBg = if (imeEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
            val bannerFg = if (imeEnabled) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bannerBg)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(imeEnabledStatus, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = bannerFg)
                    Text(
                        stringResource(
                            if (imeEnabled) R.string.settings_status_active_sub
                            else R.string.settings_status_inactive_sub
                        ),
                        fontSize = 13.sp,
                        color = bannerFg.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            // Action pills
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

            // Voice models
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.voice_models_offline),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp)
                )
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

        // Keyboard size card
        SettingsCard {
            Column {
                Text(stringResource(R.string.size_title), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.size_intro),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            LabeledSlider(
                label = stringResource(R.string.size_label_key_height),
                valueLabel = stringResource(R.string.size_value_dp, currentMetrics.rowHeightDp.toInt()),
                value = currentMetrics.rowHeightDp,
                onValueChange = { applyMetrics(rowHeightDp = it) },
                valueRange = KeyboardMetrics.ROW_HEIGHT_MIN..KeyboardMetrics.ROW_HEIGHT_MAX,
            )
            LabeledSlider(
                label = stringResource(R.string.size_label_lift),
                valueLabel = stringResource(R.string.size_value_dp, currentMetrics.bottomPaddingDp.toInt()),
                value = currentMetrics.bottomPaddingDp,
                onValueChange = { applyMetrics(bottomPaddingDp = it) },
                valueRange = KeyboardMetrics.BOTTOM_PAD_MIN..KeyboardMetrics.BOTTOM_PAD_MAX,
            )
            LabeledSlider(
                label = stringResource(R.string.size_label_gap_h),
                valueLabel = stringResource(R.string.size_value_dp, currentMetrics.gapHorizontalDp.toInt()),
                value = currentMetrics.gapHorizontalDp,
                onValueChange = { applyMetrics(gapHorizontalDp = it) },
                valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
            )
            LabeledSlider(
                label = stringResource(R.string.size_label_gap_v),
                valueLabel = stringResource(R.string.size_value_dp, currentMetrics.gapVerticalDp.toInt()),
                value = currentMetrics.gapVerticalDp,
                onValueChange = { applyMetrics(gapVerticalDp = it) },
                valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
            )
        }

        // Live preview card
        SettingsCard(spacing = 12.dp, padding = PaddingValues(16.dp)) {
            Text(
                stringResource(R.string.size_live_preview),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colorResource(R.color.kb_background))
                    .padding(8.dp)
            ) {
                LatinKeyboard(
                    keyboard = QwertyKeyboardLayout.create(currentMetrics.showDedicatedNumberRow),
                    metrics = currentMetrics,
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

        // Typing options card
        SettingsCard(spacing = 0.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
            Text(
                stringResource(R.string.size_typing_options),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                ToggleRow(
                    title = stringResource(R.string.size_number_row),
                    desc = stringResource(R.string.size_number_row_desc),
                    checked = currentMetrics.showNumberRow,
                    onCheckedChange = { checked ->
                        applyMetrics(
                            showNumberRow = checked,
                            showSymbolHints = if (checked) false else currentMetrics.showSymbolHints
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    title = stringResource(R.string.size_symbol_hints),
                    desc = stringResource(R.string.size_symbol_hints_desc),
                    checked = currentMetrics.showSymbolHints,
                    onCheckedChange = { checked ->
                        applyMetrics(
                            showNumberRow = if (checked) false else currentMetrics.showNumberRow,
                            showSymbolHints = checked
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    title = stringResource(R.string.size_dedicated_number_row),
                    desc = stringResource(R.string.size_dedicated_number_row_desc),
                    checked = currentMetrics.showDedicatedNumberRow,
                    onCheckedChange = { checked -> applyMetrics(showDedicatedNumberRow = checked) },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Haptics card
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.size_haptic), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.size_haptic_desc),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                M3Switch(
                    checked = hapticEnabled,
                    onCheckedChange = { checked ->
                        hapticEnabled = checked
                        prefs.edit().putBoolean(LatinKeyboardView.PREF_HAPTIC, checked).apply()
                        if (checked) haptics.tap((hapticLevel + 1) * 100 / 5 / 100f)
                    }
                )
            }

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

        // Reset
        PillButton(
            text = stringResource(R.string.size_reset),
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
                hapticEnabled = true
                hapticLevel = 2
                prefs.edit()
                    .putBoolean(LatinKeyboardView.PREF_HAPTIC, true)
                    .putInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, 60)
                    .apply()
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Try it out
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.action_try),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
            var tryText by remember { mutableStateOf("") }
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

        Spacer(modifier = Modifier.height(24.dp))
    }
}
