package dev.phucngu.simpletype.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.phucngu.simpletype.R

/**
 * A lightweight, fully custom-drawn keyboard view.
 *
 * The platform [android.inputmethodservice.KeyboardView] is deprecated, so we render keys
 * ourselves: each [Key]'s rectangle is laid out by row using its [Key.weight], drawn as a
 * rounded rect with a centered label, and hit-tested on touch. Backspace auto-repeats while
 * held. The owning IME wires up behaviour through [listener].
 */
class LatinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onKey(key: Key)
        /** Fired repeatedly while a repeatable key (backspace) is held down. */
        fun onKeyRepeat(key: Key)
        /** Horizontal swipe across the space bar to switch language ([direction]: -1 left, +1 right). */
        fun onSpaceSwipe(direction: Int)
        /**
         * Shift used as a held modifier: fired with true when a second finger presses another key
         * while Shift is physically held, and false when Shift is released. Lets the user hold Shift
         * and tap Delete (word-delete) or letters (caps) without the one-shot toggle being consumed.
         */
        fun onShiftHold(active: Boolean) {}
    }

    var listener: Listener? = null

    /** Label drawn on the space bar — shows the active language. */
    var spaceLabel: String = ""
        set(value) { field = value; invalidate() }

    var shifted: Boolean = false
        set(value) { field = value; invalidate() }

    var capsLock: Boolean = false
        set(value) { field = value; invalidate() }

    var keyboard: Keyboard = KeyboardLayouts.qwerty()
        set(value) {
            field = value
            requestLayout()
            layoutKeys()
            invalidate()
        }

    private val rowHeight = dp(R.dimen.kb_row_height)
    private val keyGap = dp(R.dimen.kb_key_gap)
    private val keyRadius = dp(R.dimen.kb_key_radius)
    private val vPad = dp(R.dimen.kb_vertical_padding)

    private val iconSize = dp(R.dimen.kb_icon_size)
    private val iconCache = HashMap<Int, Drawable>()

    private val placements = ArrayList<Placement>()
    private var pressed: Placement? = null

    private val bgColor = color(R.color.kb_background)
    private val keyColor = color(R.color.kb_key)
    private val keyPressedColor = color(R.color.kb_key_pressed)
    private val keySpecialColor = color(R.color.kb_key_special)
    private val accentColor = color(R.color.kb_accent)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = color(R.color.kb_key_text)
        textSize = dp(R.dimen.kb_key_text_size)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = color(R.color.kb_key_special_text)
        textSize = dp(R.dimen.kb_key_label_text_size)
    }
    // Faint chevrons flanking the space-bar label, hinting the swipe-to-switch-language gesture.
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = color(R.color.kb_key_special_text)
        alpha = 110
    }

    /** Minimum horizontal travel on the space bar that counts as a language-switch swipe. */
    private val swipeThreshold = dpf(28f)

    private data class Placement(val key: Key, val rect: RectF)

    // Space-bar swipe tracking: when the touch starts on space, a far-enough horizontal drag
    // switches language instead of committing a space.
    private var downOnSpace = false
    private var swipeStartX = 0f
    private var swipeFired = false
    private var swipeOffset = 0f

    // Multi-touch: Shift can be held with one finger while another finger taps keys. The Shift
    // pointer is tracked separately from the single "active" pointer that drives normal keys.
    private var shiftPointerId = INVALID_POINTER
    private var shiftKey: Key? = null
    private var shiftUsedAsModifier = false
    private var activePointerId = INVALID_POINTER

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            listener?.onKeyRepeat(p.key)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // ---- Measurement & layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (keyboard.rows.size * rowHeight + vPad * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        placements.clear()
        if (width == 0) return
        val usable = width.toFloat()
        var top = vPad
        for (rowObj in keyboard.rows) {
            val totalWeight = rowObj.keys.sumOf { it.weight.toDouble() }.toFloat()
            var left = 0f
            for (key in rowObj.keys) {
                val keyWidth = usable * (key.weight / totalWeight)
                placements.add(Placement(key, RectF(left, top, left + keyWidth, top + rowHeight)))
                left += keyWidth
            }
            top += rowHeight
        }
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val fm = textPaint.fontMetrics
        for (p in placements) {
            drawKey(canvas, p, fm)
        }
    }

    private fun drawKey(canvas: Canvas, p: Placement, fm: Paint.FontMetrics) {
        val key = p.key
        val r = p.rect
        val isPressed = p == pressed || (key.code == KeyCode.SHIFT && shiftPointerId != INVALID_POINTER)
        val active = key.code == KeyCode.SHIFT && (shifted || capsLock)

        keyPaint.color = when {
            isPressed -> keyPressedColor
            active -> accentColor
            key.style == KeyStyle.SPECIAL -> keySpecialColor
            else -> keyColor
        }

        val inset = keyGap / 2f
        val rr = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        canvas.drawRoundRect(rr, keyRadius, keyRadius, keyPaint)

        val cx = rr.centerX()
        val cy = rr.centerY() - (fm.ascent + fm.descent) / 2f

        val special = key.style == KeyStyle.SPECIAL
        when {
            key.code == KeyCode.SPACE -> {
                val shiftedCx = cx + swipeOffset
                labelPaint.color = color(R.color.kb_key_special_text)
                canvas.drawText(spaceLabel, shiftedCx, baselineFor(labelPaint, rr), labelPaint)
                drawSpaceArrows(canvas, rr, shiftedCx)
            }
            iconResFor(key) != null -> {
                val tint = if (active) Color.WHITE else color(R.color.kb_key_special_text)
                drawIcon(canvas, iconResFor(key)!!, rr, tint)
            }
            !key.isPrintable -> {
                // Control keys with word labels (?123, ABC, =\<) use the smaller label paint.
                labelPaint.color = if (active) Color.WHITE else color(R.color.kb_key_special_text)
                canvas.drawText(key.label, cx, baselineFor(labelPaint, rr), labelPaint)
            }
            else -> {
                // Printable glyphs always draw at full size; special keys (comma, period)
                // just take the muted special-text colour.
                textPaint.color = when {
                    active -> Color.WHITE
                    special -> color(R.color.kb_key_special_text)
                    else -> color(R.color.kb_key_text)
                }
                canvas.drawText(displayLabel(key), cx, cy, textPaint)
            }
        }
    }

    /** Vertically-centered text baseline within [rr] for the given paint. */
    private fun baselineFor(paint: Paint, rr: RectF): Float {
        val fm = paint.fontMetrics
        return rr.centerY() - (fm.ascent + fm.descent) / 2f
    }

    /** Draw small ‹ › chevrons just outside the space label to hint the swipe gesture. */
    private fun drawSpaceArrows(canvas: Canvas, rr: RectF, cx: Float) {
        val labelHalf = labelPaint.measureText(spaceLabel) / 2f
        val cy = rr.centerY()
        val s = dpf(4f)             // chevron half-height / width
        val gap = dpf(9f)           // distance from label edge to the chevron base

        // Highlight the arrows more clearly when a swipe is in progress or just fired.
        val baseAlpha = 110
        chevronPaint.alpha = if (swipeFired || (downOnSpace && kotlin.math.abs(swipeOffset) > dpf(8f))) {
            255
        } else {
            baseAlpha
        }

        val lx = cx - labelHalf - gap
        canvas.drawLine(lx, cy - s, lx - s, cy, chevronPaint)
        canvas.drawLine(lx - s, cy, lx, cy + s, chevronPaint)
        val rx = cx + labelHalf + gap
        canvas.drawLine(rx, cy - s, rx + s, cy, chevronPaint)
        canvas.drawLine(rx + s, cy, rx, cy + s, chevronPaint)

        chevronPaint.alpha = baseAlpha
    }

    /** Shift swaps to a caps-lock glyph; other keys use their declared icon. */
    private fun iconResFor(key: Key): Int? = when {
        key.code == KeyCode.SHIFT && capsLock -> R.drawable.ic_kb_shift_lock
        else -> key.iconRes
    }

    private fun drawIcon(canvas: Canvas, res: Int, rr: RectF, tint: Int) {
        val d = iconCache.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }
        d.setTint(tint)
        val half = iconSize / 2f
        val l = (rr.centerX() - half).toInt()
        val t = (rr.centerY() - half).toInt()
        d.setBounds(l, t, l + iconSize.toInt(), t + iconSize.toInt())
        d.draw(canvas)
    }

    private fun displayLabel(key: Key): String {
        if (key.isPrintable && (shifted || capsLock)) {
            val c = key.code.toChar()
            if (c.isLetter()) return c.uppercaseChar().toString()
        }
        return key.label
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val p = placementAt(x, y) ?: return true
                when {
                    // First finger on Shift becomes a held modifier (not committed until release).
                    p.key.code == KeyCode.SHIFT && shiftPointerId == INVALID_POINTER -> {
                        shiftPointerId = id
                        shiftKey = p.key
                        shiftUsedAsModifier = false
                        invalidate()
                    }
                    // A normal key: only one active pointer drives taps/repeat/space-swipe.
                    activePointerId == INVALID_POINTER -> {
                        activePointerId = id
                        beginActiveKey(p, x)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = if (activePointerId != INVALID_POINTER) event.findPointerIndex(activePointerId) else -1
                if (ai < 0) return true
                val x = event.getX(ai)
                if (downOnSpace) {
                    swipeOffset = x - swipeStartX
                    // Stay locked on space and fire one language switch once the drag is far enough.
                    if (!swipeFired && kotlin.math.abs(swipeOffset) >= swipeThreshold) {
                        swipeFired = true
                        listener?.onSpaceSwipe(if (swipeOffset > 0) 1 else -1)
                    }
                    invalidate()
                    return true
                }
                val p = placementAt(x, event.getY(ai))
                if (p != pressed) {
                    // Slid onto a different key: cancel any repeat and re-highlight.
                    removeCallbacks(repeatRunnable)
                    setPressed(p)
                    if (p != null && p.key.repeatable) {
                        postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                when (id) {
                    shiftPointerId -> releaseShift()
                    activePointerId -> {
                        removeCallbacks(repeatRunnable)
                        val p = pressed
                        setPressed(null)
                        activePointerId = INVALID_POINTER
                        downOnSpace = false
                        swipeOffset = 0f
                        // A swipe already switched language, so don't also commit a space.
                        if (p != null && !p.key.repeatable && !swipeFired) {
                            listener?.onKey(p.key)
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatRunnable)
                setPressed(null)
                activePointerId = INVALID_POINTER
                downOnSpace = false
                swipeOffset = 0f
                if (shiftPointerId != INVALID_POINTER) {
                    if (shiftUsedAsModifier) listener?.onShiftHold(false)
                    shiftPointerId = INVALID_POINTER
                    shiftUsedAsModifier = false
                    invalidate()
                }
            }
        }
        return true
    }

    /** Start tracking a normal key under the active pointer. Arms Shift-hold if Shift is down. */
    private fun beginActiveKey(p: Placement, x: Float) {
        setPressed(p)
        downOnSpace = p.key.code == KeyCode.SPACE
        swipeStartX = x
        swipeFired = false
        swipeOffset = 0f
        // Pressing any key while Shift is physically held turns Shift into a modifier (no toggle).
        if (shiftPointerId != INVALID_POINTER && !shiftUsedAsModifier) {
            shiftUsedAsModifier = true
            listener?.onShiftHold(true)
        }
        if (p.key.repeatable) {
            listener?.onKeyRepeat(p.key)
            postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
        }
    }

    /** Release the held Shift: deactivate the modifier if it was used, else fire the normal toggle. */
    private fun releaseShift() {
        val key = shiftKey
        shiftPointerId = INVALID_POINTER
        shiftKey = null
        if (shiftUsedAsModifier) {
            listener?.onShiftHold(false)
        } else if (key != null) {
            listener?.onKey(key)
        }
        shiftUsedAsModifier = false
        invalidate()
    }

    private fun setPressed(p: Placement?) {
        if (pressed != p) {
            pressed = p
            invalidate()
        }
    }

    private fun placementAt(x: Float, y: Float): Placement? =
        placements.firstOrNull { it.rect.contains(x, y) }

    // ---- Helpers ----

    private fun dp(dimenRes: Int): Float = resources.getDimension(dimenRes)
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density
    private fun color(colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 55L
        private const val INVALID_POINTER = -1
    }
}
