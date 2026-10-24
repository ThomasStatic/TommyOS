package app.lumen.sports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * Shows [TickerView] along the bottom edge in a TYPE_APPLICATION_OVERLAY window.
 *
 * The window is never focusable or touchable, so remote keys keep going to the app underneath.
 * It runs as a foreground service so low-memory kills during 4K playback are unlikely, and it is
 * START_NOT_STICKY: if it is killed anyway (or the TV restarts) it stays off until asked again.
 *
 * Control (see scripts/sports.sh):
 *   am start-foreground-service -n app.lumen.sports/.TickerService [-a app.lumen.sports.SHOW]
 *       [--ef opacity 0.0-1.0] [--ez scroll true|false]
 *   am start-foreground-service -n app.lumen.sports/.TickerService -a app.lumen.sports.HIDE
 */
class TickerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var view: TickerView? = null
    private var params: WindowManager.LayoutParams? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            view?.let { Log.i(TAG, "heartbeat shown=${it.isShown} window=${it.windowVisibility} frames=${it.takeFrames()} alpha=${params?.alpha}") }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground(), even HIDE.
        startForeground(NOTIFICATION_ID, notification())
        if (intent?.action == ACTION_HIDE) {
            Log.i(TAG, "hide requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null && !show()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_OPACITY) == true) setOpacity(intent.getFloatExtra(EXTRA_OPACITY, DEFAULT_OPACITY))
        if (intent?.hasExtra(EXTRA_SCROLL) == true) view?.scrolling = intent.getBooleanExtra(EXTRA_SCROLL, true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        view?.let { v -> try { windowManager().removeView(v) } catch (_: Exception) {} }
        view = null
        Log.i(TAG, "removed")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        Log.i(TAG, "trim memory level=$level")
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
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
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
        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_SCROLL = "scroll"
        const val WINDOW_TITLE = "LumenSportsTicker"
        private const val CHANNEL = "ticker"
        private const val NOTIFICATION_ID = 1
        private const val HEIGHT_DP = 44
        private const val DEFAULT_OPACITY = 0.9f
        private const val HEARTBEAT_MS = 30_000L
    }
}
