package dev.phucngu.simpletype.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.keyboard.model.Keyboard

/** A round tonal icon button used throughout the keyboard chrome (M3 Expressive). */
@Composable
private fun CircleIconButton(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun KeyboardScreen(
    keyboard: Keyboard,
    metrics: KeyboardMetrics,
    spaceLabel: String,
    shifted: Boolean,
    capsLock: Boolean,
    voiceStatus: String?,
    micActive: Boolean,
    optionsExpanded: Boolean,
    clipboardItems: List<ClipboardItem>,
    clipboardVisible: Boolean,
    listener: LatinKeyboardListener,
    glideEnabled: Boolean = false,
    suggestions: List<String> = emptyList(),
    selectedSuggestion: String? = null,
    onSuggestionClick: (String) -> Unit = {},
    onMicClick: () -> Unit,
    onSetupClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onMenuClick: () -> Unit,
    onClipboardClose: () -> Unit,
    onClipboardSelect: (String) -> Unit,
    onClipboardPin: (String) -> Unit,
    onClipboardDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = colorResource(R.color.kb_background)
    val chromeIconColor = colorResource(R.color.kb_chrome_icon)
    val chromeButtonBg = colorResource(R.color.kb_key_special)
    val statusTextColor = colorResource(R.color.kb_status_text)
    val micActiveColor = colorResource(R.color.kb_mic_active)
    val primaryContainerColor = colorResource(R.color.kb_primary_container)
    val onPrimaryContainerColor = colorResource(R.color.kb_on_primary_container)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(bgColor)
    ) {
        // Toolbar Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left options cluster
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    iconRes = R.drawable.ic_kb_menu,
                    contentDescription = stringResource(R.string.toolbar_menu),
                    onClick = onMenuClick,
                    tint = chromeIconColor,
                    background = chromeButtonBg
                )

                AnimatedVisibility(visible = optionsExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(10.dp))
                        CircleIconButton(
                            iconRes = R.drawable.ic_kb_settings,
                            contentDescription = stringResource(R.string.settings_title),
                            onClick = onSetupClick,
                            tint = chromeIconColor,
                            background = chromeButtonBg
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        CircleIconButton(
                            iconRes = R.drawable.ic_kb_clipboard,
                            contentDescription = stringResource(R.string.key_clipboard),
                            onClick = onClipboardClick,
                            tint = chromeIconColor,
                            background = chromeButtonBg
                        )
                    }
                }
            }

            // Middle status/suggestion area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (voiceStatus != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (micActive) {
                            RecordingIndicator(color = micActiveColor)
                            Spacer(modifier = Modifier.width(7.dp))
                        }
                        Text(
                            text = voiceStatus,
                            color = statusTextColor,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (suggestions.isNotEmpty()) {
                    SuggestionStrip(
                        suggestions = suggestions,
                        selectedSuggestion = selectedSuggestion,
                        onSuggestionClick = onSuggestionClick,
                    )
                }
            }

            // Prominent mic button (M3 filled, primaryContainer).
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (micActive) micActiveColor else primaryContainerColor)
                    .clickable(onClick = onMicClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (micActive) R.drawable.ic_kb_stop else R.drawable.ic_kb_mic
                    ),
                    contentDescription = stringResource(
                        if (micActive) R.string.toolbar_stop_mic else R.string.toolbar_mic
                    ),
                    tint = if (micActive) Color.White else onPrimaryContainerColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Keyboard / Clipboard Box Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            LatinKeyboard(
                keyboard = keyboard,
                metrics = metrics,
                spaceLabel = spaceLabel,
                shifted = shifted,
                capsLock = capsLock,
                listener = listener,
                modifier = Modifier.fillMaxWidth(),
                glideEnabled = glideEnabled,
            )

            if (clipboardVisible) {
                ClipboardPanel(
                    items = clipboardItems,
                    bgColor = bgColor,
                    onClose = onClipboardClose,
                    onSelect = onClipboardSelect,
                    onPin = onClipboardPin,
                    onDelete = onClipboardDelete,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

/** Gesture-typing candidates: the applied word gets the highlighted chip, others are plain. */
@Composable
private fun SuggestionStrip(
    suggestions: List<String>,
    selectedSuggestion: String?,
    onSuggestionClick: (String) -> Unit,
) {
    val textColor = colorResource(R.color.kb_key_text)
    val topBg = colorResource(R.color.kb_primary_container)
    val topTextColor = colorResource(R.color.kb_on_primary_container)
    // Horizontally scrollable so all candidates (up to MAX_RESULTS) are reachable
    // by swiping right, even when they overflow the strip width.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEachIndexed { index, word ->
            val isTop = if (selectedSuggestion != null) word == selectedSuggestion else index == 0
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .defaultMinSize(minWidth = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isTop) topBg else Color.Transparent)
                    .clickable { onSuggestionClick(word) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = word,
                    color = if (isTop) topTextColor else textColor,
                    fontSize = if (isTop) 19.sp else 17.sp,
                    fontWeight = if (isTop) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RecordingIndicator(color: Color) {
    val transition = rememberInfiniteTransition(label = "recording")
    val indicatorAlpha = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording alpha",
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(indicatorAlpha.value)
            .background(color, CircleShape)
    )
}

@Composable
private fun ClipboardPanel(
    items: List<ClipboardItem>,
    bgColor: Color,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onPin: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyTextColor = colorResource(R.color.kb_key_text)
    val chromeIconColor = colorResource(R.color.kb_chrome_icon)
    val chromeButtonBg = colorResource(R.color.kb_key_special)
    val accentColor = colorResource(R.color.kb_accent)

    val pinned = items.filter { it.isPinned }
    val recent = items.filter { !it.isPinned }

    Column(
        modifier = modifier
            .background(bgColor)
            .padding(horizontal = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.clipboard_title),
                color = keyTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            CircleIconButton(
                iconRes = R.drawable.ic_kb_backspace,
                contentDescription = stringResource(R.string.clipboard_close),
                onClick = onClose,
                tint = chromeIconColor,
                background = chromeButtonBg,
                modifier = Modifier.rotate(180f),
                iconSize = 18.dp
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (pinned.isNotEmpty()) {
                item(key = "hdr_pinned") {
                    ClipboardSectionHeader(
                        label = stringResource(R.string.clipboard_section_pinned),
                        color = accentColor,
                        iconRes = R.drawable.ic_kb_pin
                    )
                }
                items(pinned, key = { it.id }) { item ->
                    ClipboardCard(item, onSelect, onPin, onDelete)
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "hdr_recent") {
                    ClipboardSectionHeader(
                        label = stringResource(R.string.clipboard_section_recent),
                        color = accentColor,
                        iconRes = null
                    )
                }
                items(recent, key = { it.id }) { item ->
                    ClipboardCard(item, onSelect, onPin, onDelete)
                }
            }
        }
    }
}

@Composable
private fun ClipboardSectionHeader(label: String, color: Color, iconRes: Int?) {
    Row(
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 0.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
        }
        Text(text = label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ClipboardCard(
    item: ClipboardItem,
    onSelect: (String) -> Unit,
    onPin: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val keyTextColor = colorResource(R.color.kb_key_text)
    val chromeIconColor = colorResource(R.color.kb_chrome_icon)
    val chromeButtonBg = colorResource(R.color.kb_key_special)
    val surfaceColor = colorResource(R.color.kb_surface)
    val primaryContainerColor = colorResource(R.color.kb_primary_container)
    val onPrimaryContainerColor = colorResource(R.color.kb_on_primary_container)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surfaceColor)
            .clickable { onSelect(item.text) }
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = item.text,
            color = keyTextColor,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        CircleIconButton(
            iconRes = R.drawable.ic_kb_pin,
            contentDescription = stringResource(R.string.clipboard_pin),
            onClick = { onPin(item.id) },
            tint = if (item.isPinned) onPrimaryContainerColor else chromeIconColor,
            background = if (item.isPinned) primaryContainerColor else Color.Transparent,
            size = 38.dp,
            iconSize = 16.dp
        )
        CircleIconButton(
            iconRes = R.drawable.ic_kb_delete,
            contentDescription = stringResource(R.string.clipboard_delete),
            onClick = { onDelete(item.id) },
            tint = chromeIconColor,
            background = chromeButtonBg,
            size = 38.dp,
            iconSize = 16.dp
        )
    }
}
