package app.lumen.sports

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks the phone page's signed requests. The page and the TV share a 256-bit secret that crossed
 * between them once, optically (the pairing QR code, in the URL fragment, which browsers never
 * send); it never travels over the network afterwards. Each request carries:
 *
 *   X-Lumen-Time   the phone's clock in epoch ms (corrected by the offset the TV reports)
 *   X-Lumen-Nonce  16-64 random characters [A-Za-z0-9_-]
 *   X-Lumen-Sig    hex HMAC-SHA256(secret, "LUMEN1\n" + method + "\n" + target + "\n" + time + "\n" + nonce + "\n" + body)
 *
 * so someone watching the Wi-Fi learns nothing they can reuse: a request is accepted once, within
 * [WINDOW_MS] of the TV's clock. It does not stop an active attacker on the LAN who rewrites the
 * page as it is served (plain HTTP); see sports/SECURITY.md.
 */
class PhoneAuth(private val secret: () -> ByteArray?, private val clock: () -> Long) {
    enum class Result { OK, UNPAIRED, MALFORMED, STALE, BAD_SIGNATURE, REPLAYED, BUSY }

    /** Nonces seen inside the window, with when they may be forgotten. */
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun verify(method: String, target: String, body: ByteArray, headers: Map<String, String>): Result {
        val key = secret() ?: return Result.UNPAIRED
        val time = headers[TIME]?.toLongOrNull() ?: return Result.MALFORMED
        val nonce = headers[NONCE]?.takeIf { NONCE_SHAPE.matches(it) } ?: return Result.MALFORMED
        val sig = headers[SIG]?.lowercase()?.takeIf { it.length == 64 } ?: return Result.MALFORMED
        val now = clock()
        if (Math.abs(now - time) > WINDOW_MS) return Result.STALE
        val expected = sign(key, method, target, time, nonce, body)
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) return Result.BAD_SIGNATURE
        // Only signed requests reach the nonce cache, so it can't be filled without the secret.
        seen.entries.removeAll { it.value < now }
        if (nonce in seen) return Result.REPLAYED
        if (seen.size >= MAX_NONCES) return Result.BUSY
        seen[nonce] = time + 2 * WINDOW_MS
        return Result.OK
    }

    /** Forgets every nonce (on re-pairing: the old secret's requests are invalid anyway). */
    @Synchronized fun reset() = seen.clear()

    companion object {
        const val TIME = "x-lumen-time"
        const val NONCE = "x-lumen-nonce"
        const val SIG = "x-lumen-sig"
        /** Clock difference tolerated; the page learns the TV's clock from a STALE answer and corrects. */
        const val WINDOW_MS = 90_000L
        private const val MAX_NONCES = 1024
        private val NONCE_SHAPE = Regex("[A-Za-z0-9_-]{16,64}")

        fun sign(key: ByteArray, method: String, target: String, time: Long, nonce: String, body: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            mac.update("LUMEN1\n$method\n$target\n$time\n$nonce\n".toByteArray(Charsets.UTF_8))
            return mac.doFinal(body).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Per-address request limits for the phone listener: [BURST] requests at once, refilled at
 * [PER_SECOND] a second, and an address that sends [MAX_FAILURES] bad signatures within a minute is
 * refused for [BLOCK_MS]. Tracks at most [MAX_PEERS] addresses (oldest forgotten first).
 */
class RateLimiter(private val clock: () -> Long) {
    private class Peer(var tokens: Double, var at: Long, var failures: Int = 0, var failuresSince: Long = 0, var blockedUntil: Long = 0)

    private val peers = object : LinkedHashMap<String, Peer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Peer>?) = size > MAX_PEERS
    }

    /** False when [address] must be refused now (too many requests, or blocked after failures). */
    @Synchronized
    fun allow(address: String): Boolean {
        val now = clock()
        val p = peers.getOrPut(address) { Peer(BURST.toDouble(), now) }
        if (now < p.blockedUntil) return false
        p.tokens = minOf(BURST.toDouble(), p.tokens + (now - p.at) / 1000.0 * PER_SECOND)
        p.at = now
        if (p.tokens < 1) return false
        p.tokens -= 1
        return true
    }

    @Synchronized
    fun failed(address: String) {
        val now = clock()
        val p = peers.getOrPut(address) { Peer(BURST.toDouble(), now) }
        if (now - p.failuresSince > 60_000) { p.failures = 0; p.failuresSince = now }
        if (++p.failures >= MAX_FAILURES) { p.blockedUntil = now + BLOCK_MS; p.failures = 0 }
    }

    companion object {
        const val BURST = 20
        const val PER_SECOND = 2.0
        const val MAX_FAILURES = 5
        const val BLOCK_MS = 60_000L
        const val MAX_PEERS = 64
    }
}
