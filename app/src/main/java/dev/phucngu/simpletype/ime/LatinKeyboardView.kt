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
    }

    var listener: Listener? = null

    /** Label drawn on the space bar — shows the active language. */
    var spaceLabel: String = ""
        set(value) { field = value; invalidate() }

    /** Whether the mic key should render in its "listening" colour. */
    var micActive: Boolean = false
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
    private val micActiveColor = color(R.color.kb_mic_active)

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

    private data class Placement(val key: Key, val rect: RectF)

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
        val isPressed = p == pressed
        val active = (key.code == KeyCode.SHIFT && (shifted || capsLock)) ||
            (key.code == KeyCode.MIC && micActive)

        keyPaint.color = when {
            isPressed -> keyPressedColor
            active && key.code == KeyCode.MIC -> micActiveColor
            active -> accentColor
            key.code == KeyCode.ENTER -> accentColor
            key.style == KeyStyle.SPECIAL -> keySpecialColor
            else -> keyColor
        }

        val inset = keyGap / 2f
        val rr = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        canvas.drawRoundRect(rr, keyRadius, keyRadius, keyPaint)

        val cx = rr.centerX()
        val cy = rr.centerY() - (fm.ascent + fm.descent) / 2f

        val onAccent = active || key.code == KeyCode.ENTER
        when {
            key.code == KeyCode.SPACE -> {
                labelPaint.color = color(R.color.kb_key_special_text)
                canvas.drawText(spaceLabel, cx, baselineFor(labelPaint, rr), labelPaint)
            }
            iconResFor(key) != null -> {
                val tint = if (onAccent) Color.WHITE else color(R.color.kb_key_special_text)
                drawIcon(canvas, iconResFor(key)!!, rr, tint)
            }
            key.style == KeyStyle.SPECIAL || !key.isPrintable -> {
                labelPaint.color = if (onAccent) Color.WHITE else color(R.color.kb_key_special_text)
                canvas.drawText(key.label, cx, baselineFor(labelPaint, rr), labelPaint)
            }
            else -> {
                textPaint.color = if (onAccent) Color.WHITE else color(R.color.kb_key_text)
                canvas.drawText(displayLabel(key), cx, cy, textPaint)
            }
        }
    }

    /** Vertically-centered text baseline within [rr] for the given paint. */
    private fun baselineFor(paint: Paint, rr: RectF): Float {
        val fm = paint.fontMetrics
        return rr.centerY() - (fm.ascent + fm.descent) / 2f
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
            MotionEvent.ACTION_DOWN -> {
                val p = placementAt(event.x, event.y) ?: return true
                setPressed(p)
                if (p.key.repeatable) {
                    listener?.onKeyRepeat(p.key)
                    postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = placementAt(event.x, event.y)
                if (p != pressed) {
                    // Slid onto a different key: cancel any repeat and re-highlight.
                    removeCallbacks(repeatRunnable)
                    setPressed(p)
                    if (p != null && p.key.repeatable) {
                        postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatRunnable)
                val p = pressed
                setPressed(null)
                if (p != null && !p.key.repeatable) {
                    listener?.onKey(p.key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatRunnable)
                setPressed(null)
            }
        }
        return true
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
    private fun color(colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 55L
    }
}
