package dev.phucngu.simpletype.ime

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phucngu.simpletype.R

/** Callbacks for the writer's bar and review panel. */
class AiWriterActions(
    val onClose: () -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onAccept: () -> Unit = {},
)

/**
 * The request line shown above the keys while the user describes what to write: the keys type
 * into it, Enter or the send button asks for a draft. While the model works the send button
 * becomes a stop button.
 */
@Composable
fun AiWriterPromptBar(ui: AiWriterController.Ui, actions: AiWriterActions, modifier: Modifier = Modifier) {
    val working = ui.phase == AiWriterController.Phase.WORKING
    val textColor = colorResource(R.color.kb_key_text)
    val hintColor = colorResource(R.color.kb_key_hint)
    val fieldBg = colorResource(R.color.kb_key)
    val accent = colorResource(R.color.kb_accent)
    val chromeIcon = colorResource(R.color.kb_chrome_icon)
    val chromeBg = colorResource(R.color.kb_chrome_button)

    val transition = rememberInfiniteTransition(label = "writer")
    val blink = transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caret",
    ).value
    val pulse = transition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
    ).value

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            iconRes = R.drawable.ic_kb_close,
            description = stringResource(R.string.ai_writer_close),
            onClick = actions.onClose,
            tint = chromeIcon,
            background = chromeBg,
        )
        Spacer(Modifier.width(8.dp))

        val scroll = rememberScrollState()
        LaunchedEffect(ui.prompt) { scroll.scrollTo(scroll.maxValue) }
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 76.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(fieldBg)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .verticalScroll(scroll),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (ui.prompt.isEmpty() && !working) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = accent.copy(alpha = blink))) { append("▏") }
                        withStyle(SpanStyle(color = hintColor)) { append(stringResource(R.string.ai_writer_hint)) }
                    },
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = buildAnnotatedString {
                        append(ui.prompt)
                        if (!working) withStyle(SpanStyle(color = accent.copy(alpha = blink))) { append("▏") }
                    },
                    color = if (working) hintColor else textColor,
                    fontSize = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        if (working) {
            RoundButton(
                iconRes = R.drawable.ic_kb_stop,
                description = stringResource(R.string.ai_writer_stop),
                onClick = actions.onCancel,
                tint = colorResource(R.color.kb_accent_text),
                background = accent,
                modifier = Modifier.alpha(pulse),
            )
        } else {
            val enabled = ui.prompt.isNotBlank()
            RoundButton(
                iconRes = R.drawable.ic_kb_send,
                description = stringResource(R.string.ai_writer_generate),
                onClick = { if (enabled) actions.onGenerate() },
                tint = if (enabled) colorResource(R.color.kb_accent_text) else chromeIcon,
                background = if (enabled) accent else chromeBg,
                modifier = Modifier.alpha(if (enabled) 1f else 0.6f),
            )
        }
    }
}

/** The draft, laid over the keys for review: insert it, try again, change the request or drop it. */
@Composable
fun AiWriterReviewPanel(
    ui: AiWriterController.Ui,
    actions: AiWriterActions,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    val textColor = colorResource(R.color.kb_key_text)
    val hintColor = colorResource(R.color.kb_key_hint)
    val cardBg = colorResource(R.color.kb_key)
    val accent = colorResource(R.color.kb_accent)
    val accentText = colorResource(R.color.kb_accent_text)
    val chromeBg = colorResource(R.color.kb_chrome_button)
    val chromeIcon = colorResource(R.color.kb_chrome_icon)

    Column(
        modifier = modifier
            .background(bgColor)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        // The request, tappable to change it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = actions.onEdit)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kb_ai_write),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = ui.prompt,
                color = hintColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text = ui.draft, color = textColor, fontSize = 16.sp, lineHeight = 22.sp)
        }

        Spacer(Modifier.size(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(stringResource(R.string.ai_writer_discard), chromeBg, chromeIcon, actions.onClose)
            PillButton(stringResource(R.string.ai_writer_edit), chromeBg, chromeIcon, actions.onEdit)
            PillButton(stringResource(R.string.ai_writer_retry), chromeBg, chromeIcon, actions.onGenerate)
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.ai_writer_insert), accent, accentText, actions.onAccept, bold = true)
        }
    }
}

@Composable
private fun PillButton(label: String, background: Color, content: Color, onClick: () -> Unit, bold: Boolean = false) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun RoundButton(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(iconRes), contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}
