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
import dev.phucngu.simpletype.gesture.GesturePoint
import dev.phucngu.simpletype.gesture.KeyGeometry
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode
import dev.phucngu.simpletype.ime.keyboard.model.KeyStyle
import dev.phucngu.simpletype.ime.keyboard.model.Keyboard
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
private const val GLIDE_TRAIL_POINTS = 48
private const val HINT_FLICK_MAX_MS = 250L

object LatinKeyboardView {
    const val PREF_HAPTIC = "kb_haptic"
    const val PREF_HAPTIC_STRENGTH = "kb_haptic_strength"
    const val PREF_GLIDE = "kb_glide"
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
    val fixedColumns = keyboard.fixedColumns
    if (fixedColumns != null) {
        val unit = widthPx / fixedColumns
        val occupiedUntilRow = IntArray(fixedColumns)
        keyboard.rows.forEachIndexed { rowIndex, row ->
            var column = 0
            for (key in row.keys) {
                while (column < fixedColumns && occupiedUntilRow[column] > rowIndex) column++
                val columnSpan = key.weight.toInt().coerceAtLeast(1)
                require(column + columnSpan <= fixedColumns) { "Keyboard row exceeds fixed grid" }
                val top = vPadPx + rowIndex * rowHeightPx
                list.add(Placement(key, RectF(
                    column * unit,
                    top,
                    (column + columnSpan) * unit,
                    top + key.rowSpan * rowHeightPx,
                )))
                for (c in column until column + columnSpan) {
                    occupiedUntilRow[c] = rowIndex + key.rowSpan
                }
                column += columnSpan
            }
        }
        return list
    }
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

internal fun displayLabel(key: Key, shifted: Boolean, capsLock: Boolean): String {
    if (key.isPrintable && (shifted || capsLock)) {
        val character = key.code.toChar()
        if (character.isLetter()) return character.uppercaseChar().toString()
    }
    return key.label
}

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

    // Glide (swipe-to-type) tracking
    var glideCandidate = false
    var glideActive by mutableStateOf(false)
    val glidePath = ArrayList<GesturePoint>()
    val glideKeys = HashSet<Int>()
    var glidePathLength = 0f
    var glideStartHint: Char? = null
    var glideDownTimeMs = 0L

    fun resetGlide() {
        glideCandidate = false
        glideActive = false
        glidePath.clear()
        glideKeys.clear()
        glidePathLength = 0f
        glideStartHint = null
        glideDownTimeMs = 0L
    }
}

interface LatinKeyboardListener {
    fun onKey(key: Key)
    fun onKeyRepeat(key: Key)
    fun onSpaceSwipe(direction: Int)
    fun onShiftHold(active: Boolean)
    /** A completed swipe-to-type gesture over the letter keys. */
    fun onGlideTyped(path: List<GesturePoint>, geometry: KeyGeometry) {}
}

private fun Key.isLetterKey(): Boolean = isPrintable && code in 'a'.code..'z'.code

@Composable
fun LatinKeyboard(
    keyboard: Keyboard,
    metrics: KeyboardMetrics,
    spaceLabel: String,
    shifted: Boolean,
    capsLock: Boolean,
    listener: LatinKeyboardListener,
    modifier: Modifier = Modifier,
    glideEnabled: Boolean = false,
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
    var glideTrailTick by remember { mutableStateOf(0) }
    val trailPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

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

        val keyGeometry = remember(placements) {
            val letters = placements.filter { it.key.isLetterKey() }
            if (letters.isEmpty()) {
                KeyGeometry(emptyMap(), 0f, 0f)
            } else {
                KeyGeometry(
                    letters.associate {
                        it.key.code.toChar() to GesturePoint(it.rect.centerX(), it.rect.centerY())
                    },
                    letters.map { it.rect.width() }.average().toFloat(),
                    letters.map { it.rect.height() }.average().toFloat(),
                )
            }
        }
        val glideActivationThreshold = keyGeometry.keyWidth * 0.75f

        fun hintFor(key: Key): Char? = when {
            metrics.numberHintsVisible -> key.numberHint
            metrics.showSymbolHints -> key.symbolHint
            else -> null
        }

        fun iconResFor(key: Key): Int? = when {
            key.code == KeyCode.SHIFT && capsLock -> R.drawable.ic_kb_shift_lock
            else -> key.iconRes
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(keyboard, metrics, placements, glideEnabled) {
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

                                            touchState.resetGlide()
                                            if (glideEnabled && p.key.isLetterKey() && !keyGeometry.isEmpty) {
                                                touchState.glideCandidate = true
                                                touchState.glidePath.add(GesturePoint(pos.x, pos.y))
                                                touchState.glideKeys.add(p.key.code)
                                                touchState.glideStartHint = hintFor(p.key)
                                                touchState.glideDownTimeMs = change.uptimeMillis
                                            }

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
                                        if (touchState.glideCandidate) {
                                            val point = GesturePoint(pos.x, pos.y)
                                            val last = touchState.glidePath.lastOrNull()
                                            val step = last?.distanceTo(point) ?: 0f
                                            if (last == null || step > 0f) {
                                                touchState.glidePath.add(point)
                                                touchState.glidePathLength += step
                                            }
                                            placements.firstOrNull {
                                                it.key.isLetterKey() && it.rect.contains(pos.x, pos.y)
                                            }?.let { touchState.glideKeys.add(it.key.code) }

                                            if (!touchState.glideActive &&
                                                touchState.glideKeys.size >= 2 &&
                                                touchState.glidePathLength >= glideActivationThreshold
                                            ) {
                                                touchState.glideActive = true
                                                repeatJob?.cancel()
                                                longPressJob?.cancel()
                                                pressedPlacement = null
                                            }
                                            if (touchState.glideActive) {
                                                glideTrailTick++
                                                activeChange.consume()
                                            }
                                        }

                                        val hint = pressedPlacement?.key?.let { hintFor(it) }
                                        if (!touchState.glideCandidate && hint != null &&
                                            !touchState.numberSwipeFired && !touchState.swipeFired
                                        ) {
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

                                        if (!touchState.numberSwipeFired && !touchState.glideActive) {
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

                                            // Resolve a glide gesture: either a deferred
                                            // vertical hint-flick or a swipe-typed word.
                                            var glideHandled = false
                                            if (touchState.glideCandidate && touchState.glidePath.size >= 2) {
                                                val start = touchState.glidePath.first()
                                                val endPoint = touchState.glidePath.last()
                                                val netDy = endPoint.y - start.y
                                                val netDx = endPoint.x - start.x
                                                val hint = touchState.glideStartHint
                                                val isHintFlick = hint != null &&
                                                    netDy >= numberSwipeThreshold &&
                                                    netDy >= abs(netDx) &&
                                                    abs(netDx) <= keyGeometry.keyWidth * 0.4f &&
                                                    change.uptimeMillis - touchState.glideDownTimeMs <= HINT_FLICK_MAX_MS &&
                                                    touchState.glidePathLength <= keyGeometry.keyHeight * 1.8f &&
                                                    touchState.glideKeys.size <= 2
                                                if (isHintFlick) {
                                                    glideHandled = true
                                                    hapticTap()
                                                    listener.onKey(Key(hint!!.code, hint.toString()))
                                                } else if (touchState.glideActive) {
                                                    glideHandled = true
                                                    listener.onGlideTyped(touchState.glidePath.toList(), keyGeometry)
                                                }
                                            }
                                            touchState.resetGlide()

                                            if (!glideHandled && p != null && !p.key.repeatable && !touchState.swipeFired && !touchState.longPressFired && !numberFired) {
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
                        touchState.resetGlide()
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
                                displayLabel(key, shifted, capsLock),
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

                // Glide-typing trail: newest segments opaque, fading toward the tail.
                if (touchState.glideActive && glideTrailTick >= 0) {
                    val trail = touchState.glidePath
                    if (trail.size >= 2) {
                        val visible = trail.takeLast(GLIDE_TRAIL_POINTS)
                        trailPaint.color = accentColor.toArgb()
                        trailPaint.strokeWidth = keyGeometry.keyWidth * 0.14f
                        for (i in 1 until visible.size) {
                            trailPaint.alpha = (255f * i / visible.size).toInt().coerceIn(30, 255)
                            canvas.nativeCanvas.drawLine(
                                visible[i - 1].x, visible[i - 1].y,
                                visible[i].x, visible[i].y,
                                trailPaint,
                            )
                        }
                    }
                }
            }
        }
    }
}
