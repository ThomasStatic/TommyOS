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
     * this week (see [kickoffs]), used to tell which of your players are in a game right now.
     */
    fun snapshot(leagueJson: String, swid: String, kickoffs: Map<Int, Instant>, now: Instant): Snapshot {
        val league = JSONObject(leagueJson)
        val teams = league.getJSONArray("teams").objects().filter { it.has("id") }
        val abbrev = teams.associate { it.getInt("id") to it.optString("abbrev", "?") }
        val myTeam = teams.firstOrNull { t ->
            val owners = t.optJSONArray("owners") ?: return@firstOrNull false
            (0 until owners.length()).any { owners.optString(it).equals(swid, ignoreCase = true) }
        }?.getInt("id")
        val period = matchupPeriod(league)

        var mine: Matchup? = null
        var live = emptyList<PlayerLine>()
        val others = ArrayList<Matchup>()
        for (game in league.getJSONArray("schedule").objects()) {
            if (period != null && game.optInt("matchupPeriodId", period) != period) continue
            // Playoff byes have only one side; entries without team ids can't be shown.
            val home = game.optJSONObject("home")?.takeIf { it.has("teamId") } ?: continue
            val away = game.optJSONObject("away")?.takeIf { it.has("teamId") } ?: continue
            val final = game.optString("winner", "UNDECIDED") != "UNDECIDED"
            when (myTeam) {
                home.getInt("teamId") -> { mine = Matchup(side(home, abbrev), side(away, abbrev), final); live = livePlayers(home, kickoffs, now) }
                away.getInt("teamId") -> { mine = Matchup(side(away, abbrev), side(home, abbrev), final); live = livePlayers(away, kickoffs, now) }
                else -> others += Matchup(side(home, abbrev), side(away, abbrev), final)
            }
        }
        return Snapshot(now.toEpochMilli(), week = period ?: 0, mine = mine, others = others, livePlayers = live)
    }

    /** The fantasy week (matchup period) ESPN is currently on. */
    fun matchupPeriod(leagueJson: String): Int? = matchupPeriod(JSONObject(leagueJson))

    /** The NFL week (scoring period) ESPN is currently on; 0 if the response doesn't say. */
    fun scoringPeriod(leagueJson: String): Int {
        val league = JSONObject(leagueJson)
        return league.optInt("scoringPeriodId", 0).takeIf { it > 0 }
            ?: league.optJSONObject("status")?.optInt("latestScoringPeriod", 0) ?: 0
    }

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

    /** Starters (not bench or IR) whose NFL team kicked off less than [PollPolicy.GAME_MS] ago. */
    private fun livePlayers(side: JSONObject, kickoffs: Map<Int, Instant>, now: Instant): List<PlayerLine> {
        val entries = side.optJSONObject("rosterForCurrentScoringPeriod")?.optJSONArray("entries") ?: return emptyList()
        val t = now.toEpochMilli()
        return entries.objects().mapNotNull { e ->
            if (e.optInt("lineupSlotId", BENCH) in NOT_STARTING) return@mapNotNull null
            val pool = e.optJSONObject("playerPoolEntry") ?: return@mapNotNull null
            val player = pool.optJSONObject("player") ?: return@mapNotNull null
            val kickoff = kickoffs[player.optInt("proTeamId")]?.toEpochMilli() ?: return@mapNotNull null
            if (t < kickoff || t > kickoff + PollPolicy.GAME_MS) return@mapNotNull null
            PlayerLine(player.optString("fullName", "?"), POSITIONS[player.optInt("defaultPositionId")] ?: "", pool.optDouble("appliedStatTotal", 0.0))
        }
    }

    private fun side(o: JSONObject, abbrev: Map<Int, String>) = Side(
        team = abbrev[o.getInt("teamId")] ?: "?",
        points = o.optDouble("totalPointsLive", o.optDouble("totalPoints", 0.0)),
        projected = o.optDouble("totalProjectedPointsLive").takeIf { !it.isNaN() },
    )

    private const val BENCH = 20
    private const val IR = 21
    private val NOT_STARTING = setOf(BENCH, IR)
    /** ESPN defaultPositionId -> position. */
    private val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 16 to "D/ST")
}
