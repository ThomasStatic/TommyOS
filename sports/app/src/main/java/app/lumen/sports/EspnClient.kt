package app.lumen.sports

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Raw HTTPS access to ESPN's undocumented fantasy API. Returns response bodies; [EspnParser]
 * interprets them. Blocking: call from a background thread.
 */
class EspnClient(private val creds: Credentials.Value) {
    class AuthException(code: Int) : IOException("ESPN refused the credentials (HTTP $code)")

    /** The league with this week's matchups, scores and starting lineups. */
    fun league(season: Int, matchupPeriod: Int?): String {
        val filter = matchupPeriod?.let { """{"schedule":{"filterMatchupPeriodIds":{"value":[$it]}}}""" }
        return get("$BASE/seasons/$season/segments/0/leagues/${creds.leagueId}?$LEAGUE_VIEWS", filter)
    }

    /** Every NFL team's schedule for the season, for kickoff times. Not league-specific. */
    fun proSchedule(season: Int): String = get("$BASE/seasons/$season?view=proTeamSchedules_wl", null)

    private fun get(url: String, fantasyFilter: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("Cookie", "espn_s2=${creds.espnS2}; SWID=${creds.swid}")
            fantasyFilter?.let { conn.setRequestProperty("x-fantasy-filter", it) }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw AuthException(code)
            if (code != 200) throw IOException("ESPN HTTP $code")
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl"
        private const val LEAGUE_VIEWS = "view=mMatchupScore&view=mScoreboard&view=mTeam&view=mSettings&view=mStatus"
        private const val TIMEOUT_MS = 15_000
    }
}
