package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightsTest {
    private val t0 = 1_761_000_000_000L

    private fun player(name: String, pts: Double, pos: String = "RB") = PlayerLine(name, pos, pts, null, Game.LIVE)

    private fun snapshot(at: Long, you: List<PlayerLine>, them: List<PlayerLine>, week: Int = 8, opponent: String = "ABC") = Snapshot(
        at, week,
        Matchup(Side("TOM", you.sumOf { it.points }, null, starters = you), Side(opponent, them.sumOf { it.points }, null, starters = them), final = false),
        others = emptyList(),
    )

    /** Your QB and D/ST against their RB, at [allen], [rival] points, [s] seconds in. */
    private fun at(s: Int, allen: Double, rival: Double, week: Int = 8) =
        snapshot(t0 + s * 1000L, listOf(player("Josh Allen", allen, "QB"), player("Bills D/ST", 2.0, "D/ST")), listOf(player("Rival Back", rival)), week)

    private fun changes(vararg snapshots: Snapshot): List<ScoreEvent> {
        val watch = ScoreWatch(snapshots.first())
        return snapshots.drop(1).flatMap(watch::update)
    }

    // --- ScoreWatch ---

    @Test
    fun `a starter moving four points or more either way is an event, exactly four included`() {
        assertEquals(listOf(PointsChange("TOM", true, "Josh Allen", "QB", 10.7, 14.7)), changes(at(0, 10.7, 3.0), at(30, 14.7, 3.0)))
        assertEquals(listOf(PointsChange("ABC", false, "Rival Back", "RB", 3.0, -1.0)), changes(at(0, 10.0, 3.0), at(30, 10.0, -1.0)))
        assertEquals(emptyList<ScoreEvent>(), changes(at(0, 10.0, 3.0), at(30, 13.99, 3.0)))
    }

    @Test
    fun `duplicate responses, re-fetches and a restart from the cache report nothing again`() {
        val a = at(0, 10.0, 3.0)
        val b = at(30, 16.0, 3.0)

        assertEquals(1, changes(a, b, b.copy(fetchedAt = b.fetchedAt + 30_000), b.copy(fetchedAt = b.fetchedAt + 60_000)).size)
        // A restarted service is seeded with the last fetch it saved.
        assertEquals(emptyList<ScoreEvent>(), ScoreWatch(b).update(b.copy(fetchedAt = b.fetchedAt + 30_000)))
    }

    @Test
    fun `a delayed poll compares with the last good fetch, but not across a long gap or a new week`() {
        assertEquals(1, changes(at(0, 10.0, 3.0), at(240, 30.0, 3.0)).size)
        assertEquals(emptyList<ScoreEvent>(), changes(at(0, 10.0, 3.0), at(301, 30.0, 3.0)))
        assertEquals(emptyList<ScoreEvent>(), changes(at(0, 10.0, 3.0), at(30, 30.0, 3.0, week = 9)))
        assertEquals(emptyList<ScoreEvent>(), ScoreWatch(null).update(at(30, 30.0, 3.0)))
    }

    @Test
    fun `after a skipped comparison the next one works from the new baseline`() {
        assertEquals(listOf(30.0 to 36.0), changes(at(0, 10.0, 3.0), at(400, 30.0, 3.0), at(430, 36.0, 3.0)).map { (it as PointsChange).from to it.to })
    }

    @Test
    fun `lineup changes and status changes without points aren't events`() {
        val before = snapshot(t0, listOf(player("Josh Allen", 10.0, "QB")), listOf(player("Rival Back", 3.0)))
        val swapped = snapshot(t0 + 30_000, listOf(player("Bench Guy", 12.0, "QB")), listOf(player("Rival Back", 3.0).copy(game = Game.PLAYED)))

        assertEquals(emptyList<ScoreEvent>(), changes(before, swapped))
    }

    @Test
    fun `the lead changing hands follows the changes that caused it, even through a tie`() {
        assertEquals(listOf("PointsChange", "LeadChange"), changes(at(0, 10.0, 5.0), at(30, 10.0, 13.0)).map { it::class.simpleName })
        // 12.0 – 5.0, then tied at 12.0, then 12.0 – 16.0: one lead change, when it actually changes hands.
        val events = changes(at(0, 10.0, 5.0), at(30, 10.0, 12.0), at(60, 10.0, 16.0))
        assertEquals(listOf(LeadChange(false, "ABC", 12.0, 16.0)), events.filterIsInstance<LeadChange>())
    }

    @Test
    fun `a different opponent is a different matchup`() {
        val other = snapshot(t0 + 30_000, listOf(player("Josh Allen", 30.0, "QB"), player("Bills D/ST", 2.0, "D/ST")), listOf(player("Rival Back", 3.0)), opponent = "XYZ")

        assertEquals(emptyList<ScoreEvent>(), changes(at(0, 10.0, 3.0), other))
    }

    // --- Wording ---

    @Test
    fun `events are worded as points, never as a play`() {
        assertEquals(
            Highlight("POINTS UP", Chip("TOM", true), "J. Allen", "QB", "+6.5", "16.5 PTS", up = true, good = true),
            Highlights.present(PointsChange("TOM", true, "Josh Allen", "QB", 10.0, 16.5)),
        )
        val loss = Highlights.present(PointsChange("ABC", false, "Rival Back", "RB", 9.0, 5.0))
        assertEquals(listOf("POINTS DOWN", "−4.0", "true"), listOf(loss.heading, loss.change, loss.good.toString()))
        assertEquals("12.0 – 16.0", Highlights.present(LeadChange(false, "ABC", 12.0, 16.0)).detail)
    }

    // --- Alerts pacing ---

    private fun swing(name: String, from: Double, to: Double, mine: Boolean = true) = PointsChange(if (mine) "TOM" else "ABC", mine, name, "WR", from, to)

    /** Every banner [alerts] puts up, polled every 100 ms from 0 to [untilMs], offering [offers] (time to events) along the way. */
    private fun run(alerts: Alerts, untilMs: Long, offers: Map<Long, List<ScoreEvent>>): List<Pair<Long, String>> {
        val shown = ArrayList<Pair<Long, String>>()
        var t = 0L
        while (t <= untilMs) {
            offers[t]?.let { alerts.offer(it, t) }
            alerts.next(t)?.let { shown += t to "${it.text} ${it.change}" }
            t += 100
        }
        return shown
    }

    @Test
    fun `banners last the configured time, two in a row at most, then the crawl gets thirty seconds`() {
        val burst = listOf(swing("A One", 0.0, 6.0), swing("B Two", 0.0, 5.0), swing("C Three", 0.0, 4.0))

        assertEquals(
            listOf(0L to "A. One +6.0", 5_000L to "B. Two +5.0", 40_000L to "C. Three +4.0"),
            run(Alerts(5_000), 60_000, mapOf(0L to burst)),
        )
        assertEquals(listOf(0L to "A. One +6.0", 3_000L to "B. Two +5.0"), run(Alerts(3_000), 6_000, mapOf(0L to burst)))
    }

    @Test
    fun `a starter's later change merges into his waiting one, and a reversal cancels it`() {
        val busy = swing("Busy One", 0.0, 9.0)
        val shown = run(
            Alerts(5_000), 60_000,
            mapOf(
                0L to listOf(busy, swing("Second Guy", 0.0, 5.0)),
                // While those two are up: a third player scores and is then overturned; a fourth scores twice.
                1_000L to listOf(swing("Gone Again", 0.0, 6.0), swing("Twice Over", 0.0, 4.5)),
                2_000L to listOf(swing("Gone Again", 6.0, 0.2), swing("Twice Over", 4.5, 11.0)),
            ),
        )
        assertEquals(listOf(0L to "B. One +9.0", 5_000L to "S. Guy +5.0", 40_000L to "T. Over +11.0"), shown)
    }

    @Test
    fun `a lead change reversed before it is shown is dropped`() {
        val shown = run(
            Alerts(5_000), 30_000,
            mapOf(0L to listOf(swing("A One", 0.0, 6.0), swing("B Two", 0.0, 5.0)), 1_000L to listOf(LeadChange(true, "TOM", 50.0, 49.0)), 2_000L to listOf(LeadChange(false, "ABC", 50.0, 51.0))),
        )
        assertEquals(listOf(0L to "A. One +6.0", 5_000L to "B. Two +5.0"), shown)
    }

    @Test
    fun `news older than a minute is dropped, the queue keeps the biggest four, and zero seconds means no banners`() {
        val old = Alerts(5_000)
        old.offer(listOf(swing("A One", 0.0, 6.0)), 0)
        assertNull(old.next(61_000))

        val crowd = (1..6).map { swing("Player $it", 0.0, 3.5 + it) }
        val alerts = Alerts(5_000)
        alerts.offer(crowd, 0)
        assertEquals(
            listOf(0L to "P. 3 +6.5", 5_000L to "P. 4 +7.5", 40_000L to "P. 5 +8.5", 45_000L to "P. 6 +9.5"),
            run(alerts, 60_000, emptyMap()),
        )

        val off = Alerts(0)
        off.offer(crowd, 0)
        assertNull(off.next(0))
    }

    @Test
    fun `a real burst, from a recorded Sunday, is paced into two runs`() {
        // 2026-10-25 19:00:41 and 19:01:12 (31 s apart), names replaced: a D/ST loses 5 and the lead
        // goes; the next refresh a back gains 8 and the lead returns.
        val shown = run(
            Alerts(5_000), 90_000,
            mapOf(
                0L to listOf(PointsChange("T01", true, "Team 01 D/ST", "D/ST", 5.0, 0.0), LeadChange(false, "T02", 85.88, 88.32)),
                31_000L to listOf(PointsChange("T01", true, "Player 01", "RB", 9.6, 17.6), LeadChange(true, "T01", 93.88, 88.32)),
            ),
        )
        assertEquals(listOf(0L to "Team 01 D/ST −5.0", 5_000L to "takes the lead ", 40_000L to "P. 01 +8.0", 45_000L to "takes the lead "), shown)
    }

    @Test
    fun `nothing pending means nothing to wake for`() {
        val alerts = Alerts(5_000)
        assertNull(alerts.wakeAt(0))
        alerts.offer(listOf(swing("A One", 0.0, 6.0)), 0)
        alerts.next(0)
        assertEquals(5_000L, alerts.wakeAt(100))
        assertNull(alerts.next(5_000))
        assertNull(alerts.wakeAt(5_000))
    }
}
