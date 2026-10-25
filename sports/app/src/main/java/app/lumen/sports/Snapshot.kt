package app.lumen.sports

/**
 * One fetch of the league, reduced to what the ticker shows. Immutable and free of ESPN types, so
 * rendering never depends on the API's shape; [EspnParser] builds it, [SnapshotCache] persists it.
 */
data class Snapshot(
    val fetchedAt: Long,
    /** ESPN's matchup period (the fantasy week). */
    val week: Int,
    /** Your matchup, you on the [Matchup.left], both sides with their [Side.starters]; null on a bye. */
    val mine: Matchup?,
    /** The rest of the league, without starters. */
    val others: List<Matchup>,
)

/** Two teams in display order: you on the left in your own matchup, otherwise the home team. */
data class Matchup(val left: Side, val right: Side, val final: Boolean)

/**
 * One team in a matchup: its short [team] name and full [name], live points and live projection
 * (if ESPN gives one). [starters] in lineup order, filled in for your matchup only.
 */
data class Side(
    val team: String,
    val points: Double,
    val projected: Double?,
    val name: String = team,
    val starters: List<PlayerLine> = emptyList(),
)

/** One starter: this week's points so far, this week's projection (if ESPN gives one) and where their NFL game is. */
data class PlayerLine(val name: String, val position: String, val points: Double, val projected: Double?, val game: Game)

/** Where a player's NFL game is this week. A player on a bye, or with an unknown kickoff, is [UPCOMING]. */
enum class Game { LIVE, PLAYED, UPCOMING }
