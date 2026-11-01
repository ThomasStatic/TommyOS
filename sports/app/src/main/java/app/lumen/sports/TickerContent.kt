package app.lumen.sports

/**
 * What the ticker draws, already worded: your matchup pinned on the left ([board]), the [crawl]
 * that scrolls past it, and a [status] pinned on the right while the scores are out of date.
 * Free of ESPN types; [TickerItems] builds it from a [Snapshot].
 */
data class TickerContent(val board: Board?, val crawl: List<Segment>, val status: String? = null)

/** Your matchup, [mine] on the left. A [final] board shows FINAL instead of projections. */
data class Board(val mine: BoardTeam, val theirs: BoardTeam, val final: Boolean)

/** One side of the [Board]: actual [points] (large), [projected] final (small, beneath), and whether it [leads]. */
data class BoardTeam(val abbrev: String, val points: String, val projected: String?, val leads: Boolean)

/**
 * A run of the crawl: an optional [heading] (LIVE, TO PLAY…, with a red dot when [live]) that
 * starts a new section, an optional team [chip], then its [entries].
 */
data class Segment(val heading: String? = null, val chip: Chip? = null, val entries: List<Entry>, val live: Boolean = false)

/** A team tag. Yours is drawn filled and your opponent's outlined, so they differ by shape, not only colour. */
data class Chip(val text: String, val mine: Boolean)

/** One crawl entry: a [label] (player or league score), a small [tag] (position, FINAL) and a bold [value]; a [projected] value is dimmed and marked PROJ. */
data class Entry(val label: String, val tag: String = "", val value: String = "", val projected: Boolean = false)

/**
 * A significant change between two ESPN updates, shown over the crawl for a few seconds.
 * [up]: points gained (▲) or lost (▼), null for no arrow; [good]: good news for you.
 */
data class Highlight(
    val heading: String,
    val chip: Chip,
    val text: String,
    val tag: String,
    val change: String,
    val detail: String,
    val up: Boolean?,
    val good: Boolean,
)
