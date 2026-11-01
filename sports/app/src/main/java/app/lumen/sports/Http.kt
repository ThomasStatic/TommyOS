package app.lumen.sports

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough HTTP/1.1 for the phone page: one request per connection, no chunked bodies, strict
 * limits on every part (anything over them is refused before it is buffered).
 */
object Http {
    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    class Response(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun json(status: Int, json: String) = Response(status, "application/json", json.toByteArray(Charsets.UTF_8))
            fun error(status: Int) = Response(status, "text/plain", (REASONS[status] ?: "Error").toByteArray())
        }
    }

    /** A request that broke the rules; answered with [status] and the connection closed. */
    class Refused(val status: Int) : Exception("HTTP $status")

    const val MAX_HEAD = 4096
    const val MAX_HEADERS = 32
    const val MAX_BODY = 2048

    fun read(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw Refused(400)
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() >= MAX_HEAD) throw Refused(431)
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n").split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || parts[0].isEmpty() || !parts[1].startsWith("/")) throw Refused(400)
        if (lines.size - 1 > MAX_HEADERS) throw Refused(431)
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line[0] == ' ' || line[0] == '\t') throw Refused(400)
            val name = line.substring(0, colon).trim().lowercase()
            if (name in headers && name in SINGLE) throw Refused(400)
            headers[name] = line.substring(colon + 1).trim()
        }
        if ("transfer-encoding" in headers) throw Refused(501)
        val length = headers["content-length"]?.let { v -> v.takeIf { it.all(Char::isDigit) && it.length <= 6 }?.toInt() ?: throw Refused(400) } ?: 0
        if (length > MAX_BODY) throw Refused(413)
        val body = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(body, n, length - n)
            if (r < 0) throw Refused(400)
            n += r
        }
        return Request(parts[0], parts[1], headers, body)
    }

    fun write(out: OutputStream, r: Response) {
        val head = StringBuilder("HTTP/1.1 ${r.status} ${REASONS[r.status] ?: "Status"}\r\n")
        head.append("Content-Type: ${r.type}${if (r.type.startsWith("text/") || r.type.endsWith("json")) "; charset=utf-8" else ""}\r\n")
        head.append("Content-Length: ${r.body.size}\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        for ((k, v) in r.headers) head.append("$k: $v\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    private val SINGLE = setOf("content-length", "host", PhoneAuth.TIME, PhoneAuth.NONCE, PhoneAuth.SIG)

    private val REASONS = mapOf(
        200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed",
        413 to "Payload Too Large", 421 to "Misdirected Request", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        500 to "Internal Server Error", 501 to "Not Implemented", 503 to "Service Unavailable",
    )
}
