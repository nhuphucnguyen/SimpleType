package dev.phucngu.simpletype.ime

import android.content.Context
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.lerp
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
/** Hold time to type a key's number/symbol hint: short enough to feel snappy, long enough not to misfire while typing. */
private const val HINT_LONG_PRESS_MS = 250L
private const val HINT_TOP_PADDING_DP = 1f
private const val HINTED_TEXT_OFFSET_DP = 2f
/** Hint icons are drawn on a 24-unit grid with a ~18-unit glyph, so this gives a ~9dp smiley. */
private const val HINT_ICON_SIZE_DP = 12f
private const val KEY_TEXT_BOTTOM_PADDING_DP = 1f
private const val GLIDE_TRAIL_POINTS = 48
private const val CURSOR_MODE_FADE_MS = 120
/** Opacity of key labels while space-drag cursor mode is active (keyboard reads as a trackpad). */
private const val CURSOR_MODE_LABEL_ALPHA = 0.15f

object LatinKeyboardView {
    const val PREF_HAPTIC = "kb_haptic"
    const val PREF_HAPTIC_STRENGTH = "kb_haptic_strength"
    const val PREF_GLIDE = "kb_glide"
    const val PREF_CURSOR_SPEED = "kb_cursor_speed"
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

/** Baseline of a number/symbol hint: horizontally centered, sitting just under the key's top edge. */
internal fun calculateHintPosition(
    keyRect: RectF,
    densityFloat: Float,
    fontAscent: Float,
): PointF = PointF(
    keyRect.centerX(),
    keyRect.top + HINT_TOP_PADDING_DP * densityFloat - fontAscent,
)

/** Baseline of the main label on a hinted key, nudged down to make room for the hint above it. */
internal fun calculateHintedTextBaseline(
    centeredBaseline: Float,
    keyBottom: Float,
    densityFloat: Float,
    fontDescent: Float,
): Float = minOf(
    centeredBaseline + HINTED_TEXT_OFFSET_DP * densityFloat,
    keyBottom - KEY_TEXT_BOTTOM_PADDING_DP * densityFloat - fontDescent,
)

/** The key typed when [key] is held: its visible [hint] if any, otherwise its long-press code. */
internal fun longPressTarget(key: Key, hint: Char?): Key? = when {
    hint != null -> Key(hint.code, hint.toString())
    key.longPressCode != null -> Key(key.longPressCode, "")
    else -> null
}

internal fun longPressDelayMs(hinted: Boolean): Long = if (hinted) HINT_LONG_PRESS_MS else LONG_PRESS_MS

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
    var spaceCursorMode by mutableStateOf(false)
    var longPressFired by mutableStateOf(false)
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
    var glideLeftCorridor = false

    fun resetGlide() {
        glideCandidate = false
        glideActive = false
        glidePath.clear()
        glideKeys.clear()
        glidePathLength = 0f
        glideLeftCorridor = false
    }
}

interface LatinKeyboardListener {
    fun onKey(key: Key)
    fun onKeyRepeat(key: Key)
    fun onSpaceSwipe(direction: Int)
    /** Hold-then-drag on space: move the cursor by [steps] characters (negative = left). */
    fun onSpaceCursorMove(steps: Int) {}
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
    val keyShadowColor = colorResource(R.color.kb_key_shadow)
    val keyShadowOffsetPx = 1f * densityFloat

    // Load Haptic player and preferences reactively
    val haptics = remember(context) { HapticPlayer(context) }
    val prefs = remember(context) { context.getSharedPreferences("simpletype_prefs", Context.MODE_PRIVATE) }
    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean(LatinKeyboardView.PREF_HAPTIC, true)) }
    var hapticPercent by remember { mutableStateOf(prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT)) }
    var cursorSpeed by remember { mutableStateOf(prefs.getFloat(LatinKeyboardView.PREF_CURSOR_SPEED, CursorSpeed.DEFAULT)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LatinKeyboardView.PREF_HAPTIC) {
                hapticEnabled = prefs.getBoolean(LatinKeyboardView.PREF_HAPTIC, true)
            } else if (key == LatinKeyboardView.PREF_HAPTIC_STRENGTH) {
                hapticPercent = prefs.getInt(LatinKeyboardView.PREF_HAPTIC_STRENGTH, LatinKeyboardView.DEFAULT_HAPTIC_PERCENT)
            } else if (key == LatinKeyboardView.PREF_CURSOR_SPEED) {
                cursorSpeed = prefs.getFloat(LatinKeyboardView.PREF_CURSOR_SPEED, CursorSpeed.DEFAULT)
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
    chevronPaint.strokeWidth = 1.5f * densityFloat

    val iconCache = remember { HashMap<Int, Drawable>() }
    // Digit bounds let hint icons sit at the same height as the number/symbol hints.
    val hintDigitBounds = remember { Rect() }
    hintPaint.getTextBounds("0", 0, 1, hintDigitBounds)

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
    val spaceDrag = remember(densityFloat, cursorSpeed) {
        SpaceDragTracker(swipeThreshold, cursorStep = CursorSpeed.stepDp(cursorSpeed) * densityFloat)
    }
    // 0 = normal typing, 1 = cursor mode: labels fade out and the space bar turns accent.
    val cursorModeProgress by animateFloatAsState(
        targetValue = if (touchState.spaceCursorMode) 1f else 0f,
        animationSpec = tween(CURSOR_MODE_FADE_MS),
        label = "cursor mode",
    )

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
            key.code == KeyCode.SHIFT && !shifted -> R.drawable.ic_kb_shift_off
            else -> key.iconRes
        }

        // Hold a key to type its hint (or long-press code); the release then types nothing.
        fun startLongPress(key: Key) {
            val hint = hintFor(key)
            val target = longPressTarget(key, hint) ?: return
            longPressJob = scope.launch {
                delay(longPressDelayMs(hinted = hint != null))
                touchState.longPressFired = true
                touchState.resetGlide()
                if (hapticEnabled) haptics.longPress(hapticStrength)
                listener.onKey(target)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(keyboard, metrics, placements, glideEnabled, spaceDrag) {
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
                                            touchState.longPressFired = false
                                            touchState.spaceCursorMode = false

                                            touchState.resetGlide()
                                            if (glideEnabled && p.key.isLetterKey() && !keyGeometry.isEmpty) {
                                                touchState.glideCandidate = true
                                                touchState.glidePath.add(GesturePoint(pos.x, pos.y))
                                                touchState.glideKeys.add(p.key.code)
                                            }

                                            if (touchState.shiftPointerId != null && !touchState.shiftUsedAsModifier) {
                                                touchState.shiftUsedAsModifier = true
                                                listener.onShiftHold(true)
                                            }

                                            if (touchState.downOnSpace) {
                                                spaceDrag.down(pos.x)
                                                longPressJob = scope.launch {
                                                    delay(LONG_PRESS_MS)
                                                    if (spaceDrag.onHoldElapsed()) {
                                                        touchState.spaceCursorMode = true
                                                        touchState.swipeOffset = 0f
                                                        if (hapticEnabled) haptics.longPress(hapticStrength)
                                                    }
                                                }
                                            } else if (p.key.repeatable) {
                                                listener.onKeyRepeat(p.key)
                                                repeatJob = scope.launch {
                                                    delay(REPEAT_INITIAL_DELAY_MS)
                                                    while (isActive) {
                                                        listener.onKeyRepeat(p.key)
                                                        hapticTap()
                                                        delay(REPEAT_INTERVAL_MS)
                                                    }
                                                }
                                            } else {
                                                startLongPress(p.key)
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
                                        if (!touchState.spaceCursorMode) {
                                            touchState.swipeOffset = pos.x - touchState.swipeStartX
                                        }
                                        when (val action = spaceDrag.move(pos.x)) {
                                            is SpaceAction.LanguageSwipe -> {
                                                longPressJob?.cancel()
                                                touchState.swipeFired = true
                                                hapticTap()
                                                listener.onSpaceSwipe(action.direction)
                                            }
                                            is SpaceAction.CursorMove -> {
                                                hapticTap()
                                                listener.onSpaceCursorMove(action.steps)
                                            }
                                            null -> {}
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

                                            val glideStart = touchState.glidePath.first()
                                            if (abs(point.x - glideStart.x) > keyGeometry.keyWidth * 0.4f) {
                                                touchState.glideLeftCorridor = true
                                            }

                                            if (!touchState.glideActive &&
                                                touchState.glideLeftCorridor &&
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

                                        if (!touchState.longPressFired && !touchState.glideActive) {
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
                                                } else if (p != null) {
                                                    startLongPress(p.key)
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
                                            val wasOnSpace = touchState.downOnSpace
                                            touchState.downOnSpace = false
                                            touchState.spaceCursorMode = false
                                            touchState.swipeOffset = 0f

                                            val glideHandled = touchState.glideActive
                                            if (glideHandled) {
                                                listener.onGlideTyped(touchState.glidePath.toList(), keyGeometry)
                                            }
                                            touchState.resetGlide()

                                            val spaceSuppressed = wasOnSpace && !spaceDrag.shouldTypeSpace()
                                            if (!glideHandled && p != null && !p.key.repeatable && !spaceSuppressed && !touchState.swipeFired && !touchState.longPressFired) {
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
                        touchState.longPressFired = false
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
                val labelAlpha = (255 * (1f - (1f - CURSOR_MODE_LABEL_ALPHA) * cursorModeProgress)).toInt()

                for (p in placements) {
                    val key = p.key
                    val r = p.rect
                    val isPressed = p == pressedPlacement || (key.code == KeyCode.SHIFT && touchState.shiftPointerId != null)
                    val active = key.code == KeyCode.SHIFT && (shifted || capsLock)
                    val isEnter = key.code == KeyCode.ENTER

                    val isSpace = key.code == KeyCode.SPACE
                    keyPaint.color = when {
                        isSpace && cursorModeProgress > 0f ->
                            lerp(if (isPressed) keyPressedColor else keyColor, accentColor, cursorModeProgress).toArgb()
                        isPressed -> keyPressedColor.toArgb()
                        active -> accentColor.toArgb()
                        isEnter -> enterColor.toArgb()
                        key.style == KeyStyle.SPECIAL -> keySpecialColor.toArgb()
                        else -> keyColor.toArgb()
                    }

                    // Foreground (glyph/text) color matching the key's role.
                    val fg = when {
                        active -> accentTextColor.toArgb()
                        isEnter -> enterTextColor.toArgb()
                        key.style == KeyStyle.SPECIAL -> keySpecialTextColor.toArgb()
                        else -> keyTextColor.toArgb()
                    }

                    val insetH = gapHorizontalPx / 2f
                    val insetV = gapVerticalPx / 2f
                    val rr = RectF(r.left + insetH, r.top + insetV, r.right - insetH, r.bottom - insetV)
                    // 1dp shadow peeking out under the key gives it a crisp tile edge.
                    val keyColorArgb = keyPaint.color
                    keyPaint.color = keyShadowColor.toArgb()
                    rr.offset(0f, keyShadowOffsetPx)
                    canvas.nativeCanvas.drawRoundRect(rr, keyRadius, keyRadius, keyPaint)
                    rr.offset(0f, -keyShadowOffsetPx)
                    keyPaint.color = keyColorArgb
                    canvas.nativeCanvas.drawRoundRect(rr, keyRadius, keyRadius, keyPaint)

                    val cx = rr.centerX()
                    val cy = rr.centerY() - (fm.ascent + fm.descent) / 2f
                    val hint = hintFor(key)
                    // Once a hold has fired, the key previews the hint it just typed.
                    val showingHint = hint != null && isPressed && touchState.longPressFired
                    val hintIconRes = if (showingHint) null else key.hintIconRes
                    val printableTextBaseline = if ((hint != null && !showingHint) || hintIconRes != null) {
                        calculateHintedTextBaseline(
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
                        isSpace -> {
                            val shiftedCx = cx + touchState.swipeOffset
                            val spaceFg = lerp(keySpecialTextColor, accentTextColor, cursorModeProgress).toArgb()
                            val spaceCy = rr.centerY()
                            // Language label cross-fades into an I-beam cursor glyph.
                            labelPaint.color = spaceFg
                            labelPaint.alpha = (255 * (1f - cursorModeProgress)).toInt()
                            canvas.nativeCanvas.drawText(
                                spaceLabel,
                                shiftedCx,
                                spaceCy - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f,
                                labelPaint
                            )
                            val beamHalfHeight = 7f * densityFloat
                            val serif = 3f * densityFloat
                            if (cursorModeProgress > 0f) {
                                chevronPaint.color = spaceFg
                                chevronPaint.alpha = (255 * cursorModeProgress).toInt()
                                val top = spaceCy - beamHalfHeight
                                val bottom = spaceCy + beamHalfHeight
                                canvas.nativeCanvas.drawLine(shiftedCx, top, shiftedCx, bottom, chevronPaint)
                                canvas.nativeCanvas.drawLine(shiftedCx - serif, top, shiftedCx + serif, top, chevronPaint)
                                canvas.nativeCanvas.drawLine(shiftedCx - serif, bottom, shiftedCx + serif, bottom, chevronPaint)
                            }
                            // Draw arrows, hugging the I-beam in cursor mode.
                            val textHalf = labelPaint.measureText(spaceLabel) / 2f
                            val labelHalf = textHalf + (serif - textHalf) * cursorModeProgress
                            val s = 4f * densityFloat
                            val gap = 9f * densityFloat
                            val baseAlpha = 110
                            chevronPaint.color = spaceFg
                            chevronPaint.alpha = if (touchState.swipeFired || touchState.spaceCursorMode || (touchState.downOnSpace && abs(touchState.swipeOffset) > 8f * densityFloat)) {
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
                            d.alpha = labelAlpha
                            val half = iconSizePx / 2f
                            val l = (rr.centerX() - half).toInt()
                            val t = (rr.centerY() - half).toInt()
                            d.setBounds(l, t, l + iconSizePx.toInt(), t + iconSizePx.toInt())
                            d.draw(canvas.nativeCanvas)
                        }
                        !key.isPrintable -> {
                            labelPaint.color = fg
                            labelPaint.alpha = labelAlpha
                            canvas.nativeCanvas.drawText(
                                key.label,
                                cx,
                                rr.centerY() - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f,
                                labelPaint
                            )
                        }
                        else -> {
                            textPaint.color = fg
                            textPaint.alpha = labelAlpha
                            canvas.nativeCanvas.drawText(
                                if (showingHint) hint.toString() else displayLabel(key, shifted, capsLock),
                                cx,
                                printableTextBaseline,
                                textPaint,
                            )
                        }
                    }

                    // Number/symbol hint, centered above the letter on a shared baseline.
                    val hintPosition = calculateHintPosition(
                        keyRect = rr,
                        densityFloat = densityFloat,
                        fontAscent = hintPaint.fontMetrics.ascent,
                    )
                    if (hintIconRes != null) {
                        val d = iconCache.getOrPut(hintIconRes) { ContextCompat.getDrawable(context, hintIconRes)!!.mutate() }
                        d.setTint(keyHintColor.toArgb())
                        d.alpha = labelAlpha
                        val half = HINT_ICON_SIZE_DP * densityFloat / 2f
                        val iconCy = hintPosition.y + hintDigitBounds.exactCenterY()
                        d.setBounds(
                            (hintPosition.x - half).toInt(),
                            (iconCy - half).toInt(),
                            (hintPosition.x + half).toInt(),
                            (iconCy + half).toInt(),
                        )
                        d.draw(canvas.nativeCanvas)
                    }
                    if (hint != null && !showingHint) {
                        hintPaint.color = keyHintColor.toArgb()
                        hintPaint.alpha = labelAlpha
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
