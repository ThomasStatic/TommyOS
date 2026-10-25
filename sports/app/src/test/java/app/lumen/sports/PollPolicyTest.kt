package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PollPolicyTest {
    private val kickoff = Instant.parse("2026-10-25T17:00:00Z") // Sunday 1:00 pm ET
    private val sec = 1000L
    private val min = 60 * sec

    @Test
    fun `during a game it polls every 30 seconds`() {
        assertEquals(30 * sec, PollPolicy.nextDelayMs(kickoff.plusSeconds(3600), listOf(kickoff), failures = 0))
    }

    @Test
    fun `outside games it polls every 15 minutes, including once a game is four hours old`() {
        val tuesday = Instant.parse("2026-10-27T16:00:00Z")
        assertEquals(15 * min, PollPolicy.nextDelayMs(tuesday, listOf(kickoff), failures = 0))
        assertEquals(15 * min, PollPolicy.nextDelayMs(kickoff.plusSeconds(4 * 3600 + 1), listOf(kickoff), failures = 0))
    }

    @Test
    fun `it wakes five minutes before a kickoff that is less than 15 minutes away`() {
        assertEquals(7 * min, PollPolicy.nextDelayMs(kickoff.minusSeconds(12 * 60), listOf(kickoff), failures = 0))
    }

    @Test
    fun `after errors it backs off doubling from 30 seconds up to 15 minutes`() {
        val inGame = kickoff.plusSeconds(600)
        assertEquals(listOf(60 * sec, 2 * min, 4 * min, 8 * min, 15 * min, 15 * min),
            (1..6).map { PollPolicy.nextDelayMs(inGame, listOf(kickoff), failures = it) })
        // Never faster than the normal cadence: off-hours errors still wait at least 15 minutes.
        assertEquals(15 * min, PollPolicy.nextDelayMs(Instant.parse("2026-10-27T16:00:00Z"), listOf(kickoff), failures = 1))
    }

    @Test
    fun `without a known NFL schedule it polls every 2 minutes`() {
        assertEquals(2 * min, PollPolicy.nextDelayMs(kickoff, kickoffs = null, failures = 0))
    }

    @Test
    fun `a known week without games, like the offseason, polls every 15 minutes`() {
        assertEquals(15 * min, PollPolicy.nextDelayMs(kickoff, kickoffs = emptyList(), failures = 0))
    }
}
