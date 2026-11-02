package dev.phucngu.simpletype.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import dev.phucngu.simpletype.R
import kotlin.math.roundToInt

/**
 * The floating cursor shown over the text during a trackpad-style space-drag, like the iOS
 * keyboard's: it follows the finger freely (past line ends too) while the real caret snaps to the
 * nearest position under it.
 *
 * Drawn in a popup attached to the keyboard's window, so it can sit over the app above the
 * keyboard without any extra permission. It never takes touches or focus.
 */
class GhostCursor(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val widthPx = (WIDTH_DP * density).roundToInt().coerceAtLeast(2)

    private val bar = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.kb_accent)
            alpha = (255 * OPACITY).roundToInt()
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        }
    }

    private val popup = PopupWindow(bar, widthPx, 1, false).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
        setBackgroundDrawable(null)
    }

    private val windowOrigin = IntArray(2)

    /**
     * Shows the cursor centred on screen point ([x], [centerY]), [lineHeight] tall. [anchor] is
     * any view in the keyboard's window.
     */
    fun show(anchor: View, x: Float, centerY: Float, lineHeight: Float) {
        if (anchor.windowToken == null) return
        // Popup positions are relative to the keyboard's window, the reports are in screen pixels.
        anchor.rootView.getLocationOnScreen(windowOrigin)
        val h = (lineHeight * HEIGHT_FRACTION).roundToInt().coerceAtLeast(1)
        val left = (x - widthPx / 2f).roundToInt() - windowOrigin[0]
        val top = (centerY - h / 2f).roundToInt() - windowOrigin[1]
        if (popup.isShowing) {
            popup.update(left, top, widthPx, h)
        } else {
            popup.height = h
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, left, top)
        }
    }

    fun hide() {
        if (popup.isShowing) popup.dismiss()
    }

    private companion object {
        const val WIDTH_DP = 3f
        const val HEIGHT_FRACTION = 0.85f
        const val OPACITY = 0.75f
    }
}
