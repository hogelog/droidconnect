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
 * Decorative overlay that draws the IME's composing (preedit) string near the
 * terminal cursor. Sits on top of the [com.termux.view.TerminalView] in the
 * layout and consumes no input — it only paints.
 *
 * The host computes the cursor pixel position from the emulator state (cursor
 * row/col, scroll offset, cell width/height) and calls [setComposing] each
 * time the IME updates the composition. When the composition is committed or
 * cleared, callers pass an empty string and the overlay disappears.
 */
class PreeditOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var composing: CharSequence = ""
    private var cursorPxX: Float = 0f
    private var cursorPxY: Float = 0f
    private var cellWidthPx: Float = 0f
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
     * Update the composing text and where it should appear. [cursorPxX] /
     * [cursorPxY] mark the top-left of the cell at which the terminal cursor
     * currently sits.
     */
    fun setComposing(
        text: CharSequence,
        cursorPxX: Float,
        cursorPxY: Float,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ) {
        this.composing = text
        this.cursorPxX = cursorPxX
        this.cursorPxY = cursorPxY
        this.cellWidthPx = cellWidthPx
        this.cellHeightPx = cellHeightPx
        if (cellHeightPx > 0f) {
            // Match terminal cell height so preedit reads naturally next to
            // confirmed text. Slight shrink keeps descenders inside the box.
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
        val padX = cellWidthPx * 0.25f
        val padY = cellHeightPx * 0.1f

        // Default position: directly under the cursor cell. Flip above the
        // cursor when there isn't enough room below (last line, large preedit).
        val boxHeight = cellHeightPx + padY * 2
        val boxWidth = textWidth + padX * 2

        var boxLeft = cursorPxX
        var boxTop = cursorPxY + cellHeightPx
        if (boxTop + boxHeight > height) {
            boxTop = (cursorPxY - boxHeight).coerceAtLeast(0f)
        }
        if (boxLeft + boxWidth > width) {
            boxLeft = (width - boxWidth).coerceAtLeast(0f)
        }

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
