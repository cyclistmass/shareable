package wgbg.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** #72 without TTL: which of a host's rooms the start screen clears. */
class ExpiredTest {
    private val now = 1_800_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test fun `only rooms past their expiry, oldest first, a few per launch`() {
        val rooms = listOf("A" to now - day, "B" to now + day, "C" to now - 9 * day, "D" to now - 3 * day, "E" to now - 2 * day)
        assertEquals(listOf("C", "D", "E"), Expired.pick(rooms, now))
        assertEquals(listOf("C", "D", "E", "A"), Expired.pick(rooms, now, max = 10))
        assertEquals(3, Expired.PER_LAUNCH)
    }

    @Test fun `break-it - no expiry, expiring this very moment, nothing to do, a bad limit`() {
        assertEquals(emptyList<String>(), Expired.pick(listOf("OLD" to null, "NOW" to now), now)) // pre-#72 rooms, and the rules' strict <
        assertEquals(emptyList<String>(), Expired.pick(emptyList(), now))
        assertEquals(emptyList<String>(), Expired.pick(listOf("A" to now - day), now, max = 0))
        assertEquals(emptyList<String>(), Expired.pick(listOf("A" to now - day), now, max = -1))
    }

    @Test fun `a game still being played lives as long as its last move`() {
        assertEquals(now + day, Expired.latest(now - 5 * day, now + day))   // started 35 days ago, a move 29 days ago
        assertEquals(now - day, Expired.latest(now - day, now - 3 * day))    // its last move expired first
        assertEquals(now - day, Expired.latest(now - day, null))             // no moves yet
        assertEquals(null, Expired.latest(null, null))
        assertEquals(emptyList<String>(), Expired.pick(listOf("LONG" to Expired.latest(now - 5 * day, now + day)), now))
    }

    @Test fun `a transport without the cloud clears nothing`() {
        assertEquals(emptyList<String>(), InMemoryTransport("host").cleanExpired().join())
    }

    @Test fun `fuzz - every pick is expired, distinct, capped, and the oldest of them`() {
        val rng = java.util.Random(72)
        repeat(5000) {
            val rooms = List(rng.nextInt(12)) { i -> "R$i" to if (rng.nextInt(5) == 0) null else now + (rng.nextInt(61) - 30) * day + rng.nextInt(3) - 1 }
            val max = rng.nextInt(6) - 1
            val picked = Expired.pick(rooms, now, max)
            val at = rooms.toMap()
            val expired = rooms.filter { (_, t) -> t != null && t < now }
            assertEquals(minOf(expired.size, maxOf(max, 0)), picked.size)
            assertEquals(picked.size, picked.toSet().size)
            picked.forEach { assertEquals(true, at[it]!! < now) }
            val left = expired.map { it.first } - picked.toSet()
            if (picked.isNotEmpty()) left.forEach { assertEquals(true, at[it]!! >= at[picked.last()]!!) }
        }
    }
}
