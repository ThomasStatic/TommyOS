package app.lumen.sports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * The phone controller: a small HTTP listener on the TV's Wi-Fi address (port [PORT]) serving the
 * phone page and its signed commands ([PhoneApi]). Runs only while phone control is switched on, in
 * Lumen or with `scripts/sports.sh phone on`; then also after a reboot or an update ([BootReceiver]).
 *
 * Idle cost: one thread blocked in accept(), no polling and no timers. Binds to the current private
 * IPv4 address only, never to all interfaces, and follows the network: Wi-Fi lost closes the socket,
 * a new address rebinds. Answers only private-network peers, at most [MAX_CLIENTS] at a time, each
 * with a [TIMEOUT_MS] deadline. Logs which command came from which LAN address, never keys or bodies.
 *
 * A foreground service so lmkd doesn't silently take the phone's way in; START_STICKY so a kill
 * brings it back. Settings → Applications → Lumen Sports → Force stop ends it until Lumen or the
 * PC turns phone control on again (a force-stopped app gets no boot broadcast either).
 */
class PhoneService : Service() {
    private lateinit var api: PhoneApi
    private lateinit var secret: PhoneSecret
    private val workers = Executors.newFixedThreadPool(2)
    private val slots = Semaphore(MAX_CLIENTS)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val lock = Any()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    /** Decrypted once; reloaded after pairing changes ([secretChanged]). */
    @Volatile private var cachedSecret: ByteArray? = null
    @Volatile private var secretLoaded = false
    private var icon: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        secret = PhoneSecret(this)
        val auth = PhoneAuth({ currentSecret() }, System::currentTimeMillis)
        authRef = auth
        api = PhoneApi(Control(this), auth, RateLimiter(System::currentTimeMillis), ::asset, System::currentTimeMillis)
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = bind(privateAddress(lp))
            override fun onLost(network: Network) = bind(null)
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        // The callback reports the current network straight away; this covers a TV with none.
        cm.activeNetwork?.let { cm.getLinkProperties(it) }?.let { bind(privateAddress(it)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY } // Switched off while it was being restarted.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        authRef = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        bind(null)
        workers.shutdownNow()
        Log.i(TAG, "phone control stopped")
        super.onDestroy()
    }

    private fun currentSecret(): ByteArray? {
        if (!secretLoaded) {
            cachedSecret = secret.load()
            secretLoaded = true
        }
        return cachedSecret
    }

    /** (Re)binds the listener to [address], or closes it for null. No-op when already bound there. */
    private fun bind(address: InetAddress?) = synchronized(lock) {
        if (server?.inetAddress == address && (address == null || server?.isClosed == false)) return@synchronized
        server?.let { s -> try { s.close() } catch (_: Exception) {} }
        server = null
        state = State()
        if (address == null) {
            Log.i(TAG, "phone control: no private network address, not listening")
            return@synchronized
        }
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, PORT), BACKLOG) }
        } catch (e: Exception) {
            Log.w(TAG, "phone control: can't listen on ${address.hostAddress}:$PORT: $e")
            return@synchronized
        }
        server = s
        val host = "${address.hostAddress}:$PORT"
        state = State(listening = true, address = "http://$host/")
        acceptThread = Thread({ acceptLoop(s, host) }, "phone-accept").apply { start() }
        Log.i(TAG, "phone control listening on $host")
    }

    private fun acceptLoop(s: ServerSocket, host: String) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: Exception) { break }
            val peer = socket.inetAddress
            if (!isLocal(peer) || !slots.tryAcquire()) {
                try { socket.close() } catch (_: Exception) {}
                continue
            }
            try {
                workers.execute {
                    try { serve(socket, peer.hostAddress ?: "?", host) } finally { slots.release() }
                }
            } catch (_: Exception) {
                slots.release()
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun serve(socket: Socket, peer: String, host: String) = socket.use {
        try {
            it.soTimeout = TIMEOUT_MS
            val out = it.getOutputStream()
            val response = try {
                api.handle(Http.read(it.getInputStream().buffered()), peer, host)
            } catch (e: Http.Refused) {
                Log.i(TAG, "phone: refused request from $peer (${e.status})")
                Http.Response.error(e.status)
            }
            if (api.lastEvent.isNotEmpty()) Log.i(TAG, "phone: ${api.lastEvent} from $peer")
            Http.write(out, response)
        } catch (e: Exception) {
            Log.w(TAG, "phone: connection from $peer failed: ${e.javaClass.simpleName}")
        }
    }

    private fun asset(name: String): ByteArray? = when (name) {
        "phone.html" -> try { assets.open("phone.html").use { it.readBytes() } } catch (_: Exception) { null }
        "icon.png" -> icon ?: renderIcon().also { icon = it }
        else -> null
    }

    /** The home-screen icon (180 px, iOS rounds the corners): the ticker bar on Lumen's near-black. */
    private fun renderIcon(): ByteArray {
        val size = 180
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF1C1C20.toInt(), 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0xF2F2F2F4.toInt()
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        p.textSize = 92f
        p.textAlign = Paint.Align.CENTER
        c.drawText("L", size / 2f, 104f, p)
        p.color = 0xE61C1C20.toInt()
        c.drawRoundRect(RectF(22f, 124f, 158f, 152f), 14f, 14f, p)
        p.color = 0xFFE5484D.toInt()
        c.drawCircle(38f, 138f, 5f, p)
        p.color = 0xFFF5F5F7.toInt()
        c.drawRoundRect(RectF(52f, 135f, 112f, 141f), 3f, 3f, p)
        p.color = 0xFFE3B341.toInt()
        c.drawRoundRect(RectF(120f, 135f, 144f, 141f), 3f, 3f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.phone_channel_name), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.phone_notification_text))
            .setOngoing(true)
            .build()
    }

    /** What the phone may do: the same calls as Lumen, waiting briefly so the answer shows the result. */
    private class Control(private val context: Context) : PhoneApi.Control {
        override fun status() = Sports.status(context, includeLeague = false)
        override fun show() { Sports.show(context); settle { TickerService.live.visible } }
        override fun hide() { Sports.hide(context); settle { !TickerService.isRunning } }
        override fun set(changes: Map<String, Any?>) = Sports.set(context, changes)

        private fun settle(done: () -> Boolean) {
            val until = System.currentTimeMillis() + SETTLE_MS
            while (!done() && System.currentTimeMillis() < until) Thread.sleep(50)
        }
    }

    data class State(val listening: Boolean = false, val address: String? = null)

    companion object {
        private const val TAG = "LumenSports"
        const val PORT = 8737
        private const val BACKLOG = 8
        private const val MAX_CLIENTS = 4
        private const val TIMEOUT_MS = 5_000
        private const val SETTLE_MS = 2_000L
        private const val CHANNEL = "phone"
        private const val NOTIFICATION_ID = 2
        private const val PREFS = "phone"
        private const val ENABLED = "enabled"

        @Volatile var state = State()
            private set
        @Volatile private var instance: PhoneService? = null
        @Volatile private var authRef: PhoneAuth? = null

        fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

        /** Switches phone control on or off; on persists across reboots and updates. */
        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
            val intent = Intent(context, PhoneService::class.java)
            if (on) context.startForegroundService(intent) else context.stopService(intent)
            Log.i(TAG, "phone control ${if (on) "on" else "off"}")
        }

        /** Pairing changed: drop the cached secret and the nonces seen under the old one. */
        fun secretChanged() {
            instance?.let { it.secretLoaded = false; it.cachedSecret = null }
            authRef?.reset()
        }

        /** The pairing link: the page's address with the secret in the fragment (never sent by browsers). */
        fun pairingUrl(address: String, secret: ByteArray) =
            address + "#k=" + Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        private fun privateAddress(lp: LinkProperties): InetAddress? =
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

        private fun isLocal(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress
    }
}
