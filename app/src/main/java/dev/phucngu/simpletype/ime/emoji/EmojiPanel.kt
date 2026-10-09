package dev.phucngu.simpletype.ime.emoji

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DELETE_REPEAT_INITIAL_MS = 400L
private const val DELETE_REPEAT_INTERVAL_MS = 55L
private const val RECENT_ICON = "🕘"

/** One scrolling section of the grid: Recents (category == null) or a fixed category. */
private data class EmojiSection(val category: EmojiCategory?, val emojis: List<String>)

/**
 * Full emoji picker that replaces the key area: a scrolling grid of all categories with a
 * bottom bar to jump between them, return to the letters, or backspace. Tapping an emoji types
 * it and keeps the picker open so several can be entered in a row.
 */
@Composable
fun EmojiPanel(
    recents: List<String>,
    bgColor: Color,
    onSelect: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyTextColor = colorResource(R.color.kb_key_text)
    val chromeIconColor = colorResource(R.color.kb_chrome_icon)
    val chromeButtonBg = colorResource(R.color.kb_chrome_button)
    val accentColor = colorResource(R.color.kb_accent)
    val hintColor = colorResource(R.color.kb_key_hint)
    val selectedTabBg = colorResource(R.color.kb_primary_container)

    // Drop emoji the system font can't draw (older Android) so the grid never shows tofu.
    val categories = remember {
        val paint = Paint()
        EmojiCategory.entries.map { EmojiSection(it, EmojiData.emojis(it).filter(paint::hasGlyph)) }
    }
    val sections = remember(recents) { listOf(EmojiSection(null, recents)) + categories }
    // Grid index of each section's header; a section is its header plus its emoji (or the
    // single empty-recents placeholder).
    val sectionStarts = remember(sections) {
        sections.runningFold(0) { start, section -> start + 1 + maxOf(section.emojis.size, 1) }.dropLast(1)
    }

    val latestOnDelete by rememberUpdatedState(onDelete)
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val currentSection by remember(sectionStarts) {
        derivedStateOf { sectionStarts.indexOfLast { it <= gridState.firstVisibleItemIndex }.coerceAtLeast(0) }
    }

    Column(modifier = modifier.background(bgColor)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 44.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            sections.forEach { section ->
                val title = section.category?.label ?: R.string.emoji_category_recent
                item(key = "hdr_${section.category}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(title),
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                if (section.emojis.isEmpty()) {
                    item(key = "empty_${section.category}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.emoji_recent_empty),
                            color = hintColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    items(section.emojis, key = { "${section.category}_$it" }) { emoji ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = emoji, fontSize = 26.sp, color = keyTextColor)
                        }
                    }
                }
            }
        }

        // Bottom bar: back to letters, category tabs, backspace.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backDesc = stringResource(R.string.emoji_back_to_keyboard_desc)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(19.dp))
                    .background(chromeButtonBg)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = backDesc }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.emoji_back_to_keyboard),
                    color = chromeIconColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sections.forEachIndexed { index, section ->
                    val desc = stringResource(section.category?.label ?: R.string.emoji_category_recent)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (index == currentSection) selectedTabBg else Color.Transparent)
                            .clickable { scope.launch { gridState.scrollToItem(sectionStarts[index]) } }
                            .semantics { contentDescription = desc },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = section.category?.icon ?: RECENT_ICON, fontSize = 19.sp)
                    }
                }
            }

            val deleteDesc = stringResource(R.string.emoji_delete)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(52.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(chromeButtonBg)
                    .semantics { contentDescription = deleteDesc }
                    .pointerInput(Unit) {
                        // Hold to keep deleting, matching the keyboard's backspace key.
                        detectTapGestures(onPress = {
                            latestOnDelete()
                            coroutineScope {
                                val repeat = launch {
                                    delay(DELETE_REPEAT_INITIAL_MS)
                                    while (true) {
                                        latestOnDelete()
                                        delay(DELETE_REPEAT_INTERVAL_MS)
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_kb_backspace),
                    contentDescription = null,
                    tint = chromeIconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
