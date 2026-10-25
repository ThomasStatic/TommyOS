package app.lumen.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One line of ticker text; [emphasis] lines (your matchup, status messages) are drawn brighter and
 * bolder. [newPage] starts a new ticker page even if the line would fit on the current one.
 */
data class TickerItem(val text: String, val emphasis: Boolean = false, val newPage: Boolean = false)

/**
 * Turns a [Snapshot] into the lines the ticker pages through: your matchup with full team names,
 * then both teams' starters grouped live, played, to play and on a bye (one page or more per group), then
 * the rest of the league abbreviated.
 */
object TickerItems {
    /** [staleAfterMs]: age past which the scores say when they are from; the service ties it to its poll cadence. */
    fun from(snapshot: Snapshot, now: Instant, zone: ZoneId, staleAfterMs: Long = STALE_MS): List<TickerItem> {
        val items = ArrayList<TickerItem>()
        val m = snapshot.mine
        items += TickerItem("Week ${snapshot.week}   " + if (m == null) "No matchup this week" else "${score(m, full = true)}${status(m)}", emphasis = true)
        if (m != null) for (game in GROUPS) {
            val start = items.size
            for (side in listOf(m.left, m.right)) {
                side.starters.filter { it.game == game }.chunked(PLAYERS_PER_ITEM).forEach { group ->
                    items += TickerItem("${side.abbrev} ${LABELS.getValue(game)}   " + group.joinToString(SEPARATOR) { player(it) })
                }
            }
            if (items.size > start) items[start] = items[start].copy(newPage = true)
        }
        // Other matchups skip projections to stay short.
        snapshot.others.forEachIndexed { i, o -> items += TickerItem(score(o) + if (o.final) "   Final" else "", newPage = i == 0) }
        if (items.isNotEmpty() && now.toEpochMilli() - snapshot.fetchedAt > staleAfterMs) {
            val at = TIME.format(Instant.ofEpochMilli(snapshot.fetchedAt).atZone(zone)).lowercase(Locale.ROOT)
            items[0] = items[0].copy(text = "${items[0].text}   as of $at")
        }
        return items
    }

    /**
     * "Josh Allen", "QB" -> "J. Allen QB 21.4"; a defence's name already says D/ST, so it isn't
     * repeated. Players still to play show their projection instead of their (zero) points; players
     * on a bye show neither.
     */
    private fun player(p: PlayerLine): String {
        val parts = p.name.trim().split(' ', limit = 2)
        val who = when {
            p.name.endsWith(p.position) -> p.name
            parts.size == 2 -> "${parts[0].first()}. ${parts[1]} ${p.position}"
            else -> "${p.name} ${p.position}"
        }
        return when (p.game) {
            Game.BYE -> who
            Game.UPCOMING -> if (p.projected != null) "$who proj ${pts(p.projected)}" else who
            else -> "$who ${pts(p.points)}"
        }
    }

    private fun score(m: Matchup, full: Boolean = false): String {
        fun name(s: Side) = if (full) s.name else s.abbrev
        return "${name(m.left)} ${pts(m.left.points)} – ${pts(m.right.points)} ${name(m.right)}"
    }

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
    private val GROUPS = listOf(Game.LIVE, Game.PLAYED, Game.UPCOMING, Game.BYE, Game.UNKNOWN)
    private val LABELS = mapOf(
        Game.LIVE to "live", Game.PLAYED to "played", Game.UPCOMING to "to play", Game.BYE to "on bye", Game.UNKNOWN to "starters",
    )
}
