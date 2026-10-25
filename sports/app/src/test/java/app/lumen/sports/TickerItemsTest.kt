package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TickerItemsTest {
    private val zone = ZoneId.of("America/New_York")
    private val fetched = ZonedDateTime.of(2026, 10, 25, 13, 42, 0, 0, zone).toInstant()

    private fun mine(you: List<PlayerLine> = emptyList(), them: List<PlayerLine> = emptyList(), final: Boolean = false) = Matchup(
        Side("TOM", 98.36, 112.5, name = "Synthetic Team", starters = you),
        Side("ABC", 91.24, 104.04, name = "Alpha Bravo", starters = them),
        final,
    )

    private fun snapshot(mine: Matchup? = mine(), others: List<Matchup> = emptyList()) =
        Snapshot(fetched.toEpochMilli(), week = 8, mine = mine, others = others)

    private fun live(name: String, pos: String, pts: Double) = PlayerLine(name, pos, pts, null, Game.LIVE)
    private fun played(name: String, pos: String, pts: Double) = PlayerLine(name, pos, pts, null, Game.PLAYED)
    private fun upcoming(name: String, pos: String, proj: Double?) = PlayerLine(name, pos, 0.0, proj, Game.UPCOMING)

    @Test
    fun `your matchup leads with both full team names and projections`() {
        val items = TickerItems.from(snapshot(), now = fetched, zone = zone)

        assertEquals(TickerItem("Week 8   Synthetic Team 98.4 – 91.2 Alpha Bravo   proj 112.5 – 104.0", emphasis = true), items.first())
    }

    @Test
    fun `a finished matchup says Final instead of projections`() {
        val items = TickerItems.from(snapshot(mine = mine(final = true)), now = fetched, zone = zone)

        assertEquals("Week 8   Synthetic Team 98.4 – 91.2 Alpha Bravo   Final", items.first().text)
    }

    @Test
    fun `players come live, then played, then to play, yours before theirs, each group on a new page`() {
        val you = listOf(
            live("Josh Allen", "QB", 21.42),
            upcoming("Late Kickoff", "WR", 14.24),
            played("Thursday Guy", "TE", 9.0),
            live("Saquon Barkley", "RB", 12.0),
        )
        val them = listOf(live("Rival Back", "RB", 3.1), upcoming("Bye Player", "TE", null))

        val items = TickerItems.from(snapshot(mine = mine(you, them)), now = fetched, zone = zone)

        assertEquals(
            listOf(
                TickerItem("TOM live   J. Allen QB 21.4   ·   S. Barkley RB 12.0", newPage = true),
                TickerItem("ABC live   R. Back RB 3.1"),
                TickerItem("TOM played   T. Guy TE 9.0", newPage = true),
                TickerItem("TOM to play   L. Kickoff WR proj 14.2", newPage = true),
                TickerItem("ABC to play   B. Player TE"),
            ),
            items.drop(1),
        )
    }

    @Test
    fun `a long group is split three players at a time and a defence's name isn't repeated`() {
        val you = listOf(
            live("Josh Allen", "QB", 21.42),
            live("Saquon Barkley", "RB", 12.0),
            live("Ja'Marr Chase", "WR", 8.26),
            live("Bills D/ST", "D/ST", 4.0),
        )

        val items = TickerItems.from(snapshot(mine = mine(you)), now = fetched, zone = zone)

        assertEquals(
            listOf(
                TickerItem("TOM live   J. Allen QB 21.4   ·   S. Barkley RB 12.0   ·   J. Chase WR 8.3", newPage = true),
                TickerItem("TOM live   Bills D/ST 4.0"),
            ),
            items.drop(1),
        )
    }

    @Test
    fun `other league matchups come last, abbreviated, starting a new page`() {
        val others = listOf(
            Matchup(Side("DEF", 87.1, 101.2, name = "Delta Echo"), Side("GHI", 90.3, 95.0), final = false),
            Matchup(Side("JKL", 110.0, 110.0), Side("MNO", 64.5, 64.5), final = true),
        )

        val items = TickerItems.from(snapshot(mine = mine(listOf(live("Josh Allen", "QB", 1.0))), others = others), now = fetched, zone = zone)

        assertEquals(
            listOf(TickerItem("DEF 87.1 – 90.3 GHI", newPage = true), TickerItem("JKL 110.0 – 64.5 MNO   Final")),
            items.drop(2),
        )
    }

    @Test
    fun `scores older than three minutes say when they are from`() {
        val fresh = TickerItems.from(snapshot(), now = fetched.plusSeconds(170), zone = zone)
        val stale = TickerItems.from(snapshot(), now = fetched.plusSeconds(190), zone = zone)

        assertEquals(false, fresh.first().text.contains("as of"))
        assertEquals("Week 8   Synthetic Team 98.4 – 91.2 Alpha Bravo   proj 112.5 – 104.0   as of 1:42 pm", stale.first().text)
    }

    @Test
    fun `off-hours scores are only labelled once they are overdue`() {
        val halfHour = 30 * 60_000L
        val onTime = TickerItems.from(snapshot(), now = fetched.plusSeconds(20 * 60), zone = zone, staleAfterMs = halfHour)
        val overdue = TickerItems.from(snapshot(), now = fetched.plusSeconds(31 * 60), zone = zone, staleAfterMs = halfHour)

        assertEquals(false, onTime.first().text.contains("as of"))
        assertEquals(true, overdue.first().text.endsWith("as of 1:42 pm"))
    }

    @Test
    fun `a bye week is said first and the league still follows`() {
        val others = listOf(Matchup(Side("DEF", 87.1, null), Side("GHI", 90.3, null), final = false))

        val items = TickerItems.from(snapshot(mine = null, others = others), now = fetched, zone = zone)

        assertEquals(listOf(TickerItem("Week 8   Bye week", emphasis = true), TickerItem("DEF 87.1 – 90.3 GHI", newPage = true)), items)
    }
}
