package app.lumen.sports

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val snapshot = Snapshot(
        fetchedAt = 1_793_000_000_000L,
        week = 8,
        mine = Matchup(Side("TOM", 98.36, 112.5), Side("ABC", 91.24, null), final = false),
        others = listOf(Matchup(Side("DEF", 87.1, 101.2), Side("GHÏ", 90.3, 95.0), final = true)),
        livePlayers = listOf(PlayerLine("Ja'Marr Chase", "WR", 8.26)),
    )

    @Test
    fun `a saved snapshot reloads exactly, bye weeks included`() {
        val cache = SnapshotCache(File(tmp.root, "snapshot.json"))

        cache.save(snapshot)
        assertEquals(snapshot, SnapshotCache(File(tmp.root, "snapshot.json")).load())

        cache.save(snapshot.copy(mine = null, livePlayers = emptyList()))
        assertEquals(snapshot.copy(mine = null, livePlayers = emptyList()), cache.load())
    }

    @Test
    fun `a missing, empty or damaged cache loads as nothing`() {
        val file = File(tmp.root, "snapshot.json")
        val cache = SnapshotCache(file)
        assertEquals(null, cache.load())

        for (damaged in listOf("", "{\"fetchedAt\": 17930", "[]", "{\"fetchedAt\":1,\"week\":8}")) {
            file.writeText(damaged)
            assertEquals("for content <$damaged>", null, cache.load())
        }
    }
}
