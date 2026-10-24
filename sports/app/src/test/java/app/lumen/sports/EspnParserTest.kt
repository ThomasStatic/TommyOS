package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Fixtures in src/test/resources/espn are synthetic, shaped like ESPN's lm-api-reads responses
 * (checked against a real response for this league on 2026-10-24: slots 0-17 and 23 FLEX start,
 * 20 bench, 21 IR; starters' appliedStatTotal sums to the team's totalPointsLive).
 */
class EspnParserTest {
    private val league = resource("espn/league.json")
    private val you = "{AAAAAAAA-0000-0000-0000-000000000001}"
    private val now = Instant.parse("2026-10-25T18:30:00Z")

    @Test
    fun `your matchup is found from your SWID with you first`() {
        val s = EspnParser.snapshot(league, you, kickoffs = emptyMap(), now = now)

        assertEquals(Matchup(Side("TOM", 98.36, 112.5), Side("ABC", 91.24, 104.04), final = false), s.mine)
        assertEquals(8, s.week)
        assertEquals(now.toEpochMilli(), s.fetchedAt)
    }

    @Test
    fun `the rest of the league is listed without repeating your matchup`() {
        val s = EspnParser.snapshot(league, you, kickoffs = emptyMap(), now = now)

        assertEquals(listOf(Matchup(Side("DEF", 110.0, 110.0), Side("GHI", 64.5, 64.5), final = true)), s.others)
    }

    @Test
    fun `live players are your starters whose NFL game is under way`() {
        val kickoffs = mapOf(
            2 to Instant.parse("2026-10-25T17:00:00Z"),   // under way: Allen, the bench player, Bills D/ST
            21 to Instant.parse("2026-10-25T17:00:00Z"),  // under way: Barkley
            25 to Instant.parse("2026-10-25T20:25:00Z"),  // not started yet
        )

        val s = EspnParser.snapshot(league, you, kickoffs, now)

        assertEquals(
            listOf(PlayerLine("Josh Allen", "QB", 21.42), PlayerLine("Saquon Barkley", "RB", 12.0), PlayerLine("Flex Starter", "TE", 6.5), PlayerLine("Bills D/ST", "D/ST", 4.0)),
            s.livePlayers,
        )
        assertEquals(emptyList<PlayerLine>(), EspnParser.snapshot(league, you, kickoffs, Instant.parse("2026-10-25T21:01:00Z").plusSeconds(3600)).livePlayers.filter { it.name == "Josh Allen" })
    }

    @Test
    fun `kickoffs come from the NFL schedule for the current week`() {
        val schedule = resource("espn/proschedule.json")

        assertEquals(8, EspnParser.scoringPeriod(league))
        assertEquals(
            mapOf(
                2 to Instant.parse("2026-10-25T17:00:00Z"),
                21 to Instant.parse("2026-10-25T17:00:00Z"),
                25 to Instant.parse("2026-10-25T20:25:00Z"),
            ),
            EspnParser.kickoffs(schedule, scoringPeriod = 8),
        )
    }

    @Test
    fun `a sparse response still gives a ticker`() {
        val sparse = """
            {"status":{"currentMatchupPeriod":3},"teams":[{"id":1,"owners":[]},{"id":2,"abbrev":"XYZ"}],
             "schedule":[{"matchupPeriodId":3,"home":{"teamId":1,"totalPoints":50.5},"away":{"teamId":2,"totalPointsLive":40}}]}
        """.trimIndent()

        val s = EspnParser.snapshot(sparse, you, kickoffs = emptyMap(), now = now)

        assertEquals(null, s.mine)
        assertEquals(listOf(Matchup(Side("?", 50.5, null), Side("XYZ", 40.0, null), final = false)), s.others)
        assertEquals(3, s.week)
    }

    @Test
    fun `entries without team ids are skipped rather than failing the whole fetch`() {
        val odd = """
            {"status":{"currentMatchupPeriod":15},"teams":[{"abbrev":"NOID"},{"id":2,"abbrev":"XYZ"},{"id":3,"abbrev":"QRS"}],
             "schedule":[{"matchupPeriodId":15,"home":{"teamId":2,"totalPointsLive":10}},
                         {"matchupPeriodId":15,"home":{"totalPointsLive":1},"away":{"teamId":3,"totalPointsLive":2}},
                         {"matchupPeriodId":15,"home":{"teamId":3,"totalPointsLive":5},"away":{"teamId":2,"totalPointsLive":6}}]}
        """.trimIndent()

        val s = EspnParser.snapshot(odd, you, kickoffs = emptyMap(), now = now)

        assertEquals(listOf(Matchup(Side("QRS", 5.0, null), Side("XYZ", 6.0, null), final = false)), s.others)
    }

    private fun resource(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()
}
