package app.lumen.sports

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real ESPN refreshes of one matchup, every 30 s through the late Sunday games of 2026-10-25, with
 * every name replaced (written by [RecordedSeriesTest]). Pins what the alerts did with real data:
 * identical responses, small and large decreases, and a burst where the lead went and came back.
 */
class ReplayFixtureTest {
    private val series = JSONArray(javaClass.classLoader!!.getResource("espn/replay-series.json")!!.readText()).let { a ->
        val cache = SnapshotCache(java.io.File("unused"))
        (0 until a.length()).map { cache.fromJson(a.getJSONObject(it)) }
    }

    private fun events(): List<Pair<Long, List<ScoreEvent>>> {
        val watch = ScoreWatch(series.first())
        return series.drop(1).map { it.fetchedAt to watch.update(it) }.filter { it.second.isNotEmpty() }
    }

    @Test
    fun `the recording is real refreshes, roughly every 30 s, some identical, some going down`() {
        val gaps = series.zipWithNext { a, b -> b.fetchedAt - a.fetchedAt }
        assertEquals(true, series.size > 80)
        assertEquals(true, gaps.all { it in 25_000..40_000 })
        val identical = series.zipWithNext().count { (a, b) -> a.copy(fetchedAt = 0) == b.copy(fetchedAt = 0) }
        assertEquals(true, identical >= 5)
        val decreases = series.zipWithNext().sumOf { (a, b) ->
            val before = (a.mine!!.left.starters + a.mine!!.right.starters).associateBy { it.name }
            (b.mine!!.left.starters + b.mine!!.right.starters).count { p -> before[p.name]?.let { p.points < it.points - 0.005 } == true }
        }
        assertEquals(true, decreases >= 3)
    }

    @Test
    fun `only the real swings become events, with the lead change after the swing that caused it`() {
        val polls = events()

        assertEquals(2, polls.size)
        assertEquals(true, polls[1].first - polls[0].first in 30_000..31_000) // consecutive refreshes
        val (lost, leadLost) = polls[0].second
        assertEquals(listOf("D/ST", -500L), listOf((lost as PointsChange).position, lost.change))
        assertEquals(false, (leadLost as LeadChange).mineLeads)
        val (gained, leadBack) = polls[1].second
        assertEquals(listOf("RB", 800L), listOf((gained as PointsChange).position, gained.change))
        assertEquals(true, (leadBack as LeadChange).mineLeads)
    }

    @Test
    fun `paced, that burst is two runs of two banners, 40 s apart`() {
        val polls = events()
        val start = polls.first().first
        val alerts = Alerts()
        val shown = ArrayList<Pair<Long, String>>()
        var i = 0
        var t = start
        while (t <= start + 120_000) {
            while (i < polls.size && polls[i].first <= t) alerts.offer(polls[i++].second, t)
            alerts.next(t)?.let { shown += (t - start) to it.heading }
            t += 100
        }

        assertEquals(listOf(0L to "POINTS DOWN", 5_000L to "LEAD CHANGE", 40_000L to "POINTS UP", 45_000L to "LEAD CHANGE"), shown)
    }
}
