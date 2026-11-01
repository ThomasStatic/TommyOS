package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun content(s: Snapshot?, notice: TickerItems.Notice? = null, ageSeconds: Long = 0) =
        TickerItems.from(s, notice, now = fetched.plusSeconds(ageSeconds), zone = zone)

    private fun live(name: String, pos: String, pts: Double) = PlayerLine(name, pos, pts, null, Game.LIVE)
    private fun played(name: String, pos: String, pts: Double) = PlayerLine(name, pos, pts, null, Game.PLAYED)
    private fun upcoming(name: String, pos: String, proj: Double?) = PlayerLine(name, pos, 0.0, proj, Game.UPCOMING)
    private fun bye(name: String, pos: String) = PlayerLine(name, pos, 0.0, null, Game.BYE)

    @Test
    fun `the board pins both scores with projections and marks who leads`() {
        assertEquals(
            Board(BoardTeam("TOM", "98.4", "112.5", leads = true), BoardTeam("ABC", "91.2", "104.0", leads = false), final = false),
            content(snapshot()).board,
        )
    }

    @Test
    fun `a finished matchup drops the projections`() {
        val board = content(snapshot(mine = mine(final = true))).board!!

        assertEquals(true, board.final)
        assertNull(board.mine.projected)
        assertNull(board.theirs.projected)
    }

    @Test
    fun `scores equal to a tenth lead neither way`() {
        val tied = Matchup(Side("TOM", 90.04, null), Side("ABC", 89.96, null), final = false)

        val board = content(snapshot(mine = tied)).board!!

        assertEquals(false, board.mine.leads)
        assertEquals(false, board.theirs.leads)
    }

    @Test
    fun `players come live, then to play, then played, then on a bye, yours before theirs, one heading per group`() {
        val you = listOf(
            live("Josh Allen", "QB", 21.42),
            upcoming("Late Kickoff", "WR", 14.24),
            played("Thursday Guy", "TE", 9.0),
            live("Saquon Barkley", "RB", 12.0),
        )
        val them = listOf(live("Rival Back", "RB", 3.1), upcoming("No Projection", "TE", null), bye("Bye Player", "WR"))

        val crawl = content(snapshot(mine = mine(you, them))).crawl

        val tom = Chip("TOM", mine = true)
        val abc = Chip("ABC", mine = false)
        assertEquals(
            listOf(
                Segment("LIVE", tom, listOf(Entry("J. Allen", "QB", "21.4"), Entry("S. Barkley", "RB", "12.0")), live = true),
                Segment(null, abc, listOf(Entry("R. Back", "RB", "3.1"))),
                Segment("TO PLAY", tom, listOf(Entry("L. Kickoff", "WR", "14.2", projected = true))),
                Segment(null, abc, listOf(Entry("N. Projection", "TE"))),
                Segment("PLAYED", tom, listOf(Entry("T. Guy", "TE", "9.0"))),
                Segment("ON BYE", abc, listOf(Entry("B. Player", "WR"))),
            ),
            crawl,
        )
    }

    @Test
    fun `without the NFL schedule each team's starters are listed with their points`() {
        val you = listOf(PlayerLine("Josh Allen", "QB", 21.42, 18.0, Game.UNKNOWN), PlayerLine("Late Kickoff", "WR", 0.0, 14.24, Game.UNKNOWN))

        val crawl = content(snapshot(mine = mine(you))).crawl

        assertEquals(listOf(Segment("STARTERS", Chip("TOM", true), listOf(Entry("J. Allen", "QB", "21.4"), Entry("L. Kickoff", "WR", "0.0")))), crawl)
    }

    @Test
    fun `a defence's name isn't repeated as its position`() {
        val crawl = content(snapshot(mine = mine(listOf(live("Bills D/ST", "D/ST", 4.0))))).crawl

        assertEquals(listOf(Entry("Bills D/ST", "", "4.0")), crawl.single().entries)
    }

    @Test
    fun `other league matchups come last, abbreviated, under one heading`() {
        val others = listOf(
            Matchup(Side("DEF", 87.1, 101.2, name = "Delta Echo"), Side("GHI", 90.3, 95.0), final = false),
            Matchup(Side("JKL", 110.0, 110.0), Side("MNO", 64.5, 64.5), final = true),
        )

        val crawl = content(snapshot(mine = mine(listOf(live("Josh Allen", "QB", 1.0))), others = others)).crawl

        assertEquals(Segment("LEAGUE", entries = listOf(Entry("DEF 87.1 – 90.3 GHI"), Entry("JKL 110.0 – 64.5 MNO", tag = "FINAL"))), crawl.last())
    }

    @Test
    fun `scores older than three minutes say when they are from`() {
        assertNull(content(snapshot(), ageSeconds = 170).status)
        assertEquals("AS OF 1:42 PM", content(snapshot(), ageSeconds = 190).status)
    }

    @Test
    fun `off-hours scores are only labelled once they are overdue`() {
        val halfHour = 30 * 60_000L
        val onTime = TickerItems.from(snapshot(), null, now = fetched.plusSeconds(20 * 60), zone = zone, staleAfterMs = halfHour)
        val overdue = TickerItems.from(snapshot(), null, now = fetched.plusSeconds(31 * 60), zone = zone, staleAfterMs = halfHour)

        assertNull(onTime.status)
        assertEquals("AS OF 1:42 PM", overdue.status)
    }

    @Test
    fun `a problem with scores still up goes in the status pill with their time`() {
        val c = content(snapshot(), TickerItems.Notice("ESPN scores unavailable · retrying", "ESPN unavailable"))

        assertEquals("ESPN UNAVAILABLE · AS OF 1:42 PM", c.status)
        assertEquals(true, c.board != null)
    }

    @Test
    fun `without scores the problem, or loading, is all there is`() {
        assertEquals(
            TickerContent(null, listOf(Segment(entries = listOf(Entry("Not signed in"))))),
            content(null, TickerItems.Notice("Not signed in", "signed out")),
        )
        assertEquals(TickerContent(null, listOf(Segment(entries = listOf(Entry("Loading ESPN fantasy scores…"))))), content(null))
    }

    @Test
    fun `no matchup of your own is said first and the league still follows`() {
        val others = listOf(Matchup(Side("DEF", 87.1, null), Side("GHI", 90.3, null), final = false))

        val c = content(snapshot(mine = null, others = others))

        assertNull(c.board)
        assertEquals(
            listOf(Segment("WEEK 8", entries = listOf(Entry("No matchup this week"))), Segment("LEAGUE", entries = listOf(Entry("DEF 87.1 – 90.3 GHI")))),
            c.crawl,
        )
    }
}
