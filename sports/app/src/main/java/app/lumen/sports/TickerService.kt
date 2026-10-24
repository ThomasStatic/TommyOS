package app.lumen.sports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Shows the fantasy ticker along the bottom edge in a TYPE_APPLICATION_OVERLAY window and keeps it
 * fed from ESPN.
 *
 * The window is never focusable or touchable, so remote keys keep going to the app underneath.
 * [LeagueFetcher] runs on its own thread, only while the ticker is shown and the screen is on; the
 * ticker draws the cached snapshot immediately and never blanks on network errors. Foreground
 * service so low-memory kills during 4K playback are unlikely; START_NOT_STICKY and no boot
 * receiver, so after a kill or restart it stays off until asked. The manifest limits callers to
 * `adb shell` (and this app).
 *
 * Control (see scripts/sports.sh):
 *   am start-foreground-service -n app.lumen.sports/.TickerService [--ef opacity 0.0-1.0]
 *   am start-foreground-service -n app.lumen.sports/.TickerService -a app.lumen.sports.HIDE
 */
class TickerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var cache: SnapshotCache
    private lateinit var credentials: Credentials
    private var view: TickerView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenReceiver: BroadcastReceiver? = null

    // Main thread: what the ticker currently shows.
    private var snapshot: Snapshot? = null
    private var message: String? = null

    // Written on the main thread, read by the worker before it schedules the next poll.
    @Volatile private var screenOn = true
    // Written by the worker, read when rendering: data older than this is labelled "as of".
    @Volatile private var staleAfterMs = TickerItems.STALE_MS

    // Worker thread only.
    private var fetcher: LeagueFetcher? = null
    private var failures = 0

    private val poll = Runnable { pollOnce() }
    private val resetFailures = Runnable { failures = 0 }

    /** Re-renders once a minute so the "as of" label appears when data goes stale. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            view?.let { Log.i(TAG, "heartbeat shown=${it.isShown} window=${it.windowVisibility} frames=${it.takeFrames()}") }
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        cache = SnapshotCache(File(noBackupFilesDir, "snapshot.json"))
        credentials = Credentials(this)
        workerThread = HandlerThread("espn").apply { start() }
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground(), even HIDE.
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            ACTION_HIDE -> {
                Log.i(TAG, "hide requested")
                stopSelf()
            }
            ACTION_REFRESH -> if (view != null) restartPolling() else stopSelf()
            else -> {
                if (view == null) {
                    if (!show()) { stopSelf(); return START_NOT_STICKY }
                    snapshot = cache.load()
                    render()
                    main.postDelayed(tick, TICK_MS)
                    screenOn = getSystemService(PowerManager::class.java).isInteractive
                    registerScreenReceiver()
                    restartPolling()
                }
                if (intent?.hasExtra(EXTRA_OPACITY) == true) setOpacity(intent.getFloatExtra(EXTRA_OPACITY, DEFAULT_OPACITY))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        screenReceiver?.let { unregisterReceiver(it) }
        view?.let { v -> try { windowManager().removeView(v) } catch (_: Exception) {} }
        view = null
        Log.i(TAG, "removed")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        Log.i(TAG, "trim memory level=$level")
    }

    // --- Fetching (worker thread) ---

    /** Fetches now and resumes the schedule, if the screen is on. Main thread. */
    private fun restartPolling() {
        worker.removeCallbacks(poll)
        if (!screenOn) return
        worker.post(resetFailures)
        worker.post(poll)
    }

    private fun pollOnce() {
        val creds = credentials.load()
        if (creds == null) {
            publish(null, "Not signed in to ESPN   run scripts/sports.sh auth on the PC")
            return // AuthReceiver restarts polling once credentials arrive.
        }
        val f = fetcher?.takeIf { it.creds == creds } ?: LeagueFetcher(creds, cache.load()?.week).also { fetcher = it }
        try {
            val result = f.fetch(Instant.now())
            if (!running) return // Hidden while the request was in flight.
            cache.save(result)
            failures = 0
            publish(result, null)
            Log.i(TAG, "fetched week ${result.week}: ${result.others.size + (if (result.mine != null) 1 else 0)} matchups, ${result.livePlayers.size} live players")
        } catch (e: EspnClient.AuthException) {
            Log.w(TAG, "fetch: ${e.message}")
            publish(null, "ESPN sign-in expired   run scripts/sports.sh auth on the PC")
            return // No retries with bad credentials.
        } catch (e: Exception) {
            failures++
            Log.w(TAG, "fetch failed ($failures in a row): $e")
            if (failures >= UNAVAILABLE_AFTER) publish(null, "ESPN scores unavailable   retrying")
        }
        val delay = PollPolicy.nextDelayMs(Instant.now(), f.kickoffs, failures)
        staleAfterMs = maxOf(TickerItems.STALE_MS, 2 * delay)
        // A screen-off that arrived during the request already cleared the queue; don't re-arm.
        if (running && screenOn) worker.postDelayed(poll, delay)
    }

    /** Hands a fetch result (or a status message) to the main thread. */
    private fun publish(newSnapshot: Snapshot?, newMessage: String?) = main.post {
        if (newSnapshot != null) snapshot = newSnapshot
        message = newMessage
        render()
    }

    // --- Rendering (main thread) ---

    private fun render() {
        val v = view ?: return
        val s = snapshot
        val items = ArrayList<TickerItem>()
        message?.let { items += TickerItem(it, emphasis = true) }
        if (s != null) items += TickerItems.from(s, Instant.now(), ZoneId.systemDefault(), staleAfterMs)
        if (items.isEmpty()) items += TickerItem("Loading ESPN fantasy scores…")
        v.setItems(items)
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                Log.i(TAG, if (screenOn) "screen on: polling resumed" else "screen off: polling paused")
                if (screenOn) restartPolling() else worker.removeCallbacks(poll)
            }
        }
        registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        screenReceiver = r
    }

    private fun show(): Boolean {
        val allowed = Settings.canDrawOverlays(this)
        Log.i(TAG, "canDrawOverlays=$allowed")
        val v = TickerView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (HEIGHT_DP * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
            alpha = DEFAULT_OPACITY
            title = WINDOW_TITLE
        }
        return try {
            windowManager().addView(v, p)
            view = v
            params = p
            Log.i(TAG, "shown")
            true
        } catch (e: Exception) {
            Log.w(TAG, "overlay refused: $e")
            false
        }
    }

    private fun setOpacity(value: Float) {
        val v = view ?: return
        val p = params ?: return
        p.alpha = value.coerceIn(0f, 1f)
        windowManager().updateViewLayout(v, p)
        Log.i(TAG, "opacity=${p.alpha}")
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    companion object {
        private const val TAG = "LumenSports"
        const val ACTION_HIDE = "app.lumen.sports.HIDE"
        const val ACTION_REFRESH = "app.lumen.sports.REFRESH"
        const val EXTRA_OPACITY = "opacity"
        const val WINDOW_TITLE = "LumenSportsTicker"
        private const val CHANNEL = "ticker"
        private const val NOTIFICATION_ID = 1
        private const val HEIGHT_DP = 44
        private const val DEFAULT_OPACITY = 0.9f
        private const val TICK_MS = 60_000L
        /** Consecutive failures before an empty ticker says ESPN is unavailable (cached scores stay up regardless). */
        private const val UNAVAILABLE_AFTER = 3

        /** True while the service exists in this process; lets [AuthReceiver] avoid starting it. */
        @Volatile private var running = false

        fun refreshIfRunning(context: Context) {
            if (!running) return
            context.startForegroundService(Intent(context, TickerService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
