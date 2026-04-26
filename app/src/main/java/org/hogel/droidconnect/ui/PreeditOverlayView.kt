package org.hogel.droidconnect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * Decorative overlay that draws the IME's composing (preedit) string pinned to
 * the bottom-left of the terminal area, just above the soft keyboard. Sits on
 * top of the [com.termux.view.TerminalView] in the layout and consumes no
 * input — it only paints.
 *
 * The colour and text size match the shortcut/aux key bar (Material
 * `colorSurfaceVariant` / `colorOnSurfaceVariant`, 16sp) so the preedit reads
 * as a peer of those rows rather than as terminal content.
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

    private val textSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        TEXT_SIZE_SP,
        resources.displayMetrics,
    )

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@PreeditOverlayView,
            MaterialR.attr.colorSurfaceVariant,
        )
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@PreeditOverlayView,
            MaterialR.attr.colorOnSurfaceVariant,
        )
        textSize = textSizePx
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@PreeditOverlayView,
            MaterialR.attr.colorOnSurfaceVariant,
        )
        strokeWidth = (textSizePx * 0.06f).coerceAtLeast(1.5f)
    }

    init {
        // We never want to swallow touches — the user should still be able to
        // tap the terminal underneath even while preedit is showing.
        isFocusable = false
        isClickable = false
        isFocusableInTouchMode = false
    }

    fun setComposing(text: CharSequence) {
        this.composing = text
        invalidate()
    }

    fun clear() {
        if (composing.isEmpty()) return
        composing = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (composing.isEmpty()) return

        val text = composing.toString()
        val textWidth = textPaint.measureText(text)
        val padX = textSizePx * 0.5f
        val padY = textSizePx * 0.3f

        // Pin to the bottom-left of the overlay (== bottom of the terminal,
        // which adjustResize keeps directly above the soft keyboard).
        val boxHeight = textSizePx + padY * 2
        val boxWidth = (textWidth + padX * 2).coerceAtMost(width.toFloat())
        val boxLeft = 0f
        val boxTop = (height - boxHeight).coerceAtLeast(0f)

        val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRect(rect, backgroundPaint)

        val metrics = textPaint.fontMetrics
        val baseline = boxTop + padY - metrics.ascent
        canvas.drawText(text, boxLeft + padX, baseline, textPaint)

        val underlineY = boxTop + boxHeight - padY * 0.4f
        canvas.drawLine(
            boxLeft + padX,
            underlineY,
            boxLeft + padX + textWidth,
            underlineY,
            underlinePaint,
        )
    }

    private companion object {
        // Matches the aux/context shortcut buttons in TerminalActivity.
        const val TEXT_SIZE_SP = 16f
    }
}
