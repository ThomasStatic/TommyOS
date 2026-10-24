package app.lumen.sports

/**
 * One fetch of the league, reduced to what the ticker shows. Immutable and free of ESPN types, so
 * rendering never depends on the API's shape; [EspnParser] builds it, [SnapshotCache] persists it.
 */
data class Snapshot(
    val fetchedAt: Long,
    /** ESPN's matchup period (the fantasy week). */
    val week: Int,
    /** Your matchup, you on the [Matchup.left]; null on a bye. */
    val mine: Matchup?,
    val others: List<Matchup>,
    /** Your starters whose NFL game is in progress. */
    val livePlayers: List<PlayerLine>,
)

/** Two teams in display order: you on the left in your own matchup, otherwise the home team. */
data class Matchup(val left: Side, val right: Side, val final: Boolean)

/** One team in a matchup: its short name, live points and live projection (if ESPN gives one). */
data class Side(val team: String, val points: Double, val projected: Double?)

/** One of your starters in a game right now, with this week's points so far. */
data class PlayerLine(val name: String, val position: String, val points: Double)
