package app.lumen.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of ticker text; [emphasis] lines (your matchup, status messages) are drawn brighter and bolder. */
data class TickerItem(val text: String, val emphasis: Boolean = false)

/** Turns a [Snapshot] into the lines the ticker pages through, your matchup first. */
object TickerItems {
    /** [staleAfterMs]: age past which the scores say when they are from; the service ties it to its poll cadence. */
    fun from(snapshot: Snapshot, now: Instant, zone: ZoneId, staleAfterMs: Long = STALE_MS): List<TickerItem> {
        val items = ArrayList<TickerItem>()
        val m = snapshot.mine
        items += TickerItem("Week ${snapshot.week}   " + if (m == null) "Bye week" else "${score(m)}${status(m)}", emphasis = true)
        snapshot.livePlayers.chunked(PLAYERS_PER_ITEM).forEach { group ->
            items += TickerItem("Live   " + group.joinToString(SEPARATOR) { player(it) })
        }
        // Other matchups skip projections to stay short.
        snapshot.others.forEach { m -> items += TickerItem(score(m) + if (m.final) "   Final" else "") }
        if (items.isNotEmpty() && now.toEpochMilli() - snapshot.fetchedAt > staleAfterMs) {
            val at = TIME.format(Instant.ofEpochMilli(snapshot.fetchedAt).atZone(zone)).lowercase(Locale.ROOT)
            items[0] = items[0].copy(text = "${items[0].text}   as of $at")
        }
        return items
    }

    /** "Josh Allen", "QB" -> "J. Allen QB"; a defence's name already says D/ST, so it isn't repeated. */
    private fun player(p: PlayerLine): String {
        if (p.name.endsWith(p.position)) return "${p.name} ${pts(p.points)}"
        val parts = p.name.trim().split(' ', limit = 2)
        val short = if (parts.size == 2) "${parts[0].first()}. ${parts[1]}" else p.name
        return "$short ${p.position} ${pts(p.points)}"
    }

    private fun score(m: Matchup) = "${m.left.team} ${pts(m.left.points)} – ${pts(m.right.points)} ${m.right.team}"

    private fun status(m: Matchup) = when {
        m.final -> "   Final"
        m.left.projected != null && m.right.projected != null -> "   proj ${pts(m.left.projected)} – ${pts(m.right.projected)}"
        else -> ""
    }

    private fun pts(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    /** Live polling refreshes every 30 s, so anything past a few misses is labelled with its time. */
    const val STALE_MS = 3 * 60_000L
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private const val PLAYERS_PER_ITEM = 3
    private const val SEPARATOR = "   ·   "
}
