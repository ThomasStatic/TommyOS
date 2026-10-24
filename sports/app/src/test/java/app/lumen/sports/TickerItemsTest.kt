package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TickerItemsTest {
    private val zone = ZoneId.of("America/New_York")
    private val fetched = ZonedDateTime.of(2026, 10, 25, 13, 42, 0, 0, zone).toInstant()

    private fun snapshot(
        mine: Matchup? = Matchup(Side("TOM", 98.36, 112.5), Side("ABC", 91.24, 104.04), final = false),
        others: List<Matchup> = emptyList(),
        live: List<PlayerLine> = emptyList(),
    ) = Snapshot(fetched.toEpochMilli(), week = 8, mine = mine, others = others, livePlayers = live)

    @Test
    fun `your live matchup reads you first with projections`() {
        val items = TickerItems.from(snapshot(), now = fetched, zone = zone)

        assertEquals(TickerItem("Week 8   TOM 98.4 – 91.2 ABC   proj 112.5 – 104.0", emphasis = true), items.first())
    }

    @Test
    fun `a finished matchup says Final instead of projections`() {
        val done = Matchup(Side("TOM", 120.0, 120.0), Side("ABC", 99.95, 99.95), final = true)

        val items = TickerItems.from(snapshot(mine = done), now = fetched, zone = zone)

        assertEquals("Week 8   TOM 120.0 – 100.0 ABC   Final", items.first().text)
    }

    @Test
    fun `live players follow your matchup three at a time with short names`() {
        val live = listOf(
            PlayerLine("Josh Allen", "QB", 21.42),
            PlayerLine("Saquon Barkley", "RB", 12.0),
            PlayerLine("Ja'Marr Chase", "WR", 8.26),
            PlayerLine("Bills D/ST", "D/ST", 4.0),
        )

        val items = TickerItems.from(snapshot(live = live), now = fetched, zone = zone)

        assertEquals(
            listOf(
                "Live   J. Allen QB 21.4   ·   S. Barkley RB 12.0   ·   J. Chase WR 8.3",
                "Live   Bills D/ST 4.0",
            ),
            items.drop(1).map { it.text },
        )
    }

    @Test
    fun `other league matchups come last one per item`() {
        val others = listOf(
            Matchup(Side("DEF", 87.1, 101.2), Side("GHI", 90.3, 95.0), final = false),
            Matchup(Side("JKL", 110.0, 110.0), Side("MNO", 64.5, 64.5), final = true),
        )

        val items = TickerItems.from(snapshot(others = others, live = listOf(PlayerLine("Josh Allen", "QB", 1.0))), now = fetched, zone = zone)

        assertEquals(listOf("DEF 87.1 – 90.3 GHI", "JKL 110.0 – 64.5 MNO   Final"), items.drop(2).map { it.text })
        assertEquals(listOf(false, false), items.drop(2).map { it.emphasis })
    }

    @Test
    fun `scores older than three minutes say when they are from`() {
        val fresh = TickerItems.from(snapshot(), now = fetched.plusSeconds(170), zone = zone)
        val stale = TickerItems.from(snapshot(), now = fetched.plusSeconds(190), zone = zone)

        assertEquals("Week 8   TOM 98.4 – 91.2 ABC   proj 112.5 – 104.0", fresh.first().text)
        assertEquals("Week 8   TOM 98.4 – 91.2 ABC   proj 112.5 – 104.0   as of 1:42 pm", stale.first().text)
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

        assertEquals(listOf(TickerItem("Week 8   Bye week", emphasis = true), TickerItem("DEF 87.1 – 90.3 GHI")), items)
    }
}
