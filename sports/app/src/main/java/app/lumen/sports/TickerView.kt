package app.lumen.sports

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.View

/**
 * A static test ticker: a dark bar with a label chip and text scrolling right to left.
 * Drawn directly on the canvas (no layout pass per frame) and only animated while its window is
 * visible, so it costs nothing when the system hides it. [frames] counts draws for the
 * service's heartbeat, which shows whether the overlay is still being composed.
 */
class TickerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = BG }
    private val edge = Paint().apply { color = EDGE }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textSize = 19 * density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val chipRect = RectF()
    private val loopWidth = body.measureText(TEXT)
    private var offset = 0f
    private var lastFrameAt = 0L

    var scrolling = true
        set(value) { field = value; lastFrameAt = 0L; invalidate() }

    var frames = 0
        private set

    fun takeFrames(): Int = frames.also { frames = 0 }

    override fun onDraw(canvas: Canvas) {
        frames++
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        canvas.drawRect(0f, 0f, w, density, edge)

        val pad = 24 * density
        val chipW = label.measureText(LABEL) + 24 * density
        chipRect.set(pad, h * 0.2f, pad + chipW, h * 0.8f)
        canvas.drawRoundRect(chipRect, chipRect.height() / 2, chipRect.height() / 2, chip)
        canvas.drawText(LABEL, chipRect.left + 12 * density, centreBaseline(label, h), label)

        val start = chipRect.right + 20 * density
        canvas.save()
        canvas.clipRect(start, 0f, w, h)
        var x = start - offset
        val y = centreBaseline(body, h)
        while (x < w) {
            canvas.drawText(TEXT, x, y, body)
            x += loopWidth
        }
        canvas.restore()

        if (scrolling && windowVisibility == VISIBLE) {
            val now = SystemClock.uptimeMillis()
            if (lastFrameAt != 0L) offset = (offset + SPEED_DP_PER_S * density * (now - lastFrameAt) / 1000f) % loopWidth
            lastFrameAt = now
            postInvalidateOnAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        Log.i(TAG, "window visibility -> ${if (visibility == VISIBLE) "VISIBLE" else visibility}")
        lastFrameAt = 0L
        if (visibility == VISIBLE) invalidate()
    }

    private fun centreBaseline(p: Paint, h: Float) = h / 2 - (p.descent() + p.ascent()) / 2

    companion object {
        private const val TAG = "LumenSports"
        private const val LABEL = "LUMEN SPORTS · TEST"
        private const val TEXT = "Static test data  ·  Team A 104.2 – Team B 98.7 Final  ·  " +
            "Your QB 21.4 pts  ·  Team C 87.1 – Team D 90.3 Q3  ·  Projected 112.5  ·  "
        private const val SPEED_DP_PER_S = 60f
        private const val BG = 0xF00A0D14.toInt()
        private const val EDGE = 0x2EFFFFFF
        private const val ACCENT = 0xFF2F6BFF.toInt()
    }
}
