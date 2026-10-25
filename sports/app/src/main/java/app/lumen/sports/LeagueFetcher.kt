package app.lumen.sports

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One poll's worth of ESPN work for one set of credentials: the league filtered to the current
 * week, plus NFL kickoff times from the season schedule (re-read twice a day), parsed into a
 * [Snapshot]. Remembers the week between polls, so a normal poll is a single request.
 * Worker thread only; blocking.
 */
class LeagueFetcher(val creds: Credentials.Value, startWeek: Int?) {
    private val client = EspnClient(creds)
    private var matchupPeriod = startWeek
    private val schedule = ScheduleCache()

    /** This week's NFL kickoffs; null until the schedule has been read (see [PollPolicy]). */
    var kickoffs: List<Instant>? = null
        private set

    fun fetch(now: Instant): Snapshot {
        val season = seasonFor(now.atZone(ZONE))
        var league = client.league(season, matchupPeriod)
        // The week rolls over on ESPN's schedule; refetch once if our filter was for another week.
        val current = EspnParser.matchupPeriod(league)
        if (current != null && current != matchupPeriod) {
            matchupPeriod = current
            league = client.league(season, current)
        }
        val teamKickoffs = schedule.kickoffs(season, EspnParser.scoringPeriod(league), now)
        kickoffs = teamKickoffs?.values?.distinct()
        return EspnParser.snapshot(league, creds.swid, teamKickoffs, now)
    }

    /** The NFL season schedule, fetched at most every [MAX_AGE_MS] (or [RETRY_MS] after a failure). */
    private inner class ScheduleCache {
        private var json: String? = null
        private var season = 0
        private var attemptedAt = 0L
        private var parsedFor = -1
        private var parsed: Map<Int, Instant>? = null

        fun kickoffs(season: Int, scoringPeriod: Int, now: Instant): Map<Int, Instant>? {
            val t = now.toEpochMilli()
            if (season != this.season) {
                this.season = season
                json = null
                attemptedAt = 0L
                parsedFor = -1
            }
            val wait = if (json == null) RETRY_MS else MAX_AGE_MS
            if (attemptedAt == 0L || t - attemptedAt > wait) {
                attemptedAt = t
                try {
                    json = client.proSchedule(season)
                    parsedFor = -1
                } catch (e: Exception) {
                    Log.w(TAG, "NFL schedule unavailable: $e") // Polling falls back to a fixed cadence.
                }
            }
            val source = json ?: return null
            if (parsedFor != scoringPeriod) {
                parsed = runCatching { EspnParser.kickoffs(source, scoringPeriod) }.getOrNull()
                parsedFor = scoringPeriod
            }
            return parsed
        }
    }

    companion object {
        private const val TAG = "LumenSports"
        private const val MAX_AGE_MS = 12 * 3600_000L
        private const val RETRY_MS = 30 * 60_000L
        /** NFL kickoffs and ESPN's season boundaries are on US Eastern time. */
        private val ZONE = ZoneId.of("America/New_York")

        /** ESPN seasons are named for the year they start; January and February games belong to the previous one. */
        fun seasonFor(date: ZonedDateTime) = if (date.monthValue >= 3) date.year else date.year - 1
    }
}
