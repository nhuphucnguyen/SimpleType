package dev.phucngu.simpletype.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R

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
    val statusTextColor = colorResource(R.color.kb_status_text)
    val micActiveColor = colorResource(R.color.kb_mic_active)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
    ) {
        // Toolbar Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp) // kb_toolbar_height
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Options Container
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kb_menu),
                        contentDescription = stringResource(R.string.toolbar_menu),
                        tint = chromeIconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                AnimatedVisibility(visible = optionsExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onSetupClick,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_kb_settings),
                                contentDescription = stringResource(R.string.settings_title),
                                tint = chromeIconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = onClipboardClick,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_kb_clipboard),
                                contentDescription = stringResource(R.string.key_clipboard),
                                tint = chromeIconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
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
                    Text(
                        text = voiceStatus,
                        color = statusTextColor,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Mic Button
            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(40.dp) // kb_toolbar_mic_size
                    .clip(CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(if (micActive) micActiveColor.copy(alpha = 0.15f) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_kb_mic),
                        contentDescription = stringResource(R.string.toolbar_mic),
                        tint = if (micActive) micActiveColor else chromeIconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
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
                modifier = Modifier.fillMaxWidth()
            )

            if (clipboardVisible) {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(bgColor)
                ) {
                    // Clipboard Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(start = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.clipboard_title),
                            color = statusTextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        IconButton(
                            onClick = onClipboardClose,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_kb_backspace),
                                contentDescription = stringResource(R.string.clipboard_close),
                                tint = chromeIconColor,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(180f)
                            )
                        }
                    }

                    // Clipboard scrollable list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(clipboardItems, key = { it.id }) { item ->
                            ClipboardItemRow(
                                item = item,
                                onSelect = onClipboardSelect,
                                onPin = onClipboardPin,
                                onDelete = onClipboardDelete
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClipboardItemRow(
    item: ClipboardItem,
    onSelect: (String) -> Unit,
    onPin: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val keyTextColor = colorResource(R.color.kb_key_text)
    val chromeIconColor = colorResource(R.color.kb_chrome_icon)
    val accentColor = colorResource(R.color.kb_accent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(item.text) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.text,
            color = keyTextColor,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onPin(item.id) },
            modifier = Modifier.size(width = 28.dp, height = 36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kb_pin),
                contentDescription = stringResource(R.string.clipboard_pin),
                tint = if (item.isPinned) accentColor else chromeIconColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { onDelete(item.id) },
            modifier = Modifier.size(width = 28.dp, height = 36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kb_delete),
                contentDescription = stringResource(R.string.clipboard_delete),
                tint = chromeIconColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
