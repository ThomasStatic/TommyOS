package app.lumen.sports

import org.json.JSONObject

/**
 * The phone page's endpoints, independent of sockets and Android:
 *
 *   GET  /            the page (no secrets in it; it reads the pairing key from its own URL fragment)
 *   GET  /icon.png    home-screen icon
 *   GET  /api/status  \
 *   POST /api/show     |  signed (see [PhoneAuth]); each answers with the status: whether the ticker
 *   POST /api/hide     |  is on, its edge and settings, ESPN's state and the last refresh time.
 *   POST /api/set     /   Body: a JSON object of settings (see [TickerConfig]).
 *
 * Nothing from the league (teams, scores, players) or the ESPN credentials is reachable here.
 * Requests must name the TV's own address in Host (a page on another site that rebinds its DNS name
 * to the TV gets nothing), each address is rate-limited, and one sending bad signatures is blocked
 * for a minute ([RateLimiter]).
 */
class PhoneApi(
    private val control: Control,
    private val auth: PhoneAuth,
    private val limiter: RateLimiter,
    private val assets: (String) -> ByteArray?,
    private val clock: () -> Long,
) {
    interface Control {
        fun status(): SportsStatus
        fun show()
        fun hide()
        fun set(changes: Map<String, Any?>)
    }

    /** What happened, for the listener's log (never with secrets or bodies). */
    var lastEvent: String = ""
        private set

    fun handle(req: Http.Request, peer: String, host: String): Http.Response {
        if (!limiter.allow(peer)) return done("limited", Http.Response.error(429))
        if (req.headers["host"] != host) return done("wrong host", Http.Response.error(421))
        val path = req.target.substringBefore('?')
        if (!path.startsWith("/api/")) {
            if (req.method != "GET") return done("method", Http.Response.error(405))
            return when (path) {
                "/" -> page()
                "/icon.png" -> assets("icon.png")?.let { Http.Response(200, "image/png", it) } ?: Http.Response.error(404)
                else -> Http.Response.error(404)
            }.also { lastEvent = "" }
        }
        val wanted = API[path] ?: return done("unknown api", Http.Response.error(404))
        if (req.method != wanted) return done("method", Http.Response.error(405))
        when (val result = auth.verify(req.method, req.target, req.body, req.headers)) {
            PhoneAuth.Result.OK -> {}
            PhoneAuth.Result.BAD_SIGNATURE, PhoneAuth.Result.MALFORMED, PhoneAuth.Result.REPLAYED -> {
                limiter.failed(peer)
                return done("refused: ${result.name.lowercase()}", unauthorized(result))
            }
            else -> return done("refused: ${result.name.lowercase()}", unauthorized(result))
        }
        when (path) {
            "/api/show" -> control.show()
            "/api/hide" -> control.hide()
            "/api/set" -> {
                val changes = try {
                    JSONObject(req.body.toString(Charsets.UTF_8)).let { o -> o.keys().asSequence().associateWith { o.opt(it) } }
                } catch (_: Exception) {
                    return done("bad settings", Http.Response.error(400))
                }
                control.set(changes)
            }
        }
        lastEvent = path.removePrefix("/api/")
        return Http.Response.json(200, control.status().toPhoneJson(clock()).toString())
    }

    private fun done(event: String, r: Http.Response): Http.Response {
        lastEvent = event
        return r
    }

    /** Says why, and the TV's clock, so the page can correct its time and retry once. */
    private fun unauthorized(result: PhoneAuth.Result) = Http.Response.json(
        if (result == PhoneAuth.Result.BUSY) 503 else 401,
        JSONObject().put("error", result.name.lowercase()).put("now", clock()).toString(),
    )

    private fun page(): Http.Response {
        val html = assets("phone.html") ?: return Http.Response.error(404)
        return Http.Response(200, "text/html", html, mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        ))
    }

    private companion object {
        val API = mapOf("/api/status" to "GET", "/api/show" to "POST", "/api/hide" to "POST", "/api/set" to "POST")
    }
}
