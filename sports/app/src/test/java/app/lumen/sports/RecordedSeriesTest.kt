package app.lumen.sports

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Replays a real recording of ESPN refreshes through the app's parser, [ScoreWatch] and [Alerts].
 * Skipped unless ESPN_SERIES names a recording (league-<ms>.json and scoreboard-<ms>.json every
 * poll, plus proschedule.json) and ESPN_AUTH the auth file (for your SWID). Recordings name league
 * members, so they live outside the repo; this writes replay-report.txt beside them and, when
 * ESPN_FIXTURE_OUT is set, the snapshots with every name replaced, for [ReplayFixtureTest].
 */
class RecordedSeriesTest {
    @Test
    fun `replay a recorded series`() {
        val dir = System.getenv("ESPN_SERIES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val swid = File(System.getenv("ESPN_AUTH")).readLines().first { it.startsWith("SWID=") }.substringAfter('=').trim().uppercase()
        val schedule = File(dir, "proschedule.json").readText()
        val polls = dir!!.listFiles { f -> f.name.matches(Regex("league-\\d+\\.json")) }!!.sortedBy(::stamp)
        val leagues = polls.map { stamp(it) to it.readText() }
        val states = polls.map { f ->
            File(dir, "scoreboard-${stamp(f)}.json").takeIf { it.exists() }?.readText()?.let { runCatching { EspnParser.gameStates(it) }.getOrNull() }.orEmpty()
        }
        fun series(owner: String) = leagues.mapIndexed { i, (t, league) ->
            EspnParser.snapshot(league, owner, EspnParser.kickoffs(schedule, EspnParser.scoringPeriod(league)), Instant.ofEpochMilli(t), states[i])
        }
        val snapshots = series(swid)
        // Every other team's view too: each owner's SWID makes their matchup "mine", so the same real
        // refreshes exercise the detector on all of the league's matchups.
        val owners = JSONObject(leagues.first().second).getJSONArray("teams").let { teams ->
            (0 until teams.length()).mapNotNull { teams.getJSONObject(it).optJSONArray("owners")?.optString(0)?.uppercase() }
        }.filter { it != swid }
        val league = owners.joinToString("\n") { owner ->
            val s = series(owner).filter { it.mine != null }
            "--- ${s.firstOrNull()?.mine?.left?.abbrev} vs ${s.firstOrNull()?.mine?.right?.abbrev}\n" + report(s)
        }
        File(dir, "replay-report.txt").writeText(report(snapshots) + "\n=== the same refreshes from every other team's side ===\n" + league)
        System.getenv("ESPN_FIXTURE_OUT")?.let { out ->
            // ESPN_FIXTURE_TEAM: whose side to keep (an abbreviation), yours by default.
            val team = System.getenv("ESPN_FIXTURE_TEAM")
            val owner = (listOf(swid) + owners).first { o -> team == null || series(o).firstOrNull { it.mine != null }?.mine?.left?.abbrev == team }
            anonymise(series(owner), File(out))
        }
    }

    private fun stamp(f: File) = f.name.substringAfter('-').substringBefore('.').toLong()

    private fun report(snapshots: List<Snapshot>): String {
        val out = StringBuilder()
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/New_York"))
        fun at(ms: Long) = time.format(Instant.ofEpochMilli(ms))
        out.appendLine("${snapshots.size} polls, ${at(snapshots.first().fetchedAt)}–${at(snapshots.last().fetchedAt)}")

        var duplicates = 0
        var decreases = 0
        var regressions = 0
        val watch = ScoreWatch(snapshots.first())
        val timeline = ArrayList<Pair<Long, List<ScoreEvent>>>()
        for ((a, b) in snapshots.zipWithNext()) {
            if (a.copy(fetchedAt = 0) == b.copy(fetchedAt = 0)) duplicates++
            val ma = a.mine!!
            val mb = b.mine!!
            val before = (ma.left.starters + ma.right.starters).associateBy { it.name }
            val down = (mb.left.starters + mb.right.starters).mapNotNull { p -> before[p.name]?.let { p to p.points - it.points } }.filter { it.second < -0.005 }
            decreases += down.size
            // Several players and both totals going backwards at once would mean a stale (older) response.
            if (down.size >= 2 && mb.left.points < ma.left.points && mb.right.points < ma.right.points) regressions++
            for ((p, d) in down) out.appendLine("  ${at(b.fetchedAt)} decrease ${p.name} ${p.position} ${"%.2f".format(d)} (${p.game})")
            val moved = (mb.left.starters + mb.right.starters).mapNotNull { p -> before[p.name]?.let { p to p.points - it.points } }.filter { abs(it.second) >= 0.005 }
            if (moved.isNotEmpty()) out.appendLine("  ${at(b.fetchedAt)} +${(b.fetchedAt - a.fetchedAt) / 1000}s " + moved.joinToString { (p, d) -> "${p.name} ${"%+.2f".format(d)}" })
            val totals = listOf(mb.left, mb.right).filter { s -> abs(s.starters.sumOf { it.points } - s.points) > 0.05 }
            for (s in totals) out.appendLine("  ${at(b.fetchedAt)} ${s.abbrev} total ${s.points} != starters ${"%.2f".format(s.starters.sumOf { it.points })}")
            val events = watch.update(b)
            if (events.isNotEmpty()) timeline += b.fetchedAt to events
            for (e in events) out.appendLine("  ${at(b.fetchedAt)} event $e")
        }
        out.appendLine("identical responses: $duplicates; point decreases: $decreases; stale-looking responses: $regressions")

        // The banners a viewer would have seen, polling the pacing every 100 ms between fetches.
        val alerts = Alerts()
        var t = snapshots.first().fetchedAt
        val end = snapshots.last().fetchedAt + 60_000
        var i = 0
        var shown = 0
        while (t <= end) {
            while (i < timeline.size && timeline[i].first <= t) alerts.offer(timeline[i++].second, t)
            alerts.next(t)?.let { shown++; out.appendLine("  ${at(t)} banner ${it.heading} ${it.chip.text} ${it.text} ${it.change} ${it.detail}") }
            t += 100
        }
        out.appendLine("events: ${timeline.sumOf { it.second.size }} in ${timeline.size} polls; banners shown: $shown")
        return out.toString()
    }

    /**
     * The series, your matchup only, with team and player names replaced consistently, as one JSON
     * array in [file], so it can be committed as a fixture.
     */
    private fun anonymise(snapshots: List<Snapshot>, file: File) {
        val teams = HashMap<String, String>()
        val players = HashMap<String, String>()
        fun team(a: String) = teams.getOrPut(a) { "T%02d".format(teams.size + 1) }
        fun player(p: PlayerLine) = if (p.name.endsWith("D/ST")) "Team ${players.getOrPut(p.name) { "%02d".format(players.size + 1) }} D/ST"
            else "Player ${players.getOrPut(p.name) { "%02d".format(players.size + 1) }}"
        fun side(s: Side) = s.copy(abbrev = team(s.abbrev), name = "Team ${team(s.abbrev)}", starters = s.starters.map { it.copy(name = player(it)) })
        fun matchup(m: Matchup) = m.copy(left = side(m.left), right = side(m.right))
        val cache = SnapshotCache(file)
        val array = org.json.JSONArray(snapshots.map { cache.toJson(it.copy(mine = it.mine?.let(::matchup), others = emptyList())) })
        file.parentFile.mkdirs()
        file.writeText(array.toString())
    }
}
