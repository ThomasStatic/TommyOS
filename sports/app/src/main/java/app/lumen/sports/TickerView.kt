package app.lumen.sports

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The ticker bar: your matchup pinned on the left ([BoardView]), the rest crawling right to left
 * beside it ([CrawlView]), and a status pill pinned on the right while the scores are out of date.
 * [Highlight]s briefly replace the crawl. Knows nothing about ESPN; it draws a [TickerContent].
 *
 * The crawl text is recorded once per content change; each frame only moves it (a RenderNode
 * translation), so nothing is redrawn on the UI thread while it scrolls. Redrawing the text every
 * frame cost ~23 % of a core on this TV (see REPORT.md). Everything is sized from the bar's height.
 */
class TickerView(context: Context) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private var style = Style(TickerConfig.DEFAULT_HEIGHT_DP * density)
    private var content = TickerContent(null, emptyList())
    private var opacity = TickerConfig.DEFAULT_OPACITY
    private val board = BoardView(context)
    private val crawl = CrawlView(context)
    private val status = StatusView(context)
    private val banner = BannerView(context)
    private val bg = Paint()
    private val alerts = Alerts()
    private val pump = Runnable { pumpAlerts() }

    init {
        setWillNotDraw(false)
        addView(board)
        addView(crawl)
        addView(status)
        addView(banner)
        banner.visibility = INVISIBLE
        banner.alpha = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, style.radius)
        }
        clipToOutline = true
    }

    /** Replaces the content. Identical content is ignored so polling doesn't cause redraws. */
    fun setContent(newContent: TickerContent) {
        if (newContent == content) return
        content = newContent
        board.board = newContent.board
        status.text = newContent.status
        crawl.setSegments(newContent.crawl)
        requestLayout()
        board.invalidate()
        status.invalidate()
    }

    /** Background opacity, 0–1; text stays opaque so it keeps its contrast over the picture. */
    fun setOpacity(value: Float) {
        opacity = value
        invalidate()
        board.invalidate()
    }

    /** Crawl speed, and how many frames a second it moves (out of the display's 60). */
    fun setMotion(dpPerSecond: Int, fps: Int) {
        crawl.speedPx = dpPerSecond * density
        crawl.skip = (60 / fps.coerceIn(1, 60)).coerceAtLeast(1)
        crawl.update()
    }

    /** Stops the crawl (and any banners) while the screen is off, and restarts it when it comes back on. */
    fun setScreenOn(on: Boolean) {
        if (!on) clearHighlights()
        crawl.screenOn = on
    }

    /** How long each banner stays up; 0 turns them off. */
    fun setAlertDuration(ms: Long) {
        alerts.durationMs = ms
        if (ms <= 0) clearHighlights()
    }

    /** Hands [events] to the [Alerts] pacing; ignored while the ticker isn't on screen. */
    fun offerAlerts(events: List<ScoreEvent>) {
        if (events.isEmpty() || windowVisibility != VISIBLE) return
        alerts.offer(events, SystemClock.uptimeMillis())
        pumpAlerts()
    }

    /** Puts up the next banner, or brings the crawl back, as [Alerts] decides, and comes back when it next may. */
    private fun pumpAlerts() {
        removeCallbacks(pump)
        val now = SystemClock.uptimeMillis()
        val next = alerts.next(now)
        if (next != null) {
            Log.i(TAG, "highlight: ${next.heading} ${next.chip.text} ${next.text} ${next.change}")
            banner.highlight = next
            crawl.paused = true
            banner.visibility = VISIBLE
            banner.animate().alpha(1f).setDuration(FADE_MS)
            crawl.animate().alpha(0f).setDuration(FADE_MS)
        } else if (!alerts.showing(now) && banner.highlight != null) {
            banner.highlight = null
            banner.animate().alpha(0f).setDuration(FADE_MS).withEndAction { banner.visibility = INVISIBLE }
            crawl.animate().alpha(1f).setDuration(FADE_MS).withEndAction { crawl.paused = false }
        }
        alerts.wakeAt(now)?.let { postDelayed(pump, (it - now).coerceAtLeast(MIN_WAKE_MS)) }
    }

    private fun clearHighlights() {
        removeCallbacks(pump)
        alerts.clear()
        banner.highlight = null
        banner.animate().cancel()
        crawl.animate().cancel()
        banner.alpha = 0f
        banner.visibility = INVISIBLE
        crawl.alpha = 1f
        crawl.paused = false
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        if (h > 0 && h.toFloat() != style.h) {
            style = Style(h.toFloat())
            crawl.setSegments(content.crawl, relayout = false)
            board.invalidate()
            invalidateOutline()
        }
        setMeasuredDimension(w, h)
        val boardW = board.desiredWidth()
        val statusW = status.desiredWidth()
        val crawlW = (w - boardW - statusW).coerceAtLeast(0)
        board.measure(exactly(boardW), exactly(h))
        status.measure(exactly(statusW), exactly(h))
        crawl.measure(exactly(crawlW), exactly(h))
        banner.measure(exactly(crawlW + statusW), exactly(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        board.layout(0, 0, board.measuredWidth, h)
        crawl.layout(board.measuredWidth, 0, board.measuredWidth + crawl.measuredWidth, h)
        status.layout(w - status.measuredWidth, 0, w, h)
        banner.layout(board.measuredWidth, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        bg.color = withAlpha(BG, opacity)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        Log.i(TAG, "window visibility -> ${if (visibility == VISIBLE) "VISIBLE" else visibility}")
        if (visibility != VISIBLE) clearHighlights()
    }

    override fun onDetachedFromWindow() {
        clearHighlights()
        super.onDetachedFromWindow()
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /** Your matchup: abbreviations and actual scores on the top row, projections (or FINAL) under them, a triangle pointing at the leader. */
    private inner class BoardView(context: Context) : View(context) {
        var board: Board? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        // Right edge of your score column, centre of the middle column, left edge of theirs, their abbreviation.
        private var mineRight = 0f
        private var centre = 0f
        private var theirsLeft = 0f
        private var theirAbbrev = 0f

        fun desiredWidth(): Int {
            val b = board ?: return 0
            val s = style
            // Tabular figures: the columns only change width when a score gains a digit.
            fun column(t: BoardTeam) = maxOf(s.score.measureText(t.points), t.projected?.let(s.proj::measureText) ?: 0f)
            val column = maxOf(column(b.mine), column(b.theirs))
            val middle = maxOf(s.caption.measureText("FINAL"), s.caption.measureText("PROJ"), s.triangle) + 2 * s.gapM
            mineRight = s.stripe + s.padX + s.abbrev.measureText(b.mine.abbrev) + s.gapM + column
            centre = mineRight + middle / 2
            theirsLeft = mineRight + middle
            theirAbbrev = theirsLeft + column + s.gapM
            return ceil(theirAbbrev + s.abbrev.measureText(b.theirs.abbrev) + s.padX).toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val b = board ?: return
            val s = style
            paint.color = withAlpha(PANEL, opacity)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = ACCENT
            canvas.drawRect(0f, 0f, s.stripe, height.toFloat(), paint)

            val mineBehind = b.theirs.leads
            val theirsBehind = b.mine.leads
            canvas.drawText(b.mine.abbrev, s.stripe + s.padX, s.scoreBase, if (mineBehind) s.abbrevDim else s.abbrev)
            drawRight(canvas, b.mine.points, mineRight, s.scoreBase, if (mineBehind) s.scoreDim else s.score)
            canvas.drawText(b.theirs.points, theirsLeft, s.scoreBase, if (theirsBehind) s.scoreDim else s.score)
            canvas.drawText(b.theirs.abbrev, theirAbbrev, s.scoreBase, if (theirsBehind) s.abbrevDim else s.abbrev)
            if (b.mine.leads || b.theirs.leads) {
                paint.color = WHITE
                triangle(canvas, path, paint, centre, s.scoreBase - s.scoreCap / 2, s.triangle, if (b.mine.leads) LEFT else RIGHT)
            }

            val caption = when {
                b.final -> "FINAL"
                b.mine.projected != null || b.theirs.projected != null -> "PROJ"
                else -> null
            }
            caption?.let { canvas.drawText(it, centre - s.caption.measureText(it) / 2, s.projBase, s.caption) }
            b.mine.projected?.let { drawRight(canvas, it, mineRight, s.projBase, s.proj) }
            b.theirs.projected?.let { canvas.drawText(it, theirsLeft, s.projBase, s.proj) }
        }
    }

    /** The scrolling region: clips [strip] to the space between the board and the status pill, and moves it once per frame. */
    private inner class CrawlView(context: Context) : ViewGroup(context), Choreographer.FrameCallback {
        val strip = CrawlStrip(context)
        var speedPx = TickerConfig.DEFAULT_SPEED_DP * density
        /** Moves on every [skip]th vsync: each frame costs this TV a few percent of a core in the app and in SurfaceFlinger. */
        var skip = 1
        private var vsyncs = 0
        var paused = false
            set(value) { field = value; update() }
        /** False while the TV sleeps: the window stays "visible" then, and frame callbacks kept the UI thread at ~5 % of a core. */
        var screenOn = true
            set(value) { field = value; update() }
        private var offset = 0f
        private var lastFrame = 0L
        private var running = false

        init { addView(strip) }

        /** [relayout]: false when already measuring (a new bar height re-lays the same text). */
        fun setSegments(segments: List<Segment>, relayout: Boolean = true) {
            strip.lay(segments)
            if (relayout) requestLayout()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            // Text that fits stands still; text that doesn't is drawn twice, back to back, so it loops seamlessly.
            strip.copies = if (strip.contentWidth > measuredWidth - 2 * style.padX) 2 else 1
            val w = if (strip.copies == 2) strip.loop + strip.contentWidth else strip.contentWidth
            strip.measure(exactly(ceil(w).toInt()), exactly(measuredHeight))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val pad = style.padX.roundToInt()
            strip.layout(pad, 0, pad + strip.measuredWidth, b - t)
            // New content keeps its place in the loop, so a score update doesn't restart the crawl.
            offset = if (strip.copies == 2) offset % strip.loop else 0f
            strip.translationX = -offset.roundToInt().toFloat()
            update()
        }

        fun update() {
            val go = speedPx > 0 && !paused && screenOn && strip.copies == 2 && isAttachedToWindow && windowVisibility == VISIBLE
            if (go == running) return
            running = go
            val choreographer = Choreographer.getInstance()
            if (go) {
                lastFrame = 0L
                choreographer.postFrameCallback(this)
            } else {
                choreographer.removeFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (++vsyncs % skip != 0) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            if (lastFrame != 0L) {
                val dt = ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(MAX_STEP_S)
                offset = (offset + speedPx * dt) % strip.loop
            }
            lastFrame = frameTimeNanos
            // Whole pixels: glyphs stay crisp instead of shimmering between sub-pixel positions.
            strip.translationX = -offset.roundToInt().toFloat()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            update()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            update()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    /** The crawl's text, recorded once per content change; [CrawlView] only moves it. */
    private inner class CrawlStrip(context: Context) : View(context) {
        private val pieces = ArrayList<Piece>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var contentWidth = 0f
            private set
        val loop get() = contentWidth + style.sectionGap
        var copies = 1
            set(value) { if (field != value) { field = value; invalidate() } }

        fun lay(segments: List<Segment>) {
            val s = style
            pieces.clear()
            var x = 0f
            fun text(t: String, p: Paint) {
                pieces += Piece(TEXT, x, t, p)
                x += p.measureText(t)
            }
            segments.forEachIndexed { i, seg ->
                if (i > 0 && seg.heading != null) {
                    x += s.sectionGap / 2
                    pieces += Piece(DIVIDER, x)
                    x += s.sectionGap / 2
                } else if (i > 0) {
                    x += s.segmentGap
                }
                seg.heading?.let {
                    if (seg.live) {
                        pieces += Piece(LIVE_DOT, x + s.liveDot)
                        x += 2 * s.liveDot + s.gapS
                    }
                    text(it, s.heading)
                    x += s.gapM
                }
                seg.chip?.let {
                    val w = s.chip.measureText(it.text) + 2 * s.chipPad
                    pieces += Piece(if (it.mine) CHIP_MINE else CHIP_THEIRS, x, it.text, w = w)
                    x += w + s.gapM
                }
                seg.entries.forEachIndexed { j, e ->
                    if (j > 0) {
                        x += s.entryGap / 2
                        pieces += Piece(DOT, x)
                        x += s.entryGap / 2
                    }
                    text(e.label, s.name)
                    if (e.tag.isNotEmpty()) { x += s.gapS; text(e.tag, s.tag) }
                    if (e.value.isNotEmpty()) {
                        x += s.gapM
                        if (e.projected) { text("PROJ", s.caption); x += s.gapS; text(e.value, s.projValue) } else text(e.value, s.value)
                    }
                }
            }
            contentWidth = x
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val s = style
            val mid = s.crawlBase - s.nameCap / 2
            for (copy in 0 until copies) {
                val dx = copy * loop
                for (p in pieces) {
                    val x = p.x + dx
                    when (p.kind) {
                        TEXT -> canvas.drawText(p.text, x, s.crawlBase, p.paint!!)
                        DOT -> { paint.color = SEPARATOR; canvas.drawCircle(x, mid, s.dot, paint) }
                        DIVIDER -> divider(canvas, x, mid)
                        LIVE_DOT -> { paint.color = LIVE; canvas.drawCircle(x, s.crawlBase - s.headingCap / 2, s.liveDot, paint) }
                        CHIP_MINE, CHIP_THEIRS -> chip(canvas, paint, rect, s, x, mid, p.w, p.text, p.kind == CHIP_MINE, ACCENT, WHITE, OUTLINE, WHITE, s.chip)
                    }
                }
                if (copies > 1) divider(canvas, dx + contentWidth + s.sectionGap / 2, mid)
            }
        }

        private fun divider(canvas: Canvas, x: Float, mid: Float) {
            paint.color = RULE
            val half = style.h * 0.2f
            canvas.drawRect(x - style.rule / 2, mid - half, x + style.rule / 2, mid + half, paint)
        }
    }

    /** "AS OF 4:12 PM" (and any ESPN problem) in an amber pill pinned at the right end. */
    private inner class StatusView(context: Context) : View(context) {
        var text: String? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun desiredWidth(): Int {
            val t = text ?: return 0
            return ceil(style.status.measureText(t) + 2 * style.chipPad + style.gapM + style.padX).toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val t = text ?: return
            val s = style
            val mid = height / 2f
            rect.set(s.gapM, mid - s.chipH / 2, width - s.padX, mid + s.chipH / 2)
            paint.color = AMBER
            canvas.drawRoundRect(rect, s.chipRadius, s.chipRadius, paint)
            canvas.drawText(t, rect.left + s.chipPad, mid + s.statusCap / 2, s.status)
        }
    }

    /** A [Highlight] filling the crawl region: gold when it is good news for you, crimson when not. */
    private inner class BannerView(context: Context) : View(context) {
        var highlight: Highlight? = null
            set(value) { field = value; if (value != null) invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val h = highlight ?: return
            val s = style
            val fill = if (h.good) GOLD else CRIMSON
            val ink = if (h.good) INK else WHITE
            paint.color = fill
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val mid = s.crawlBase - s.nameCap / 2
            var x = s.padX
            fun text(t: String, p: Paint, alpha: Float = 1f) {
                p.color = withAlpha(ink, alpha)
                canvas.drawText(t, x, s.crawlBase, p)
                x += p.measureText(t)
            }
            text(h.heading, s.bannerHeading)
            x += s.gapL
            val chipW = s.chip.measureText(h.chip.text) + 2 * s.chipPad
            chip(canvas, paint, rect, s, x, mid, chipW, h.chip.text, h.chip.mine, ink, fill, ink, ink, s.bannerChip)
            x += chipW + s.gapM
            text(h.text, s.bannerName)
            if (h.tag.isNotEmpty()) { x += s.gapS; text(h.tag, s.bannerTag, 0.75f) }
            if (h.change.isNotEmpty()) {
                x += s.gapL
                h.up?.let {
                    paint.color = ink
                    triangle(canvas, path, paint, x + s.triangle / 2, mid, s.triangle, if (it) UP else DOWN)
                    x += s.triangle + s.gapS
                }
                text(h.change, s.bannerValue)
            }
            x += s.gapL
            text(h.detail, s.bannerDetail, 0.85f)
        }
    }

    private class Piece(val kind: Int, val x: Float, val text: String = "", val paint: Paint? = null, val w: Float = 0f)

    companion object {
        private const val TAG = "LumenSports"
        private const val FADE_MS = 250L
        private const val MIN_WAKE_MS = 16L
        /** A frame that comes late (or the first after a pause) moves the crawl at most this far, rather than jumping. */
        private const val MAX_STEP_S = 0.05f

        private const val TEXT = 0
        private const val DOT = 1
        private const val DIVIDER = 2
        private const val LIVE_DOT = 3
        private const val CHIP_MINE = 4
        private const val CHIP_THEIRS = 5

        private const val LEFT = 0
        private const val RIGHT = 1
        private const val UP = 2
        private const val DOWN = 3

        private const val BG = 0xFF0A0F1C.toInt()
        private const val PANEL = 0xFF182544.toInt()
        private const val ACCENT = 0xFF3B7BFF.toInt()
        private const val HEADING = 0xFFA8C4FF.toInt()
        private const val PROJECTED = 0xFF7FD3FF.toInt()
        private const val LIVE = 0xFFFF4B4B.toInt()
        private const val AMBER = 0xFFFFB627.toInt()
        private const val GOLD = 0xFFFFC53D.toInt()
        private const val CRIMSON = 0xFFC8283C.toInt()
        private const val INK = 0xFF10131A.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SEPARATOR = 0x80FFFFFF.toInt()
        private const val RULE = 0x4DFFFFFF
        private const val OUTLINE = 0xB3FFFFFF.toInt()

        private fun withAlpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

        private fun drawRight(canvas: Canvas, text: String, right: Float, baseline: Float, paint: Paint) =
            canvas.drawText(text, right - paint.measureText(text), baseline, paint)

        /** A team chip centred on [mid]: filled with [fill] for your team, outlined in [outline] for theirs. */
        private fun chip(
            canvas: Canvas, paint: Paint, rect: RectF, s: Style, x: Float, mid: Float, w: Float, text: String, mine: Boolean,
            fill: Int, onFill: Int, outline: Int, onOutline: Int, textPaint: Paint,
        ) {
            rect.set(x, mid - s.chipH / 2, x + w, mid + s.chipH / 2)
            if (mine) {
                paint.color = fill
                canvas.drawRoundRect(rect, s.chipRadius, s.chipRadius, paint)
                textPaint.color = onFill
            } else {
                paint.color = outline
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s.rule
                rect.inset(s.rule / 2, s.rule / 2)
                canvas.drawRoundRect(rect, s.chipRadius, s.chipRadius, paint)
                paint.style = Paint.Style.FILL
                textPaint.color = onOutline
            }
            canvas.drawText(text, x + s.chipPad, mid + s.chipCap / 2, textPaint)
        }

        private fun triangle(canvas: Canvas, path: Path, paint: Paint, cx: Float, cy: Float, size: Float, direction: Int) {
            val r = size / 2
            path.reset()
            when (direction) {
                LEFT -> { path.moveTo(cx - r, cy); path.lineTo(cx + r, cy - r); path.lineTo(cx + r, cy + r) }
                RIGHT -> { path.moveTo(cx + r, cy); path.lineTo(cx - r, cy - r); path.lineTo(cx - r, cy + r) }
                UP -> { path.moveTo(cx, cy - r); path.lineTo(cx + r, cy + r * 0.8f); path.lineTo(cx - r, cy + r * 0.8f) }
                else -> { path.moveTo(cx, cy + r); path.lineTo(cx + r, cy - r * 0.8f); path.lineTo(cx - r, cy - r * 0.8f) }
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Paints and sizes for one bar height [h] (px). Sizes are in [u], one dp at the default
     * height, so the height setting scales the whole ticker, text included.
     */
    private class Style(val h: Float) {
        val u = h / TickerConfig.DEFAULT_HEIGHT_DP

        val score = text(28f, WHITE, BOLD)
        val scoreDim = text(28f, 0xB3FFFFFF.toInt(), BOLD)
        val abbrev = text(17f, WHITE, CONDENSED_BOLD, 0.04f)
        val abbrevDim = text(17f, 0xB3FFFFFF.toInt(), CONDENSED_BOLD, 0.04f)
        // Projections: larger than before (14 arcmin at 2.5 m on a 40" TV was below the ~16 needed) and
        // in their own colour, so white always means points scored and blue means projected.
        val proj = text(20f, PROJECTED, MEDIUM)
        val caption = text(14f, 0xB3FFFFFF.toInt(), CONDENSED_BOLD, 0.14f)
        val name = text(23f, 0xF5FFFFFF.toInt(), REGULAR)
        val value = text(23f, WHITE, BOLD)
        val projValue = text(23f, PROJECTED, REGULAR)
        val tag = text(16f, 0xA6FFFFFF.toInt(), MEDIUM)
        val heading = text(15f, HEADING, CONDENSED_BOLD, 0.14f)
        val chip = text(15f, WHITE, CONDENSED_BOLD, 0.06f)
        val status = text(14f, INK, CONDENSED_BOLD, 0.1f)
        val bannerHeading = text(16f, INK, CONDENSED_BOLD, 0.16f)
        val bannerChip = text(15f, INK, CONDENSED_BOLD, 0.06f)
        val bannerName = text(23f, INK, BOLD)
        val bannerTag = text(15f, INK, MEDIUM)
        val bannerValue = text(25f, INK, BOLD)
        val bannerDetail = text(17f, INK, MEDIUM)

        val radius = 10 * u
        val padX = 18 * u
        val stripe = 5 * u
        val gapS = 7 * u
        val gapM = 12 * u
        val gapL = 20 * u
        val entryGap = 30 * u
        val segmentGap = 30 * u
        val sectionGap = 60 * u
        val chipH = 26 * u
        val chipPad = 9 * u
        val chipRadius = 5 * u
        val rule = 1.5f * u
        val dot = 2.5f * u
        val liveDot = 5 * u
        val triangle = 12 * u

        val nameCap = cap(name)
        val headingCap = cap(heading)
        val chipCap = cap(chip)
        val statusCap = cap(status)
        val scoreCap = cap(score)
        /** Single-line crawl text, centred on its capital height. */
        val crawlBase = h / 2 + nameCap / 2
        /** The board's two rows (score, projection) centred as one block. */
        val scoreBase: Float
        val projBase: Float

        init {
            val rowGap = 6 * u
            val block = scoreCap + rowGap + cap(proj)
            scoreBase = (h - block) / 2 + scoreCap
            projBase = (h + block) / 2
        }

        private fun text(size: Float, color: Int, face: Typeface, spacing: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * u
            this.color = color
            typeface = face
            letterSpacing = spacing
            fontFeatureSettings = "tnum" // equal-width digits, so scores don't wobble as they change
        }

        private fun cap(p: Paint) = Rect().also { p.getTextBounds("H", 0, 1, it) }.height().toFloat()

        companion object {
            private val REGULAR = Typeface.create("sans-serif", Typeface.NORMAL)
            private val MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            private val BOLD = Typeface.create("sans-serif", Typeface.BOLD)
            private val CONDENSED_BOLD = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
    }
}
