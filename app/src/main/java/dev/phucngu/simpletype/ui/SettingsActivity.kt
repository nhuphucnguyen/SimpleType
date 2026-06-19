package dev.phucngu.simpletype.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.HapticPlayer
import dev.phucngu.simpletype.ime.KeyboardLayouts
import dev.phucngu.simpletype.ime.KeyboardMetrics
import dev.phucngu.simpletype.ime.LatinKeyboard
import dev.phucngu.simpletype.ime.LatinKeyboardListener
import dev.phucngu.simpletype.ime.LatinKeyboardView
import dev.phucngu.simpletype.ime.Key
import dev.phucngu.simpletype.voice.ModelManager
import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlin.concurrent.thread

class SettingsActivity : ComponentActivity() {

    private val models by lazy { ModelManager(this) }
    private val haptics by lazy { HapticPlayer(this) }

    private val imeEnabledStatus = mutableStateOf("")
    private val enModelText = mutableStateOf("")
    private val enModelEnabled = mutableStateOf(true)
    private val viModelText = mutableStateOf("")
    private val viModelEnabled = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        imeEnabledStatus = imeEnabledStatus.value,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    imeEnabledStatus: String,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = stringResource(R.string.settings_intro),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Setup Steps Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = imeEnabledStatus,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = stringResource(R.string.step_enable),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = onEnableClick,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(stringResource(R.string.action_enable))
                }

                Text(
                    text = stringResource(R.string.step_select),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(onClick = onSelectClick) {
                    Text(stringResource(R.string.action_select))
                }
            }
        }

        // Voice Models Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.voice_models_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.voice_models_intro),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = { onDownloadClick(VoiceLanguage.ENGLISH) },
                    enabled = enModelEnabled,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(enModelText)
                }

                Button(
                    onClick = { onDownloadClick(VoiceLanguage.VIETNAMESE) },
                    enabled = viModelEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(viModelText)
                }
            }
        }

        // Sizing & Preferences Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.size_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.size_intro),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Row Height
                Text(
                    text = stringResource(R.string.size_row_height, currentMetrics.rowHeightDp.toInt()),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Slider(
                    value = currentMetrics.rowHeightDp,
                    onValueChange = {
                        val newMetrics = KeyboardMetrics.of(
                            rowHeightDp = it,
                            gapHorizontalDp = currentMetrics.gapHorizontalDp,
                            gapVerticalDp = currentMetrics.gapVerticalDp,
                            bottomPaddingDp = currentMetrics.bottomPaddingDp,
                            showNumberRow = currentMetrics.showNumberRow,
                            showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                            showSymbolHints = currentMetrics.showSymbolHints
                        )
                        currentMetrics = newMetrics
                        KeyboardMetrics.save(prefs, newMetrics)
                    },
                    valueRange = KeyboardMetrics.ROW_HEIGHT_MIN..KeyboardMetrics.ROW_HEIGHT_MAX,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Horizontal Gap
                Text(
                    text = stringResource(R.string.size_gap_h, currentMetrics.gapHorizontalDp.toInt()),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Slider(
                    value = currentMetrics.gapHorizontalDp,
                    onValueChange = {
                        val newMetrics = KeyboardMetrics.of(
                            rowHeightDp = currentMetrics.rowHeightDp,
                            gapHorizontalDp = it,
                            gapVerticalDp = currentMetrics.gapVerticalDp,
                            bottomPaddingDp = currentMetrics.bottomPaddingDp,
                            showNumberRow = currentMetrics.showNumberRow,
                            showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                            showSymbolHints = currentMetrics.showSymbolHints
                        )
                        currentMetrics = newMetrics
                        KeyboardMetrics.save(prefs, newMetrics)
                    },
                    valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Vertical Gap
                Text(
                    text = stringResource(R.string.size_gap_v, currentMetrics.gapVerticalDp.toInt()),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Slider(
                    value = currentMetrics.gapVerticalDp,
                    onValueChange = {
                        val newMetrics = KeyboardMetrics.of(
                            rowHeightDp = currentMetrics.rowHeightDp,
                            gapHorizontalDp = currentMetrics.gapHorizontalDp,
                            gapVerticalDp = it,
                            bottomPaddingDp = currentMetrics.bottomPaddingDp,
                            showNumberRow = currentMetrics.showNumberRow,
                            showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                            showSymbolHints = currentMetrics.showSymbolHints
                        )
                        currentMetrics = newMetrics
                        KeyboardMetrics.save(prefs, newMetrics)
                    },
                    valueRange = KeyboardMetrics.GAP_MIN..KeyboardMetrics.GAP_MAX,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Bottom Pad
                Text(
                    text = stringResource(R.string.size_bottom_pad, currentMetrics.bottomPaddingDp.toInt()),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Slider(
                    value = currentMetrics.bottomPaddingDp,
                    onValueChange = {
                        val newMetrics = KeyboardMetrics.of(
                            rowHeightDp = currentMetrics.rowHeightDp,
                            gapHorizontalDp = currentMetrics.gapHorizontalDp,
                            gapVerticalDp = currentMetrics.gapVerticalDp,
                            bottomPaddingDp = it,
                            showNumberRow = currentMetrics.showNumberRow,
                            showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                            showSymbolHints = currentMetrics.showSymbolHints
                        )
                        currentMetrics = newMetrics
                        KeyboardMetrics.save(prefs, newMetrics)
                    },
                    valueRange = KeyboardMetrics.BOTTOM_PAD_MIN..KeyboardMetrics.BOTTOM_PAD_MAX,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Live Preview
                Text(
                    text = stringResource(R.string.size_live_preview),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorResource(R.color.kb_background), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    LatinKeyboard(
                        keyboard = KeyboardLayouts.qwerty(currentMetrics.showDedicatedNumberRow),
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

                // Switches
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.size_number_row), fontSize = 15.sp)
                        Text(
                            stringResource(R.string.size_number_row_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = currentMetrics.showNumberRow,
                        onCheckedChange = { checked ->
                            val newMetrics = KeyboardMetrics.of(
                                rowHeightDp = currentMetrics.rowHeightDp,
                                gapHorizontalDp = currentMetrics.gapHorizontalDp,
                                gapVerticalDp = currentMetrics.gapVerticalDp,
                                bottomPaddingDp = currentMetrics.bottomPaddingDp,
                                showNumberRow = checked,
                                showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                                showSymbolHints = if (checked) false else currentMetrics.showSymbolHints
                            )
                            currentMetrics = newMetrics
                            KeyboardMetrics.save(prefs, newMetrics)
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.size_symbol_hints), fontSize = 15.sp)
                        Text(
                            stringResource(R.string.size_symbol_hints_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = currentMetrics.showSymbolHints,
                        onCheckedChange = { checked ->
                            val newMetrics = KeyboardMetrics.of(
                                rowHeightDp = currentMetrics.rowHeightDp,
                                gapHorizontalDp = currentMetrics.gapHorizontalDp,
                                gapVerticalDp = currentMetrics.gapVerticalDp,
                                bottomPaddingDp = currentMetrics.bottomPaddingDp,
                                showNumberRow = if (checked) false else currentMetrics.showNumberRow,
                                showDedicatedNumberRow = currentMetrics.showDedicatedNumberRow,
                                showSymbolHints = checked
                            )
                            currentMetrics = newMetrics
                            KeyboardMetrics.save(prefs, newMetrics)
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.size_dedicated_number_row), fontSize = 15.sp)
                        Text(
                            stringResource(R.string.size_dedicated_number_row_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = currentMetrics.showDedicatedNumberRow,
                        onCheckedChange = { checked ->
                            val newMetrics = KeyboardMetrics.of(
                                rowHeightDp = currentMetrics.rowHeightDp,
                                gapHorizontalDp = currentMetrics.gapHorizontalDp,
                                gapVerticalDp = currentMetrics.gapVerticalDp,
                                bottomPaddingDp = currentMetrics.bottomPaddingDp,
                                showNumberRow = currentMetrics.showNumberRow,
                                showDedicatedNumberRow = checked,
                                showSymbolHints = currentMetrics.showSymbolHints
                            )
                            currentMetrics = newMetrics
                            KeyboardMetrics.save(prefs, newMetrics)
                        }
                    )
                }

                // Haptics Section
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.size_haptic), fontSize = 15.sp)
                        Text(
                            stringResource(R.string.size_haptic_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hapticEnabled,
                        onCheckedChange = { checked ->
                            hapticEnabled = checked
                            prefs.edit().putBoolean(LatinKeyboardView.PREF_HAPTIC, checked).apply()
                            if (checked) {
                                val percent = (hapticLevel + 1) * 100 / 5
                                haptics.tap(percent / 100f)
                            }
                        }
                    )
                }

                if (hapticEnabled) {
                    val strengthName = hapticLevels.getOrNull(hapticLevel) ?: "Medium"
                    Text(
                        text = stringResource(R.string.size_haptic_strength, strengthName),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    Slider(
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
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.haptic_level_min), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.haptic_level_mid), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.haptic_level_max), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Button(
                    onClick = {
                        val newMetrics = KeyboardMetrics.DEFAULT
                        currentMetrics = newMetrics
                        KeyboardMetrics.save(prefs, newMetrics)
                        // Reset haptics
                        hapticEnabled = true
                        hapticLevel = 2 // Medium
                        prefs.edit()
                            .putBoolean(LatinKeyboardView.PREF_HAPTIC, true)
                            .putInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, 60)
                            .apply()
                    },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.size_reset))
                }
            }
        }

        // Try it out Field
        Text(
            text = stringResource(R.string.action_try),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        var tryText by remember { mutableStateOf("") }
        OutlinedTextField(
            value = tryText,
            onValueChange = { tryText = it },
            placeholder = { Text(stringResource(R.string.try_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        )
    }
}
