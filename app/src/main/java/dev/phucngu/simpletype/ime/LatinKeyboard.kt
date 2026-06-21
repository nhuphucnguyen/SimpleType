package dev.phucngu.simpletype.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.phucngu.simpletype.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L
private const val LONG_PRESS_MS = 300L
private const val NUMBER_HINT_TOP_PADDING_DP = 1f
private const val NUMBER_HINTED_TEXT_OFFSET_DP = 2f
private const val KEY_TEXT_BOTTOM_PADDING_DP = 1f

object LatinKeyboardView {
    const val PREF_HAPTIC = "kb_haptic"
    const val PREF_HAPTIC_STRENGTH = "kb_haptic_strength"
    const val DEFAULT_HAPTIC_PERCENT = 60
    const val DEFAULT_HAPTIC_STRENGTH = DEFAULT_HAPTIC_PERCENT / 100f
}

data class Placement(val key: Key, val rect: RectF)

fun calculatePlacements(
    widthPx: Float,
    keyboard: Keyboard,
    metrics: KeyboardMetrics,
    densityFloat: Float,
    vPadPx: Float
): List<Placement> {
    val list = ArrayList<Placement>()
    val rowHeightPx = metrics.rowHeightDp * densityFloat
    var top = vPadPx
    for (rowObj in keyboard.rows) {
        val totalWeight = rowObj.keys.sumOf { it.weight.toDouble() }.toFloat() + rowObj.sideWeight * 2f
        val unit = widthPx / totalWeight
        var left = rowObj.sideWeight * unit
        for (key in rowObj.keys) {
            val keyWidth = unit * key.weight
            list.add(Placement(key, RectF(left, top, left + keyWidth, top + rowHeightPx)))
            left += keyWidth
        }
        top += rowHeightPx
    }
    return list
}

internal fun calculateNumberHintPosition(
    keyRect: RectF,
    densityFloat: Float,
    fontAscent: Float,
): PointF = PointF(
    keyRect.centerX(),
    keyRect.top + NUMBER_HINT_TOP_PADDING_DP * densityFloat - fontAscent,
)

internal fun calculateNumberHintedTextBaseline(
    centeredBaseline: Float,
    keyBottom: Float,
    densityFloat: Float,
    fontDescent: Float,
): Float = minOf(
    centeredBaseline + NUMBER_HINTED_TEXT_OFFSET_DP * densityFloat,
    keyBottom - KEY_TEXT_BOTTOM_PADDING_DP * densityFloat - fontDescent,
)

class TouchState {
    var downOnSpace by mutableStateOf(false)
    var swipeStartX = 0f
    var swipeStartY = 0f
    var swipeFired by mutableStateOf(false)
    var swipeOffset by mutableStateOf(0f)
    var numberSwipeFired = false
    var longPressFired = false
    var shiftPointerId by mutableStateOf<PointerId?>(null)
    var shiftKey: Key? = null
    var shiftUsedAsModifier = false
    var activePointerId: PointerId? = null
}

interface LatinKeyboardListener {
    fun onKey(key: Key)
    fun onKeyRepeat(key: Key)
    fun onSpaceSwipe(direction: Int)
    fun onShiftHold(active: Boolean)
}

@Composable
fun LatinKeyboard(
    keyboard: Keyboard,
    metrics: KeyboardMetrics,
    spaceLabel: String,
    shifted: Boolean,
    capsLock: Boolean,
    listener: LatinKeyboardListener,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val densityFloat = density.density

    val keyRadius = dimensionResource(R.dimen.kb_key_radius).value * densityFloat
    val vPad = dimensionResource(R.dimen.kb_vertical_padding).value * densityFloat
    val iconSizePx = dimensionResource(R.dimen.kb_icon_size).value * densityFloat

    val rowHeightPx = metrics.rowHeightDp * densityFloat
    val gapHorizontalPx = metrics.gapHorizontalDp * densityFloat
    val gapVerticalPx = metrics.gapVerticalDp * densityFloat

    // Resolve color resources
    val bgColor = colorResource(R.color.kb_background)
    val keyColor = colorResource(R.color.kb_key)
    val keyPressedColor = colorResource(R.color.kb_key_pressed)
    val keySpecialColor = colorResource(R.color.kb_key_special)
    val accentColor = colorResource(R.color.kb_accent)
    val accentTextColor = colorResource(R.color.kb_accent_text)
    val enterColor = colorResource(R.color.kb_key_enter)
    val enterTextColor = colorResource(R.color.kb_key_enter_text)
    val keyTextColor = colorResource(R.color.kb_key_text)
    val keySpecialTextColor = colorResource(R.color.kb_key_special_text)
    val keyHintColor = colorResource(R.color.kb_key_hint)

    // Load Haptic player and preferences reactively
    val haptics = remember(context) { HapticPlayer(context) }
    val prefs = remember(context) { context.getSharedPreferences("simpletype_prefs", Context.MODE_PRIVATE) }
    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean(LatinKeyboardView.PREF_HAPTIC, true)) }
    var hapticPercent by remember { mutableStateOf(prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LatinKeyboardView.PREF_HAPTIC) {
                hapticEnabled = prefs.getBoolean(LatinKeyboardView.PREF_HAPTIC, true)
            } else if (key == LatinKeyboardView.PREF_HAPTIC_STRENGTH) {
                hapticPercent = prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val hapticStrength = hapticPercent / 100f
    val hapticTap = {
        if (hapticEnabled) {
            haptics.tap(hapticStrength)
        }
    }

    // Paints
    val keyPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val hintPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val chevronPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    // Set paint properties
    val keyTextSize = dimensionResource(R.dimen.kb_key_text_size)
    val keyLabelTextSize = dimensionResource(R.dimen.kb_key_label_text_size)
    textPaint.textSize = with(density) { keyTextSize.toPx() }
    labelPaint.textSize = with(density) { keyLabelTextSize.toPx() }
    hintPaint.textSize = with(density) { keyTextSize.toPx() } * 0.5f
    hintPaint.alpha = 170
    chevronPaint.strokeWidth = 1.5f * densityFloat

    val iconCache = remember { HashMap<Int, Drawable>() }

    // Touch and Timer State
    val scope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val touchState = remember { TouchState() }
    var pressedPlacement by remember { mutableStateOf<Placement?>(null) }

    val swipeThreshold = 28f * densityFloat
    val numberSwipeThreshold = 22f * densityFloat

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { (keyboard.rows.size * metrics.rowHeightDp + metrics.bottomPaddingDp + 16f).dp }) // Adding paddings
    ) {
        val widthPx = constraints.maxWidth.toFloat()

        val placements = remember(keyboard, metrics, widthPx) {
            calculatePlacements(widthPx, keyboard, metrics, densityFloat, vPad)
        }

        fun hintFor(key: Key): Char? = when {
            metrics.numberHintsVisible -> key.numberHint
            metrics.showSymbolHints -> key.symbolHint
            else -> null
        }

        fun displayLabel(key: Key): String {
            if (key.isPrintable && shifted) {
                val c = key.code.toChar()
                if (c.isLetter()) return c.uppercaseChar().toString()
            }
            return key.label
        }

        fun iconResFor(key: Key): Int? = when {
            key.code == KeyCode.SHIFT && capsLock -> R.drawable.ic_kb_shift_lock
            else -> key.iconRes
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(keyboard, metrics, placements) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes

                                // 1. Process pointer down events
                                for (change in changes) {
                                    val id = change.id
                                    val pos = change.position
                                    val isDown = change.pressed && !change.previousPressed
                                    if (isDown) {
                                        val p = placements.firstOrNull { it.rect.contains(pos.x, pos.y) } ?: continue
                                        if (p.key.code == KeyCode.SHIFT && touchState.shiftPointerId == null) {
                                            touchState.shiftPointerId = id
                                            touchState.shiftKey = p.key
                                            touchState.shiftUsedAsModifier = false
                                            hapticTap()
                                            change.consume()
                                        } else if (touchState.activePointerId == null) {
                                            touchState.activePointerId = id
                                            pressedPlacement = p
                                            hapticTap()
                                            touchState.downOnSpace = p.key.code == KeyCode.SPACE
                                            touchState.swipeStartX = pos.x
                                            touchState.swipeStartY = pos.y
                                            touchState.swipeFired = false
                                            touchState.swipeOffset = 0f
                                            touchState.numberSwipeFired = false
                                            touchState.longPressFired = false

                                            if (touchState.shiftPointerId != null && !touchState.shiftUsedAsModifier) {
                                                touchState.shiftUsedAsModifier = true
                                                listener.onShiftHold(true)
                                            }

                                            if (p.key.repeatable) {
                                                listener.onKeyRepeat(p.key)
                                                repeatJob = scope.launch {
                                                    delay(REPEAT_INITIAL_DELAY_MS)
                                                    while (isActive) {
                                                        listener.onKeyRepeat(p.key)
                                                        hapticTap()
                                                        delay(REPEAT_INTERVAL_MS)
                                                    }
                                                }
                                            } else if (p.key.longPressCode != null) {
                                                longPressJob = scope.launch {
                                                    delay(LONG_PRESS_MS)
                                                    touchState.longPressFired = true
                                                    if (hapticEnabled) haptics.longPress(hapticStrength)
                                                    listener.onKey(Key(p.key.longPressCode, ""))
                                                }
                                            }
                                            change.consume()
                                        }
                                    }
                                }

                                // 2. Process pointer move events
                                val activeChange = changes.firstOrNull { it.id == touchState.activePointerId }
                                if (activeChange != null && activeChange.pressed) {
                                    val pos = activeChange.position
                                    if (touchState.downOnSpace) {
                                        touchState.swipeOffset = pos.x - touchState.swipeStartX
                                        if (!touchState.swipeFired && abs(touchState.swipeOffset) >= swipeThreshold) {
                                            touchState.swipeFired = true
                                            hapticTap()
                                            listener.onSpaceSwipe(if (touchState.swipeOffset > 0) 1 else -1)
                                        }
                                        activeChange.consume()
                                    } else {
                                        val hint = pressedPlacement?.key?.let { hintFor(it) }
                                        if (hint != null && !touchState.numberSwipeFired && !touchState.swipeFired) {
                                            val dy = pos.y - touchState.swipeStartY
                                            if (dy >= numberSwipeThreshold && dy >= abs(pos.x - touchState.swipeStartX)) {
                                                touchState.numberSwipeFired = true
                                                repeatJob?.cancel()
                                                longPressJob?.cancel()
                                                hapticTap()
                                                listener.onKey(Key(hint.code, hint.toString()))
                                                activeChange.consume()
                                            }
                                        }

                                        if (!touchState.numberSwipeFired) {
                                            val p = placements.firstOrNull { it.rect.contains(pos.x, pos.y) }
                                            if (p != pressedPlacement) {
                                                repeatJob?.cancel()
                                                longPressJob?.cancel()
                                                pressedPlacement = p
                                                if (p != null && p.key.repeatable) {
                                                    repeatJob = scope.launch {
                                                        delay(REPEAT_INITIAL_DELAY_MS)
                                                        while (isActive) {
                                                            listener.onKeyRepeat(p.key)
                                                            hapticTap()
                                                            delay(REPEAT_INTERVAL_MS)
                                                        }
                                                    }
                                                } else if (p != null && p.key.longPressCode != null) {
                                                    longPressJob = scope.launch {
                                                        delay(LONG_PRESS_MS)
                                                        touchState.longPressFired = true
                                                        if (hapticEnabled) haptics.longPress(hapticStrength)
                                                        listener.onKey(Key(p.key.longPressCode, ""))
                                                    }
                                                }
                                            }
                                            activeChange.consume()
                                        }
                                    }
                                }

                                // 3. Process pointer up events
                                for (change in changes) {
                                    val id = change.id
                                    val isUp = !change.pressed && change.previousPressed
                                    if (isUp) {
                                        if (id == touchState.shiftPointerId) {
                                            val key = touchState.shiftKey
                                            touchState.shiftPointerId = null
                                            touchState.shiftKey = null
                                            if (touchState.shiftUsedAsModifier) {
                                                listener.onShiftHold(false)
                                            } else if (key != null) {
                                                listener.onKey(key)
                                            }
                                            touchState.shiftUsedAsModifier = false
                                            change.consume()
                                        } else if (id == touchState.activePointerId) {
                                            repeatJob?.cancel()
                                            longPressJob?.cancel()
                                            val p = pressedPlacement
                                            pressedPlacement = null
                                            touchState.activePointerId = null
                                            touchState.downOnSpace = false
                                            touchState.swipeOffset = 0f
                                            val numberFired = touchState.numberSwipeFired
                                            touchState.numberSwipeFired = false
                                            if (p != null && !p.key.repeatable && !touchState.swipeFired && !touchState.longPressFired && !numberFired) {
                                                listener.onKey(p.key)
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        repeatJob?.cancel()
                        longPressJob?.cancel()
                        pressedPlacement = null
                        touchState.activePointerId = null
                        touchState.downOnSpace = false
                        touchState.swipeOffset = 0f
                        touchState.numberSwipeFired = false
                        if (touchState.shiftPointerId != null) {
                            if (touchState.shiftUsedAsModifier) listener.onShiftHold(false)
                            touchState.shiftPointerId = null
                            touchState.shiftUsedAsModifier = false
                        }
                    }
                }
        ) {
            drawIntoCanvas { canvas ->
                val fm = textPaint.fontMetrics


                for (p in placements) {
                    val key = p.key
                    val r = p.rect
                    val isPressed = p == pressedPlacement || (key.code == KeyCode.SHIFT && touchState.shiftPointerId != null)
                    val active = key.code == KeyCode.SHIFT && (shifted || capsLock)
                    val isShift = key.code == KeyCode.SHIFT
                    val isEnter = key.code == KeyCode.ENTER

                    keyPaint.color = when {
                        isPressed -> keyPressedColor.toArgb()
                        isShift || active -> accentColor.toArgb()
                        isEnter -> enterColor.toArgb()
                        key.style == KeyStyle.SPECIAL -> keySpecialColor.toArgb()
                        else -> keyColor.toArgb()
                    }

                    // Foreground (glyph/text) color matching the key's role.
                    val fg = when {
                        isShift || active -> accentTextColor.toArgb()
                        isEnter -> enterTextColor.toArgb()
                        key.style == KeyStyle.SPECIAL -> keySpecialTextColor.toArgb()
                        else -> keyTextColor.toArgb()
                    }

                    val insetH = gapHorizontalPx / 2f
                    val insetV = gapVerticalPx / 2f
                    val rr = RectF(r.left + insetH, r.top + insetV, r.right - insetH, r.bottom - insetV)
                    canvas.nativeCanvas.drawRoundRect(rr, keyRadius, keyRadius, keyPaint)

                    val cx = rr.centerX()
                    val cy = rr.centerY() - (fm.ascent + fm.descent) / 2f
                    val hasNumberHint = metrics.numberHintsVisible && key.numberHint != null
                    val printableTextBaseline = if (hasNumberHint) {
                        calculateNumberHintedTextBaseline(
                            centeredBaseline = cy,
                            keyBottom = rr.bottom,
                            densityFloat = densityFloat,
                            fontDescent = fm.descent,
                        )
                    } else {
                        cy
                    }

                    val special = key.style == KeyStyle.SPECIAL
                    when {
                        key.code == KeyCode.SPACE -> {
                            val shiftedCx = cx + touchState.swipeOffset
                            labelPaint.color = keySpecialTextColor.toArgb()
                            canvas.nativeCanvas.drawText(
                                spaceLabel,
                                shiftedCx,
                                rr.centerY() - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f,
                                labelPaint
                            )
                            // Draw arrows
                            val labelHalf = labelPaint.measureText(spaceLabel) / 2f
                            val spaceCy = rr.centerY()
                            val s = 4f * densityFloat
                            val gap = 9f * densityFloat
                            val baseAlpha = 110
                            chevronPaint.color = keySpecialTextColor.toArgb()
                            chevronPaint.alpha = if (touchState.swipeFired || (touchState.downOnSpace && abs(touchState.swipeOffset) > 8f * densityFloat)) {
                                255
                            } else {
                                baseAlpha
                            }
                            val lx = shiftedCx - labelHalf - gap
                            canvas.nativeCanvas.drawLine(lx, spaceCy - s, lx - s, spaceCy, chevronPaint)
                            canvas.nativeCanvas.drawLine(lx - s, spaceCy, lx, spaceCy + s, chevronPaint)
                            val rx = shiftedCx + labelHalf + gap
                            canvas.nativeCanvas.drawLine(rx, spaceCy - s, rx + s, spaceCy, chevronPaint)
                            canvas.nativeCanvas.drawLine(rx + s, spaceCy, rx, spaceCy + s, chevronPaint)
                            chevronPaint.alpha = baseAlpha
                        }
                        iconResFor(key) != null -> {
                            val tint = fg
                            val res = iconResFor(key)!!
                            val d = iconCache.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }
                            d.setTint(tint)
                            val half = iconSizePx / 2f
                            val l = (rr.centerX() - half).toInt()
                            val t = (rr.centerY() - half).toInt()
                            d.setBounds(l, t, l + iconSizePx.toInt(), t + iconSizePx.toInt())
                            d.draw(canvas.nativeCanvas)
                        }
                        !key.isPrintable -> {
                            labelPaint.color = fg
                            canvas.nativeCanvas.drawText(
                                key.label,
                                cx,
                                rr.centerY() - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f,
                                labelPaint
                            )
                        }
                        else -> {
                            textPaint.color = fg
                            canvas.nativeCanvas.drawText(
                                displayLabel(key),
                                cx,
                                printableTextBaseline,
                                textPaint,
                            )
                        }
                    }

                    // Corner hints
                    val hint = hintFor(key)
                    if (hint != null) {
                        val hintPosition = if (hasNumberHint) {
                            calculateNumberHintPosition(
                                keyRect = rr,
                                densityFloat = densityFloat,
                                fontAscent = hintPaint.fontMetrics.ascent,
                            )
                        } else {
                            val pad = 5f * densityFloat
                            PointF(
                                rr.right - pad - hintPaint.textSize / 2f,
                                rr.top + pad - hintPaint.fontMetrics.ascent,
                            )
                        }
                        hintPaint.color = keyHintColor.toArgb()
                        canvas.nativeCanvas.drawText(
                            hint.toString(),
                            hintPosition.x,
                            hintPosition.y,
                            hintPaint,
                        )
                    }
                }
            }
        }
    }
}
