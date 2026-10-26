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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Shows the fantasy ticker along the bottom (or top) edge, inside the TV-safe margins, in a
 * TYPE_APPLICATION_OVERLAY window and keeps it fed from ESPN.
 *
 * The window is never focusable or touchable, so remote keys keep going to the app underneath.
 * [LeagueFetcher] runs on its own thread, only while the ticker is shown and the screen is on; the
 * ticker draws the cached snapshot immediately and never blanks on network errors. Foreground
 * service so low-memory kills during 4K playback are unlikely; START_NOT_STICKY and no boot
 * receiver, so after a kill or restart it stays off until asked. The manifest limits callers to
 * `adb shell` (and this app). A non-focusable window never sees remote keys, so it can't tell when
 * an app's player controls appear; the `edge` setting moves it out of their way instead.
 *
 * Control (see scripts/sports.sh):
 *   am start-foreground-service -n app.lumen.sports/.TickerService [settings: see TickerConfig]
 *   am start-foreground-service -n app.lumen.sports/.TickerService -a app.lumen.sports.HIDE
 *   am start-foreground-service -n app.lumen.sports/.TickerService -a app.lumen.sports.PREVIEW --es preview highlight|stale|offline
 */
class TickerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var cache: SnapshotCache
    private lateinit var credentials: Credentials
    private lateinit var config: TickerConfig
    private var view: TickerView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Main thread: what the ticker currently shows.
    private var snapshot: Snapshot? = null
    private var notice: TickerItems.Notice? = null
    /** Set by the stale preview; the next fetch result clears it. */
    private var previewStale = false

    // Written on the main thread, read by the worker before it schedules the next poll.
    @Volatile private var screenOn = true
    // Written by the worker, read when rendering: data older than this is labelled "as of".
    @Volatile private var staleAfterMs = TickerItems.STALE_MS

    // Worker thread only.
    private var fetcher: LeagueFetcher? = null
    private var failures = 0
    /** Compares each fetch with the one before for alerts; seeded from the cache so a restart replays nothing. */
    private var watch: ScoreWatch? = null

    private val poll = Runnable { pollOnce() }
    private val resetFailures = Runnable { failures = 0 }

    /** Re-renders once a minute so the "as of" label appears when data goes stale. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        cache = cache(this)
        credentials = Credentials(this)
        config = TickerConfig(this)
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
            ACTION_PREVIEW -> if (view != null) preview(intent.getStringExtra(EXTRA_PREVIEW)) else stopSelf()
            else -> {
                val settings = config.update(intent)
                if (view == null) {
                    if (!show(settings)) { stopSelf(); return START_NOT_STICKY }
                    snapshot = cache.load()
                    render()
                    main.postDelayed(tick, TICK_MS)
                    screenOn = getSystemService(PowerManager::class.java).isInteractive
                    view?.setScreenOn(screenOn)
                    registerScreenReceiver()
                    registerNetworkCallback()
                    restartPolling()
                } else {
                    apply(settings)
                }
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
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        view?.let { v -> try { windowManager().removeView(v) } catch (_: Exception) {} }
        view = null
        Log.i(TAG, "removed")
        super.onDestroy()
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
            cache.clear() // Again here: a fetch in flight during sign-out may have saved after AuthProvider cleared it.
            watch = null
            publish(null, SIGNED_OUT, clearScores = true)
            return // AuthProvider restarts polling once credentials arrive.
        }
        val f = fetcher?.takeIf { it.creds == creds } ?: cache.load().let { cached ->
            watch = ScoreWatch(cached)
            LeagueFetcher(creds, cached?.week).also { fetcher = it }
        }
        try {
            val result = f.fetch(Instant.now())
            if (!running) return // Hidden while the request was in flight.
            cache.save(result)
            failures = 0
            val events = watch?.update(result).orEmpty()
            publish(result, null, events = events)
            val live = result.mine?.let { m -> (m.left.starters + m.right.starters).count { it.game == Game.LIVE } } ?: 0
            Log.i(TAG, "fetched week ${result.week}: ${result.others.size + (if (result.mine != null) 1 else 0)} matchups, $live live players, ${events.size} alert events")
        } catch (e: EspnClient.AuthException) {
            Log.w(TAG, "fetch: ${e.message}")
            publish(null, EXPIRED)
            return // No retries with bad credentials.
        } catch (e: Exception) {
            failures++
            Log.w(TAG, "fetch failed ($failures in a row): $e")
            if (failures >= UNAVAILABLE_AFTER) publish(null, UNAVAILABLE)
        }
        val now = Instant.now()
        // From the normal cadence, not the backed-off one: failing fetches must not postpone the label.
        staleAfterMs = maxOf(TickerItems.STALE_MS, 2 * PollPolicy.nextDelayMs(now, f.kickoffs, 0))
        // A screen-off that arrived during the request already cleared the queue; don't re-arm.
        if (running && screenOn) worker.postDelayed(poll, PollPolicy.nextDelayMs(now, f.kickoffs, failures))
    }

    /**
     * Hands a fetch result (or a problem) to the main thread. [clearScores] drops the shown scores
     * (signed out); [events] are the changes since the previous fetch.
     */
    private fun publish(
        newSnapshot: Snapshot?,
        newNotice: TickerItems.Notice?,
        clearScores: Boolean = false,
        events: List<ScoreEvent> = emptyList(),
    ) = main.post {
        if (newSnapshot != null || clearScores) snapshot = newSnapshot
        notice = newNotice
        previewStale = false
        render()
        view?.offerAlerts(events)
    }

    // --- Rendering (main thread) ---

    private fun render() {
        val v = view ?: return
        v.setContent(TickerItems.from(snapshot, notice, Instant.now(), ZoneId.systemDefault(), if (previewStale) 0 else staleAfterMs))
    }

    /**
     * Shows a state on the current scores for design review (scripts/sports.sh preview): highlights
     * as if both teams' top scorers had just scored, or the scores going stale, or ESPN failing. The
     * next fetch result replaces the stale and offline previews.
     */
    private fun preview(what: String?) {
        val s = snapshot ?: return
        val m = s.mine
        when (what) {
            "highlight" -> if (m != null) {
                fun Side.rewind(by: Double): Side {
                    val top = starters.maxByOrNull { it.points } ?: return this
                    return copy(points = points - by, starters = starters.map { if (it === top) it.copy(points = it.points - by) else it })
                }
                val before = s.copy(fetchedAt = s.fetchedAt - 30_000, mine = m.copy(left = m.left.rewind(6.6), right = m.right.rewind(4.4)))
                view?.offerAlerts(ScoreWatch(before).update(s))
            }
            "stale" -> { previewStale = true; render() }
            "offline" -> { notice = UNAVAILABLE; render() }
        }
    }

    /**
     * Retries as soon as a network comes back after failed fetches. Otherwise the backoff (4 min by the
     * third failure, up to 15) kept the ticker on stale scores long after the network had recovered.
     * A network already up when this registers changes nothing: there are no failures to recover from.
     */
    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                worker.post {
                    if (failures > 0 && screenOn && running) {
                        Log.i(TAG, "network back after $failures failed fetches: retrying now")
                        worker.removeCallbacks(poll)
                        pollOnce()
                    }
                }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                Log.i(TAG, if (screenOn) "screen on: polling and crawl resumed" else "screen off: polling and crawl paused")
                view?.setScreenOn(screenOn)
                if (screenOn) restartPolling() else worker.removeCallbacks(poll)
            }
        }
        registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        screenReceiver = r
    }

    private fun show(settings: TickerConfig.Value): Boolean {
        val v = TickerView(this).apply { setOpacity(settings.opacity); setMotion(settings.speedDp, settings.fps); setAlertDuration(settings.alertSeconds * 1000L) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            place(this, settings)
            title = WINDOW_TITLE
        }
        return try {
            windowManager().addView(v, p)
            view = v
            params = p
            Log.i(TAG, "shown: $settings")
            true
        } catch (e: Exception) {
            Log.w(TAG, "overlay refused: $e")
            false
        }
    }

    /** Applies changed settings to the showing ticker: opacity and speed are drawn, size and edge move the window. */
    private fun apply(settings: TickerConfig.Value) {
        val v = view ?: return
        val p = params ?: return
        v.setOpacity(settings.opacity)
        v.setMotion(settings.speedDp, settings.fps)
        v.setAlertDuration(settings.alertSeconds * 1000L)
        val old = Triple(p.width, p.height, p.gravity)
        place(p, settings)
        if (Triple(p.width, p.height, p.gravity) != old) windowManager().updateViewLayout(v, p)
        Log.i(TAG, "settings: $settings")
    }

    /** Sizes and positions the window inside the TV-safe margins (Android TV's 48 dp at the sides, 27 dp top and bottom). */
    private fun place(p: WindowManager.LayoutParams, settings: TickerConfig.Value) {
        val metrics = resources.displayMetrics
        p.width = metrics.widthPixels - 2 * (SAFE_X_DP * metrics.density).toInt()
        p.height = (settings.heightDp * metrics.density).toInt()
        p.gravity = (if (settings.top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        p.y = (SAFE_Y_DP * metrics.density).toInt()
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
        const val ACTION_PREVIEW = "app.lumen.sports.PREVIEW"
        const val EXTRA_PREVIEW = "preview"
        const val WINDOW_TITLE = "LumenSportsTicker"
        private const val CHANNEL = "ticker"
        private const val NOTIFICATION_ID = 1
        private const val SAFE_X_DP = 48
        private const val SAFE_Y_DP = 27
        private const val TICK_MS = 60_000L
        /** Consecutive failures before the ticker says ESPN is unavailable (cached scores stay up regardless). */
        private const val UNAVAILABLE_AFTER = 3
        private val SIGNED_OUT = TickerItems.Notice("Not signed in to ESPN · run scripts/sports.sh auth on the PC", "ESPN signed out")
        private val EXPIRED = TickerItems.Notice("ESPN sign-in expired · run scripts/sports.sh auth on the PC", "ESPN sign-in expired")
        private val UNAVAILABLE = TickerItems.Notice("ESPN scores unavailable · retrying", "ESPN unavailable")

        /** True while the service exists in this process; lets [AuthProvider] avoid starting it. */
        @Volatile private var running = false

        /** The last good scores, shared with [AuthProvider] so signing out forgets them. */
        fun cache(context: Context) = SnapshotCache(File(context.noBackupFilesDir, "snapshot.json"))

        fun refreshIfRunning(context: Context) {
            if (!running) return
            context.startForegroundService(Intent(context, TickerService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
