package app.lumen.home

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Lumen's side of Lumen Sports (a separate app, `app.lumen.sports`, with its own network access):
 * switches and configures the fantasy ticker through its control provider. Only apps signed with
 * Lumen's key may call it (a signature permission), and Lumen itself still has no network access.
 *
 * Calls run off the main thread (the first one may start the Sports process) and answer on it.
 */
class SportsLink(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The ticker's state as the Sports app reports it. */
    data class Status(
        val enabled: Boolean,
        /** On screen now (in auto mode, only while TSN's player is in front). */
        val visible: Boolean,
        val top: Boolean,
        /** Auto mode: shown only during TSN playback. */
        val auto: Boolean,
        val heightDp: Int,
        val opacity: Float,
        val speedDp: Int,
        val alertSeconds: Int,
        /** ok, signed_out, expired, unavailable or unknown. */
        val espn: String,
        val signedIn: Boolean,
        /** Epoch ms of the last scores from ESPN, or null. */
        val lastRefresh: Long?,
        val team: String?,
        val league: String?,
        val phone: Boolean,
        val phoneListening: Boolean,
        val phoneAddress: String?,
        val phonePaired: Boolean,
    )

    val installed: Boolean
        get() = try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }

    /** [done] gets null if Sports can't be reached (not installed, or the permission is missing). */
    fun status(done: (Status?) -> Unit) = run({ call("status")?.toStatus() }, done)

    fun show(done: (Status?) -> Unit) = command("show", null, null, done)
    fun hide(done: (Status?) -> Unit) = command("hide", null, null, done)

    /** Phone control on or off (the listener starts or stops in Lumen Sports). */
    fun setPhone(on: Boolean, done: (Status?) -> Unit) = command("phone", if (on) "on" else "off", null, done)

    /** Saves one setting (see the Sports app's TickerConfig); a showing ticker applies it at once. */
    fun set(key: String, value: Any, done: (Status?) -> Unit) = command("set", null, Bundle().apply {
        when (value) {
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            else -> putString(key, value.toString())
        }
    }, done)

    /** Runs [method], waits for the ticker to settle, then reports the new status. */
    private fun command(method: String, arg: String?, extras: Bundle?, done: (Status?) -> Unit) = run({
        call(method, arg, extras) ?: return@run null
        Thread.sleep(SETTLE_MS) // The ticker starts or stops asynchronously; report its state after that.
        call("status")?.toStatus()
    }, done)

    /**
     * The pairing QR code ([renew]: with a new key, unpairing every phone). [done] gets the bundle
     * (`ok`, `qr`, `address`, or `error`: phone-off / no-network), or null if Sports can't be reached.
     */
    fun pair(renew: Boolean, done: (Bundle?) -> Unit) = run({ call("pair", if (renew) "new" else null, requireOk = false) }, done)

    private fun <T> run(work: () -> T, done: (T) -> Unit) {
        worker.execute {
            val result = work()
            main.post { done(result) }
        }
    }

    private fun call(method: String, arg: String? = null, extras: Bundle? = null, requireOk: Boolean = true): Bundle? = try {
        context.contentResolver.call(URI, method, arg, extras)?.takeIf { !requireOk || it.getBoolean("ok") }
    } catch (e: Exception) {
        Log.w(TAG, "sports $method: $e")
        null
    }

    private fun Bundle.toStatus() = Status(
        enabled = getBoolean("enabled"),
        visible = getBoolean("visible"),
        top = getString("edge") == "top",
        auto = getBoolean("auto"),
        heightDp = getInt("height"),
        opacity = getFloat("opacity"),
        speedDp = getInt("speed"),
        alertSeconds = getInt("alert"),
        espn = getString("espn") ?: "unknown",
        signedIn = getBoolean("signedIn"),
        lastRefresh = getLong("lastRefresh", -1).takeIf { it > 0 },
        team = getString("team"),
        league = getString("league"),
        phone = getBoolean("phone"),
        phoneListening = getBoolean("phoneListening"),
        phoneAddress = getString("phoneAddress"),
        phonePaired = getBoolean("phonePaired"),
    )

    companion object {
        const val PACKAGE = "app.lumen.sports"
        private const val TAG = "Lumen"
        private val URI: Uri = Uri.parse("content://$PACKAGE.control")
        private const val SETTLE_MS = 400L
        private val worker = Executors.newSingleThreadExecutor()
    }
}
