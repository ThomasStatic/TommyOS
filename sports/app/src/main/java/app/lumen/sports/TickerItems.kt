package app.lumen.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Turns a [Snapshot] into [TickerContent]: your matchup for the pinned board, then the crawl, which
 * has both teams' starters grouped live, still to play, played and on a bye (yours before your
 * opponent's), then the rest of the league.
 */
object TickerItems {
    /** A problem to show: [text] in place of the scores when there are none, [short] in the status pill beside them. */
    data class Notice(val text: String, val short: String)

    /** [staleAfterMs]: age past which the status pill says when the scores are from; the service ties it to its poll cadence. */
    fun from(snapshot: Snapshot?, notice: Notice?, now: Instant, zone: ZoneId, staleAfterMs: Long = STALE_MS): TickerContent {
        if (snapshot == null) return TickerContent(null, listOf(Segment(entries = listOf(Entry(notice?.text ?: LOADING)))))
        val m = snapshot.mine
        val crawl = ArrayList<Segment>()
        if (m == null) crawl += Segment("WEEK ${snapshot.week}", entries = listOf(Entry("No matchup this week")))
        else for (game in GROUPS) {
            var heading: String? = LABELS.getValue(game)
            for ((side, mine) in listOf(m.left to true, m.right to false)) {
                val players = side.starters.filter { it.game == game }
                if (players.isEmpty()) continue
                crawl += Segment(heading, Chip(side.abbrev, mine), players.map(::entry), live = heading != null && game == Game.LIVE)
                heading = null // Once per group; the team chips separate the two sides.
            }
        }
        // Other matchups skip projections to stay short.
        if (snapshot.others.isNotEmpty()) crawl += Segment("LEAGUE", entries = snapshot.others.map { o ->
            Entry("${o.left.abbrev} ${pts(o.left.points)} – ${pts(o.right.points)} ${o.right.abbrev}", tag = if (o.final) "FINAL" else "")
        })
        val stale = now.toEpochMilli() - snapshot.fetchedAt > staleAfterMs
        val status = if (stale || notice != null) {
            val at = TIME.format(Instant.ofEpochMilli(snapshot.fetchedAt).atZone(zone))
            listOfNotNull(notice?.short, "as of $at").joinToString(" · ").uppercase(Locale.ROOT)
        } else null
        return TickerContent(m?.let(::board), crawl, status)
    }

    /**
     * "Josh Allen", "QB" -> "J. Allen" tagged "QB"; a defence's name already says D/ST, so it isn't
     * repeated.
     */
    fun shortName(name: String, position: String): Pair<String, String> {
        val parts = name.trim().split(' ', limit = 2)
        return when {
            name.endsWith(position) -> name to ""
            parts.size == 2 -> "${parts[0].first()}. ${parts[1]}" to position
            else -> name to position
        }
    }

    fun pts(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    /** Players still to play show their projection instead of their (zero) points; players on a bye show neither. */
    private fun entry(p: PlayerLine): Entry {
        val (name, position) = shortName(p.name, p.position)
        return when (p.game) {
            Game.BYE -> Entry(name, position)
            Game.UPCOMING -> Entry(name, position, p.projected?.let(::pts) ?: "", projected = p.projected != null)
            else -> Entry(name, position, pts(p.points))
        }
    }

    private fun board(m: Matchup): Board {
        // Compared as shown, so a lead never points at two equal-looking scores.
        fun tenths(s: Side) = (s.points * 10).roundToLong()
        fun team(s: Side, other: Side) = BoardTeam(s.abbrev, pts(s.points), s.projected?.takeIf { !m.final }?.let(::pts), tenths(s) > tenths(other))
        return Board(team(m.left, m.right), team(m.right, m.left), m.final)
    }

    /** Live polling refreshes every 30 s, so anything past a few misses is labelled with its time. */
    const val STALE_MS = 3 * 60_000L
    private const val LOADING = "Loading ESPN fantasy scores…"
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    /** Players in action first, then those still to come; finished and idle ones after. */
    private val GROUPS = listOf(Game.LIVE, Game.UPCOMING, Game.PLAYED, Game.BYE, Game.UNKNOWN)
    private val LABELS = mapOf(
        Game.LIVE to "LIVE", Game.UPCOMING to "TO PLAY", Game.PLAYED to "PLAYED", Game.BYE to "ON BYE", Game.UNKNOWN to "STARTERS",
    )
}
