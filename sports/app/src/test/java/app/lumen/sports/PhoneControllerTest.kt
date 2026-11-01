package app.lumen.sports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PhoneControllerTest {
    private var now = 1_800_000_000_000L
    private val key = ByteArray(32) { it.toByte() }
    private var secret: ByteArray? = key
    private val auth = PhoneAuth({ secret }, { now })
    private val limiter = RateLimiter { now }
    private val host = "10.0.0.213:8737"

    private val calls = mutableListOf<String>()
    private var settings = TickerConfig.DEFAULTS
    private val control = object : PhoneApi.Control {
        override fun status() = SportsStatus(calls.lastOrNull() == "show", calls.lastOrNull() == "show", settings, EspnState.OK, now - 5_000,
            signedIn = true, team = "Secret Team", leagueId = "123")
        override fun show() { calls += "show" }
        override fun hide() { calls += "hide" }
        override fun set(changes: Map<String, Any?>) { calls += "set"; settings = TickerConfig.merge(settings, changes) }
    }
    private val api = PhoneApi(control, auth, limiter, { name -> if (name == "phone.html") "<html>".toByteArray() else null }, { now })

    // --- HTTP parsing ---

    private fun parse(raw: String) = Http.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun refused(raw: String): Int = try {
        parse(raw)
        fail("accepted: $raw")
        0
    } catch (e: Http.Refused) {
        e.status
    }

    @Test fun `parses a request with headers and a body`() {
        val r = parse("POST /api/set HTTP/1.1\r\nHost: $host\r\nContent-Length: 4\r\nX-Lumen-Time: 5\r\n\r\n{\"a\"")
        assertEquals("POST", r.method)
        assertEquals("/api/set", r.target)
        assertEquals(host, r.headers["host"])
        assertEquals("5", r.headers["x-lumen-time"])
        assertEquals("{\"a\"", r.body.toString(Charsets.UTF_8))
    }

    @Test fun `refuses oversized, chunked, malformed and truncated requests`() {
        assertEquals(431, refused("GET / HTTP/1.1\r\nX: ${"a".repeat(Http.MAX_HEAD)}\r\n\r\n"))
        assertEquals(431, refused("GET / HTTP/1.1\r\n" + (1..40).joinToString("") { "H$it: v\r\n" } + "\r\n"))
        assertEquals(413, refused("POST /api/set HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"))
        assertEquals(501, refused("POST /api/set HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, refused("GET /\r\n\r\n"))
        assertEquals(400, refused("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(400, refused("GET / HTTP/1.1\r\nno colon\r\n\r\n"))
        assertEquals(400, refused("POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"))
        assertEquals(400, refused("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, refused("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertEquals(400, refused("GET / HTTP/1.1\r\nHost: x"))
    }

    // --- Signatures ---

    @Test fun `HMAC-SHA256 matches RFC 4231 test case 2`() {
        // The page's JavaScript implementation is checked against the same vector (phone.html self-test).
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("Jefe".toByteArray(), "HmacSHA256")) }
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.doFinal("what do ya want for nothing?".toByteArray()).joinToString("") { "%02x".format(it) })
    }

    @Test fun `request signatures match the page and Python`() {
        // Same vector as app/src/test/js/phone-crypto.test.js (the page's JavaScript), computed with Python's hmac.
        assertEquals("27a78c2ef6577857e1d9d314ed1c89db92358a901f186a2bf6dac07d49e4e28e",
            PhoneAuth.sign(key, "POST", "/api/set", 1_800_000_000_000L, "abcdefghijklmnop", "{\"edge\":\"top\",\"note\":\"é\"}".toByteArray()))
    }

    private fun signed(method: String, target: String, body: String = "", time: Long = now, nonce: String = "n" + "0123456789abcdef".substring(0, 15) + calls.size + time,
                       signKey: ByteArray = key, hostHeader: String = host): Http.Request {
        val sig = PhoneAuth.sign(signKey, method, target, time, nonce, body.toByteArray())
        return Http.Request(method, target, mapOf("host" to hostHeader, PhoneAuth.TIME to "$time", PhoneAuth.NONCE to nonce, PhoneAuth.SIG to sig), body.toByteArray())
    }

    @Test fun `a signed request is accepted once`() {
        val req = signed("POST", "/api/show", nonce = "abcdefghijklmnop")
        assertEquals(200, api.handle(req, "10.0.0.42", host).status)
        assertEquals(listOf("show"), calls)
        val again = api.handle(req, "10.0.0.42", host)
        assertEquals(401, again.status)
        assertEquals("replayed", JSONObject(String(again.body)).getString("error"))
        assertEquals(listOf("show"), calls)
    }

    @Test fun `tampering with the path, body or time breaks the signature`() {
        val good = signed("POST", "/api/set", "{\"edge\":\"top\"}")
        val swappedPath = Http.Request("POST", "/api/show", good.headers, good.body)
        assertEquals(401, api.handle(swappedPath, "p", host).status)
        val swappedBody = Http.Request("POST", "/api/set", good.headers, "{\"edge\":\"bottom\"}".toByteArray())
        assertEquals(401, api.handle(swappedBody, "p", host).status)
        val movedTime = Http.Request("POST", "/api/set", good.headers + (PhoneAuth.TIME to "${now + 1}"), good.body)
        assertEquals(401, api.handle(movedTime, "p", host).status)
        assertTrue(calls.isEmpty())
    }

    @Test fun `the wrong key is refused`() {
        val r = api.handle(signed("POST", "/api/hide", signKey = ByteArray(32)), "p", host)
        assertEquals("bad_signature", JSONObject(String(r.body)).getString("error"))
        assertTrue(calls.isEmpty())
    }

    @Test fun `a stale request gets the TV's clock so the page can correct`() {
        val r = api.handle(signed("GET", "/api/status", time = now - 120_000), "p", host)
        assertEquals(401, r.status)
        val json = JSONObject(String(r.body))
        assertEquals("stale", json.getString("error"))
        assertEquals(now, json.getLong("now"))
        // Within the window is fine.
        assertEquals(200, api.handle(signed("GET", "/api/status", time = now - 60_000), "p", host).status)
    }

    @Test fun `unpaired refuses everything signed`() {
        secret = null
        assertEquals("unpaired", JSONObject(String(api.handle(signed("POST", "/api/show"), "p", host).body)).getString("error"))
        assertTrue(calls.isEmpty())
    }

    @Test fun `missing or malformed signature headers are refused`() {
        val bare = Http.Request("POST", "/api/show", mapOf("host" to host), ByteArray(0))
        assertEquals("malformed", JSONObject(String(api.handle(bare, "p", host).body)).getString("error"))
        val shortNonce = signed("POST", "/api/show", nonce = "short")
        assertEquals(401, api.handle(shortNonce, "p", host).status)
        assertTrue(calls.isEmpty())
    }

    // --- Routing ---

    @Test fun `settings arrive as JSON and the answer is the phone status without league data`() {
        val r = api.handle(signed("POST", "/api/set", "{\"edge\":\"top\",\"height\":64}"), "p", host)
        assertEquals(200, r.status)
        val json = JSONObject(String(r.body))
        assertEquals("top", json.getString("edge"))
        assertEquals(64, json.getInt("height"))
        assertFalse(String(r.body).contains("Secret"))
        assertFalse(json.has("team") || json.has("league"))
    }

    @Test fun `bad settings JSON is a 400 after the signature check`() {
        assertEquals(400, api.handle(signed("POST", "/api/set", "not json"), "p", host).status)
        assertTrue(calls.isEmpty())
    }

    @Test fun `another Host is refused (DNS rebinding)`() {
        assertEquals(421, api.handle(signed("POST", "/api/show", hostHeader = "evil.example:8737"), "p", host).status)
        assertEquals(421, api.handle(Http.Request("GET", "/", mapOf("host" to "evil.example"), ByteArray(0)), "p", host).status)
        assertTrue(calls.isEmpty())
    }

    @Test fun `the page is served without auth, with a strict content policy`() {
        val r = api.handle(Http.Request("GET", "/", mapOf("host" to host), ByteArray(0)), "p", host)
        assertEquals(200, r.status)
        assertTrue(r.headers.getValue("Content-Security-Policy").startsWith("default-src 'none'"))
        assertEquals(404, api.handle(Http.Request("GET", "/etc/passwd", mapOf("host" to host), ByteArray(0)), "p", host).status)
        assertEquals(405, api.handle(Http.Request("POST", "/", mapOf("host" to host), ByteArray(0)), "p", host).status)
        assertEquals(405, api.handle(signed("GET", "/api/show"), "p", host).status)
        assertEquals(404, api.handle(signed("POST", "/api/reboot"), "p", host).status)
    }

    // --- Limits ---

    @Test fun `bursts are limited per address and refill over time`() {
        val get = Http.Request("GET", "/", mapOf("host" to host), ByteArray(0))
        repeat(RateLimiter.BURST) { assertEquals(200, api.handle(get, "10.0.0.9", host).status) }
        assertEquals(429, api.handle(get, "10.0.0.9", host).status)
        assertEquals(200, api.handle(get, "10.0.0.10", host).status) // Another address is unaffected.
        now += 1_000
        assertEquals(200, api.handle(get, "10.0.0.9", host).status)
    }

    @Test fun `repeated bad signatures block the address for a minute`() {
        repeat(RateLimiter.MAX_FAILURES) { api.handle(signed("POST", "/api/show", signKey = ByteArray(32)), "10.0.0.66", host) }
        now += 1_000
        assertEquals(429, api.handle(signed("POST", "/api/show"), "10.0.0.66", host).status)
        assertTrue(calls.isEmpty())
        now += RateLimiter.BLOCK_MS
        assertEquals(200, api.handle(signed("POST", "/api/show"), "10.0.0.66", host).status)
    }
}
