package org.hogel.droidconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Decorative overlay that draws the IME's composing (preedit) string pinned to
 * the bottom-left of the terminal area, just above the soft keyboard. Sits on
 * top of the [com.termux.view.TerminalView] in the layout and consumes no
 * input — it only paints.
 *
 * Cursor-tracking placement was tried first but TUI apps like Claude Code
 * leave the emulator cursor at column 0 while drawing their own visible input
 * cursor, which made the preedit appear to drift away from where the user was
 * typing. A fixed bottom-anchored slot is predictable and stays in the user's
 * line of sight while typing.
 */
class PreeditOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var composing: CharSequence = ""
    private var cellHeightPx: Float = 0f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xCC, 0x20, 0x20, 0x20)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        // Will be overwritten when the host reports cell size.
        textSize = 32f
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }

    init {
        // We never want to swallow touches — the user should still be able to
        // tap the terminal underneath even while preedit is showing.
        isFocusable = false
        isClickable = false
        isFocusableInTouchMode = false
        // Drawing is gated on having a non-empty composing string; until then
        // the view is essentially invisible.
    }

    /**
     * Update the composing text. [cellHeightPx] is the terminal's line height
     * so the preedit reads naturally next to confirmed terminal text.
     */
    fun setComposing(text: CharSequence, cellHeightPx: Float) {
        this.composing = text
        this.cellHeightPx = cellHeightPx
        if (cellHeightPx > 0f) {
            // Slight shrink keeps descenders inside the box.
            textPaint.textSize = cellHeightPx * 0.9f
            underlinePaint.strokeWidth = (cellHeightPx * 0.06f).coerceAtLeast(1.5f)
        }
        invalidate()
    }

    fun clear() {
        if (composing.isEmpty()) return
        composing = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (composing.isEmpty() || cellHeightPx <= 0f) return

        val text = composing.toString()
        val textWidth = textPaint.measureText(text)
        val padX = cellHeightPx * 0.25f
        val padY = cellHeightPx * 0.1f

        // Pin to the bottom-left of the overlay (== bottom of the terminal,
        // which adjustResize keeps directly above the soft keyboard).
        val boxHeight = cellHeightPx + padY * 2
        val boxWidth = (textWidth + padX * 2).coerceAtMost(width.toFloat())
        val boxLeft = 0f
        val boxTop = (height - boxHeight).coerceAtLeast(0f)

        val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRect(rect, backgroundPaint)

        // Baseline so the glyph sits inside the box with a little headroom.
        val baseline = boxTop + cellHeightPx + padY - (cellHeightPx * 0.18f)
        canvas.drawText(text, boxLeft + padX, baseline, textPaint)

        val underlineY = boxTop + boxHeight - padY * 0.5f
        canvas.drawLine(
            boxLeft + padX,
            underlineY,
            boxLeft + padX + textWidth,
            underlineY,
            underlinePaint,
        )
    }
}
