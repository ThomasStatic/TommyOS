package app.lumen.sports

import kotlin.math.abs
import kotlin.math.sign

/**
 * A change in your matchup between ESPN updates that may deserve a banner. Raw numbers only: a
 * fantasy-point delta says nothing about which NFL play caused it (one play, several, or a stat
 * correction), so [Alerts] words it as points, never as a touchdown or a play.
 */
sealed class ScoreEvent {
    abstract val key: String
}

/** A starter's points moving by [Highlights.THRESHOLD] or more, from [from] to [to]. */
data class PointsChange(val team: String, val mine: Boolean, val name: String, val position: String, val from: Double, val to: Double) : ScoreEvent() {
    override val key get() = "$team|$name|$position"
    /** In hundredths: ESPN scores to two decimals, and doubles would make 14.7 − 10.7 fall short of 4. */
    val change get() = hundredths(to) - hundredths(from)
}

/** The lead passing to the other team (ties in between don't count as a change). */
data class LeadChange(val mineLeads: Boolean, val leader: String, val mine: Double, val theirs: Double) : ScoreEvent() {
    override val key get() = "lead"
}

/**
 * Turns successive snapshots of your matchup into [ScoreEvent]s. Each snapshot is compared with the
 * one before, so an identical (duplicate) response, a re-render or a restart from the cached snapshot
 * reports nothing new. Comparisons are skipped across a week change, a different matchup, or a gap of
 * more than [Highlights.MAX_GAP_MS] (screen off, ticker hidden, long backoff): that change is history.
 * Starters who weren't starters before (lineup changes) are skipped too. Not thread-safe.
 */
class ScoreWatch(private var last: Snapshot? = null) {
    /** The last team to lead outright: 1 you, -1 them, 0 unknown. Kept through ties. */
    private var leader = last?.mine?.let(::lead) ?: 0

    fun update(next: Snapshot): List<ScoreEvent> {
        val before = last
        last = next
        val now = next.mine ?: return emptyList<ScoreEvent>().also { leader = 0 }
        val was = before?.mine
        val leads = lead(now)
        val comparable = was != null && before.week == next.week && next.fetchedAt - before.fetchedAt in 1..Highlights.MAX_GAP_MS &&
            was.left.abbrev == now.left.abbrev && was.right.abbrev == now.right.abbrev
        if (!comparable) {
            leader = leads
            return emptyList()
        }
        val out = ArrayList<ScoreEvent>()
        for ((old, new, mine) in listOf(Triple(was!!.left, now.left, true), Triple(was.right, now.right, false))) {
            val previous = old.starters.associateBy { it.name to it.position }
            for (p in new.starters) {
                val from = previous[p.name to p.position]?.points ?: continue
                val e = PointsChange(new.abbrev, mine, p.name, p.position, from, p.points)
                if (Highlights.significant(e)) out += e
            }
        }
        out.sortByDescending { abs((it as PointsChange).change) }
        if (leads != 0) {
            if (leader != 0 && leads != leader) {
                out += LeadChange(leads > 0, if (leads > 0) now.left.abbrev else now.right.abbrev, now.left.points, now.right.points)
            }
            leader = leads
        }
        return out
    }
}

/**
 * Paces [ScoreEvent]s into [Highlight] banners so a burst of scoring doesn't keep interrupting the
 * crawl. Events wait in a queue where a starter's later change merges into his pending one (a gain
 * reversed before it is shown cancels out, like a pending lead change that is reversed). Banners
 * come in runs of at most [MAX_RUN], each [durationMs] long, then the crawl gets at least
 * [MIN_CRAWL_MS] before the next run; anything still waiting after [MAX_AGE_MS] is dropped as old
 * news. Driven by the caller's clock (ms), so it is testable; [TickerView] runs it on the UI thread.
 */
class Alerts(var durationMs: Long = DEFAULT_DURATION_MS) {
    private class Pending(var event: ScoreEvent, val since: Long)

    private val pending = ArrayList<Pending>()
    private var shownAt = 0L
    private var shown = false
    private var run = 0
    private var crawlUntil = 0L

    fun offer(events: List<ScoreEvent>, now: Long) {
        if (durationMs <= 0) return
        for (e in events) {
            val i = pending.indexOfFirst { it.event.key == e.key }
            if (i < 0) {
                pending += Pending(e, now)
                continue
            }
            val merged = merge(pending[i].event, e)
            if (merged == null) pending.removeAt(i) else pending[i].event = merged
        }
        while (pending.size > MAX_PENDING) {
            // Keep the lead change and the biggest swings.
            pending.remove(pending.filter { it.event is PointsChange }.minByOrNull { abs((it.event as PointsChange).change) } ?: pending.first())
        }
    }

    /** Whether the banner put up last is still within its time at [now]. */
    fun showing(now: Long) = shown && now < shownAt + durationMs

    /**
     * The banner to put up at [now], or null to let the crawl run: nothing waiting, the current banner
     * still has time, or the crawl is owed time after a run.
     */
    fun next(now: Long): Highlight? {
        if (showing(now)) return null
        pending.removeAll { now - it.since > MAX_AGE_MS }
        if (shown) {
            shown = false
            if (pending.isEmpty() || run >= MAX_RUN) {
                run = 0
                crawlUntil = shownAt + durationMs + MIN_CRAWL_MS
            }
        }
        if (pending.isEmpty() || now < crawlUntil || durationMs <= 0) return null
        val e = pending.removeAt(0).event
        shown = true
        shownAt = now
        run++
        return Highlights.present(e)
    }

    /** When to call [next] again, or null when nothing is up or waiting. */
    fun wakeAt(now: Long): Long? = when {
        showing(now) -> shownAt + durationMs
        pending.isEmpty() -> null
        else -> maxOf(now, crawlUntil)
    }

    fun clear() {
        pending.clear()
        shown = false
        run = 0
    }

    private fun merge(old: ScoreEvent, new: ScoreEvent): ScoreEvent? = when {
        old is PointsChange && new is PointsChange -> old.copy(to = new.to).takeIf(Highlights::significant)
        old is LeadChange && new is LeadChange -> null // the lead went back before anyone saw it change
        else -> new
    }

    companion object {
        const val DEFAULT_DURATION_MS = 5_000L
        const val MAX_RUN = 2
        /** Thirty seconds: replaying real refreshes, ten let one matchup put up four banners in 36 s. */
        const val MIN_CRAWL_MS = 30_000L
        const val MAX_AGE_MS = 60_000L
        const val MAX_PENDING = 4
    }
}

object Highlights {
    /** Points (either way) one starter must move between updates: 4, a passing touchdown's worth in ESPN's default scoring. */
    const val THRESHOLD = 4.0
    /** Updates further apart than this aren't compared. Live polling is every 30 s; backoff after errors reaches 4 min by the third. */
    const val MAX_GAP_MS = 5 * 60_000L

    fun significant(e: PointsChange) = abs(e.change) >= hundredths(THRESHOLD)

    /** Words an event as points, never as a play: the delta alone can't say what happened. */
    fun present(e: ScoreEvent): Highlight = when (e) {
        is PointsChange -> {
            val up = e.change > 0
            val (name, position) = TickerItems.shortName(e.name, e.position)
            Highlight(
                heading = if (up) "POINTS UP" else "POINTS DOWN",
                chip = Chip(e.team, e.mine),
                text = name,
                tag = position,
                change = (if (up) "+" else "−") + TickerItems.pts(abs(e.change) / 100.0),
                detail = "${TickerItems.pts(e.to)} PTS",
                up = up,
                good = up == e.mine,
            )
        }
        is LeadChange -> Highlight(
            heading = "LEAD CHANGE",
            chip = Chip(e.leader, e.mineLeads),
            text = "takes the lead",
            tag = "",
            change = "",
            detail = "${TickerItems.pts(e.mine)} – ${TickerItems.pts(e.theirs)}",
            up = null,
            good = e.mineLeads,
        )
    }
}

private fun hundredths(x: Double) = Math.round(x * 100)

/** 1 when you lead, -1 when your opponent does, 0 when tied, to a tenth as the board shows it. */
private fun lead(m: Matchup) = (Math.round(m.left.points * 10) - Math.round(m.right.points * 10)).sign
