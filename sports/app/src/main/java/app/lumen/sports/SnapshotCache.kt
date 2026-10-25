package app.lumen.sports

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last good [Snapshot], kept on disk so the ticker can draw scores the moment it is shown,
 * before (or without) a network round trip. Writes are atomic, so a crash mid-write never leaves a
 * half-written snapshot.
 */
class SnapshotCache(private val file: File) {
    fun save(snapshot: Snapshot) = writeAtomically(file, toJson(snapshot).toString())

    /** Null when there is no usable snapshot (never saved, or damaged); the ticker then waits for a fetch. */
    fun load(): Snapshot? = try {
        fromJson(JSONObject(file.readText()))
    } catch (_: Exception) {
        null
    }

    private fun toJson(s: Snapshot) = JSONObject()
        .put("fetchedAt", s.fetchedAt)
        .put("week", s.week)
        .put("mine", s.mine?.let(::matchupJson) ?: JSONObject.NULL)
        .put("others", JSONArray(s.others.map(::matchupJson)))

    private fun matchupJson(m: Matchup) = JSONObject().put("left", sideJson(m.left)).put("right", sideJson(m.right)).put("final", m.final)

    private fun sideJson(s: Side) = JSONObject()
        .put("team", s.team).put("name", s.name).put("pts", s.points).put("proj", s.projected ?: JSONObject.NULL)
        .put("starters", JSONArray(s.starters.map(::playerJson)))

    private fun playerJson(p: PlayerLine) = JSONObject()
        .put("name", p.name).put("pos", p.position).put("pts", p.points).put("proj", p.projected ?: JSONObject.NULL).put("game", p.game.name)

    private fun fromJson(o: JSONObject) = Snapshot(
        fetchedAt = o.getLong("fetchedAt"),
        week = o.getInt("week"),
        mine = o.optJSONObject("mine")?.let(::matchup),
        others = o.getJSONArray("others").objects().map(::matchup),
    )

    private fun matchup(o: JSONObject) = Matchup(side(o.getJSONObject("left")), side(o.getJSONObject("right")), o.getBoolean("final"))

    private fun side(o: JSONObject) = Side(
        o.getString("team"), o.getDouble("pts"), proj(o), o.getString("name"),
        o.getJSONArray("starters").objects().map { PlayerLine(it.getString("name"), it.getString("pos"), it.getDouble("pts"), proj(it), Game.valueOf(it.getString("game"))) },
    )

    private fun proj(o: JSONObject) = if (o.isNull("proj")) null else o.getDouble("proj")
}
