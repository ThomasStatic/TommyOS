package app.lumen.sports

import org.json.JSONObject
import java.time.Instant

/**
 * Reads ESPN's undocumented fantasy API responses into a [Snapshot]. The only place that knows
 * ESPN's JSON shape. Tolerant: entries missing fields are skipped, so a partial response still
 * yields a ticker; only a response with no teams or schedule at all fails the fetch.
 */
object EspnParser {
    /**
     * [swid]: your ESPN member id, which identifies your team. [kickoffs]: NFL team id -> kickoff
     * this week (see [kickoffs]), used to tell whose game is under way, over or still to come.
     */
    fun snapshot(leagueJson: String, swid: String, kickoffs: Map<Int, Instant>, now: Instant): Snapshot {
        val league = JSONObject(leagueJson)
        val teams = league.getJSONArray("teams").objects().filter { it.has("id") }
        val abbrev = teams.associate { it.getInt("id") to it.optString("abbrev", "?") }
        val names = teams.associate { it.getInt("id") to it.optString("name").takeIf { n -> n.isNotBlank() } }
        val myTeam = teams.firstOrNull { t ->
            val owners = t.optJSONArray("owners") ?: return@firstOrNull false
            (0 until owners.length()).any { owners.optString(it).equals(swid, ignoreCase = true) }
        }?.getInt("id")
        val period = matchupPeriod(league)
        val scoringPeriod = scoringPeriod(league)
        fun side(o: JSONObject, withStarters: Boolean = false): Side {
            val id = o.getInt("teamId")
            val team = abbrev[id] ?: "?"
            return Side(
                team = team,
                points = o.optDouble("totalPointsLive", o.optDouble("totalPoints", 0.0)),
                projected = o.optDouble("totalProjectedPointsLive").takeIf { !it.isNaN() },
                name = names[id] ?: team,
                starters = if (withStarters) starters(o, scoringPeriod, kickoffs, now) else emptyList(),
            )
        }

        var mine: Matchup? = null
        val others = ArrayList<Matchup>()
        for (game in league.getJSONArray("schedule").objects()) {
            if (period != null && game.optInt("matchupPeriodId", period) != period) continue
            // Playoff byes have only one side; entries without team ids can't be shown.
            val home = game.optJSONObject("home")?.takeIf { it.has("teamId") } ?: continue
            val away = game.optJSONObject("away")?.takeIf { it.has("teamId") } ?: continue
            val final = game.optString("winner", "UNDECIDED") != "UNDECIDED"
            when (myTeam) {
                home.getInt("teamId") -> mine = Matchup(side(home, true), side(away, true), final)
                away.getInt("teamId") -> mine = Matchup(side(away, true), side(home, true), final)
                else -> others += Matchup(side(home), side(away), final)
            }
        }
        return Snapshot(now.toEpochMilli(), week = period ?: 0, mine = mine, others = others)
    }

    /** The fantasy week (matchup period) ESPN is currently on. */
    fun matchupPeriod(leagueJson: String): Int? = matchupPeriod(JSONObject(leagueJson))

    /** The NFL week (scoring period) ESPN is currently on; 0 if the response doesn't say. */
    fun scoringPeriod(leagueJson: String): Int = scoringPeriod(JSONObject(leagueJson))

    private fun scoringPeriod(league: JSONObject): Int =
        league.optInt("scoringPeriodId", 0).takeIf { it > 0 }
            ?: league.optJSONObject("status")?.optInt("latestScoringPeriod", 0) ?: 0

    /** NFL team id -> kickoff time for [scoringPeriod], from the proTeamSchedules_wl view. Teams on a bye are absent. */
    fun kickoffs(scheduleJson: String, scoringPeriod: Int): Map<Int, Instant> {
        val teams = JSONObject(scheduleJson).optJSONObject("settings")?.optJSONArray("proTeams") ?: return emptyMap()
        val out = HashMap<Int, Instant>()
        for (team in teams.objects()) {
            val game = team.optJSONObject("proGamesByScoringPeriod")?.optJSONArray(scoringPeriod.toString())?.optJSONObject(0) ?: continue
            val date = game.optLong("date", 0L).takeIf { it > 0 } ?: continue
            out[team.optInt("id")] = Instant.ofEpochMilli(date)
        }
        return out
    }

    private fun matchupPeriod(league: JSONObject): Int? =
        league.optJSONObject("status")?.optInt("currentMatchupPeriod", 0)?.takeIf { it > 0 }

    /**
     * Starters (not bench or IR) in lineup order. A game is live from kickoff for
     * [PollPolicy.GAME_MS], then played; ESPN's per-game "final" flag only arrives with the
     * twice-daily schedule, too late to use.
     */
    private fun starters(side: JSONObject, scoringPeriod: Int, kickoffs: Map<Int, Instant>, now: Instant): List<PlayerLine> {
        val entries = side.optJSONObject("rosterForCurrentScoringPeriod")?.optJSONArray("entries") ?: return emptyList()
        val t = now.toEpochMilli()
        return entries.objects()
            .filter { it.optInt("lineupSlotId", BENCH) !in NOT_STARTING }
            .sortedBy { e -> SLOT_ORDER.indexOf(e.optInt("lineupSlotId")).let { if (it < 0) SLOT_ORDER.size else it } }
            .mapNotNull { e ->
                val pool = e.optJSONObject("playerPoolEntry") ?: return@mapNotNull null
                val player = pool.optJSONObject("player") ?: return@mapNotNull null
                val kickoff = kickoffs[player.optInt("proTeamId")]?.toEpochMilli()
                val game = when {
                    kickoff == null || t < kickoff -> Game.UPCOMING
                    t > kickoff + PollPolicy.GAME_MS -> Game.PLAYED
                    else -> Game.LIVE
                }
                PlayerLine(
                    player.optString("fullName", "?"),
                    POSITIONS[player.optInt("defaultPositionId")] ?: "",
                    pool.optDouble("appliedStatTotal", 0.0),
                    projection(player, scoringPeriod),
                    game,
                )
            }
    }

    /** ESPN's projection for [scoringPeriod]: the stats entry from source 1 (projected), split 1 (one week). */
    private fun projection(player: JSONObject, scoringPeriod: Int): Double? =
        player.optJSONArray("stats")?.objects()?.firstOrNull {
            it.optInt("statSourceId", -1) == 1 && it.optInt("statSplitTypeId", -1) == 1 && it.optInt("scoringPeriodId") == scoringPeriod
        }?.optDouble("appliedTotal")?.takeIf { !it.isNaN() }

    private const val BENCH = 20
    private const val IR = 21
    private val NOT_STARTING = setOf(BENCH, IR)
    /** Lineup slots in the order ESPN shows them: QB, RB, RB/WR, WR, WR/TE, TE, OP, FLEX, D/ST, K. */
    private val SLOT_ORDER = listOf(0, 2, 3, 4, 5, 6, 7, 23, 16, 17)
    /** ESPN defaultPositionId -> position. */
    private val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 16 to "D/ST")
}
