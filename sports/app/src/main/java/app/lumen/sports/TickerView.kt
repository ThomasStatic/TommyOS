package app.lumen.sports

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.Log
import android.view.View

/**
 * The ticker bar: a label chip, then pages of [TickerItem]s. Each page holds as many items as fit
 * (an item marked [TickerItem.newPage] always starts one),
 * stays still for [PAGE_MS] and crossfades to the next in [FADE_MS]. Between fades nothing is
 * redrawn: continuous scrolling cost ~23 % of a core plus compositor load on this TV (see REPORT.md).
 * Knows nothing about ESPN; it just shows whatever items it is given.
 */
class TickerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = BG }
    private val edge = Paint().apply { color = EDGE }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
    }
    private val strong = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 19 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        textSize = 19 * density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val sep = TextPaint(body).apply { color = 0x66FFFFFF }
    private val chipRect = RectF()

    private var items: List<TickerItem> = emptyList()
    private var pages: List<List<TickerItem>> = emptyList()
    private var page = 0
    private var fade = 1f
    private var fader: ValueAnimator? = null

    private val advance = object : Runnable {
        override fun run() {
            if (pages.size > 1) {
                fader?.cancel()
                fader = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS).apply {
                    var switched = false
                    addUpdateListener {
                        val f = it.animatedFraction
                        if (!switched && f >= 0.5f) { switched = true; page = (page + 1) % pages.size }
                        fade = if (f < 0.5f) 1f - f * 2 else (f - 0.5f) * 2
                        invalidate()
                    }
                    start()
                }
            }
            postDelayed(this, PAGE_MS)
        }
    }

    /** Replaces the content. Identical content is ignored so polling doesn't cause redraws. */
    fun setItems(newItems: List<TickerItem>) {
        if (newItems == items) return
        items = newItems
        repaginate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = repaginate()

    private fun repaginate() {
        val available = width - textStart() - PAD_DP * density
        if (available <= 0) return
        val gap = sep.measureText(SEPARATOR)
        val out = ArrayList<List<TickerItem>>()
        var current = ArrayList<TickerItem>()
        var used = 0f
        for (item in items) {
            val w = paint(item).measureText(item.text)
            val needed = if (current.isEmpty()) w else used + gap + w
            if (current.isNotEmpty() && (item.newPage || needed > available)) {
                out += current
                current = ArrayList()
                used = w
            } else {
                used = needed
            }
            current += item
        }
        if (current.isNotEmpty()) out += current
        pages = out
        page = page.coerceAtMost((pages.size - 1).coerceAtLeast(0))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        canvas.drawRect(0f, 0f, w, density, edge)

        val pad = PAD_DP * density
        chipRect.set(pad, h * 0.2f, pad + label.measureText(LABEL) + 24 * density, h * 0.8f)
        canvas.drawRoundRect(chipRect, chipRect.height() / 2, chipRect.height() / 2, chip)
        canvas.drawText(LABEL, chipRect.left + 12 * density, baseline(label, h), label)

        val current = pages.getOrNull(page) ?: return
        val alpha = (fade * 255).toInt()
        var x = textStart()
        val right = w - pad
        current.forEachIndexed { i, item ->
            if (i > 0) {
                sep.alpha = (alpha * 0x66) / 255
                canvas.drawText(SEPARATOR, x, baseline(sep, h), sep)
                x += sep.measureText(SEPARATOR)
            }
            val p = paint(item)
            val restore = p.alpha
            p.alpha = (alpha * restore) / 255
            // Only a single over-long item can exceed the width; it is cut with an ellipsis.
            val text = TextUtils.ellipsize(item.text, p, right - x, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, x, baseline(p, h), p)
            p.alpha = restore
            x += p.measureText(text)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        Log.i(TAG, "window visibility -> ${if (visibility == VISIBLE) "VISIBLE" else visibility}")
        removeCallbacks(advance)
        if (visibility == VISIBLE) postDelayed(advance, PAGE_MS) else { fader?.cancel(); fade = 1f }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(advance)
        fader?.cancel()
        super.onDetachedFromWindow()
    }

    private fun paint(item: TickerItem) = if (item.emphasis) strong else body

    private fun textStart() = PAD_DP * density + label.measureText(LABEL) + 24 * density + 20 * density

    private fun baseline(p: Paint, h: Float) = h / 2 - (p.descent() + p.ascent()) / 2

    companion object {
        private const val TAG = "LumenSports"
        private const val LABEL = "FANTASY"
        private const val SEPARATOR = "    |    "
        private const val PAGE_MS = 6_000L
        private const val FADE_MS = 250L
        private const val PAD_DP = 24
        private const val BG = 0xF00A0D14.toInt()
        private const val EDGE = 0x2EFFFFFF
        private const val ACCENT = 0xFF2F6BFF.toInt()
    }
}
