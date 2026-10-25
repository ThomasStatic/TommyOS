package app.lumen.sports

import java.time.Instant

/**
 * How long to wait before the next ESPN fetch: every 30 s from 5 min before a kickoff until 4 h
 * after it, otherwise every 15 min (waking for the next kickoff), backing off after errors.
 */
object PollPolicy {
    /**
     * [kickoffs]: this week's NFL kickoff times; empty when the week has no games (offseason), null
     * when the schedule isn't known. [failures]: consecutive failed fetches.
     */
    fun nextDelayMs(now: Instant, kickoffs: List<Instant>?, failures: Int): Long {
        val normal = normalDelay(now.toEpochMilli(), kickoffs)
        if (failures <= 0) return normal
        val backoff = minOf(MAX_BACKOFF_MS, LIVE_MS shl minOf(failures, 10))
        return maxOf(normal, backoff)
    }

    private fun normalDelay(t: Long, kickoffs: List<Instant>?): Long {
        if (kickoffs == null) return UNKNOWN_SCHEDULE_MS
        val live = kickoffs.any { val k = it.toEpochMilli(); t >= k - PREGAME_MS && t <= k + GAME_MS }
        if (live) return LIVE_MS
        val untilPregame = kickoffs.map { it.toEpochMilli() - PREGAME_MS - t }.filter { it > 0 }.minOrNull()
        return minOf(IDLE_MS, untilPregame ?: IDLE_MS)
    }

    private const val LIVE_MS = 30_000L
    private const val IDLE_MS = 15 * 60_000L
    /** Schedule not fetched (yet): never blind on game day, never hammering ESPN either. */
    private const val UNKNOWN_SCHEDULE_MS = 2 * 60_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L
    private const val PREGAME_MS = 5 * 60_000L
    /** How long an NFL game counts as under way after kickoff. Generous: overtime and long reviews fit. */
    const val GAME_MS = 4 * 3600_000L
}
