package app.lumen.home

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Fire OS ignores the default-home setting and always shows Amazon's launcher on Home.
 * This service watches window changes only (it cannot read screen content) and brings Lumen
 * forward when Amazon's *home* activity appears and stays. It runs in its own small ":redirect"
 * process so low-memory kills of the UI process don't disable Home.
 *
 * Amazon also routes Settings and other deep links *through* its home activity: home resumes
 * briefly, then e.g. MainSettingsActivity opens on top (measured 0.1–0.74 s later). Home-key
 * presses are not visible to accessibility services on this build (tested), so the redirect waits
 * [SETTLE_MS] and is cancelled if any other real window appears meanwhile.
 *
 * To hide Amazon's home during that wait, a cover (Lumen's wallpaper) is drawn in an
 * accessibility overlay: no extra permission, never focusable or touchable, removed as soon as
 * Lumen or another window appears, and always within [COVER_MAX_MS].
 *
 * Lumen Sports' auto mode: while its ticker runs in auto mode it asks (through [SportsFollowReceiver])
 * to be told which app is in front, and this service passes on each change of package/activity name,
 * nothing else, off the main thread. When Sports hasn't asked, nothing is sent and Sports isn't woken.
 */
class HomeRedirectService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var lastRedirect = 0L
    private var homeSeenAt = 0L
    private var pending = false
    private var cover: View? = null
    private var coverBitmap: Bitmap? = null
    private var coverStamp = 0L
    /** The last real foreground window (package/class), and the last one passed to Lumen Sports. */
    private var lastForeground: String? = null
    private var sentForeground: String? = null

    private val redirect = Runnable {
        pending = false
        lastRedirect = SystemClock.uptimeMillis()
        Log.i(TAG, "redirect after ${lastRedirect - homeSeenAt} ms")
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private val coverFailsafe = Runnable { hideCover(fade = true) }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()

        if (pkg in TRANSIENT_WINDOWS) return
        // Our own cover window reports itself too (as android.widget.ImageView); only Lumen's activities count.
        if (pkg == packageName && !cls.startsWith("$packageName.")) return
        reportForeground("$pkg/$cls")
        val isHome = pkg == AMAZON_LAUNCHER && (cls.endsWith(".HomeActivity_vNext") || cls.endsWith(".HomeActivity"))
        if (!isHome) {
            // Another real window took over (Settings, an app, Lumen itself): don't redirect.
            if (pending) Log.i(TAG, "cancelled by $pkg/$cls after ${SystemClock.uptimeMillis() - homeSeenAt} ms")
            handler.removeCallbacks(redirect)
            pending = false
            // Lumen arriving fades the cover; anything else must be visible immediately.
            hideCover(fade = pkg == packageName)
            // Opening any other app ends a "Go to Amazon home" visit.
            if (pkg != AMAZON_LAUNCHER && pkg != packageName && Prefs.isSuppressed(this)) Prefs.setSuppressed(this, false)
            return
        }
        val suppressed = Prefs.isSuppressed(this)
        val enabled = prefs.redirect
        Log.i(TAG, "amazon home appeared suppressed=$suppressed redirect=$enabled")
        if (suppressed || !enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < DEBOUNCE_MS) return
        homeSeenAt = now
        showCover()
        handler.removeCallbacks(redirect)
        handler.postDelayed(redirect, SETTLE_MS)
        pending = true
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(redirect)
        hideCover(fade = false)
        super.onDestroy()
    }

    private fun showCover() {
        if (cover != null) return
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COVER_FALLBACK)
            loadCoverBitmap()?.let { setImageBitmap(it) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            windowManager().addView(view, params)
            cover = view
            handler.postDelayed(coverFailsafe, COVER_MAX_MS)
        } catch (e: Exception) {
            Log.w(TAG, "cover unavailable: $e")
        }
    }

    private fun hideCover(fade: Boolean) {
        val view = cover ?: return
        cover = null
        handler.removeCallbacks(coverFailsafe)
        val remove = Runnable { try { windowManager().removeView(view) } catch (_: Exception) {} }
        if (fade) view.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction(remove).start() else remove.run()
    }

    /** The UI process writes a small snapshot of the current wallpaper; reload it when it changes. */
    private fun loadCoverBitmap(): Bitmap? {
        val file = File(filesDir, Wallpapers.COVER_FILE)
        if (!file.isFile) return null
        if (coverBitmap == null || file.lastModified() != coverStamp) {
            coverBitmap = BitmapFactory.decodeFile(file.path)
            coverStamp = file.lastModified()
        }
        return coverBitmap
    }

    /** Passes a foreground change to Lumen Sports if it asked for them; it answers whether to keep going. */
    private fun reportForeground(foreground: String) {
        lastForeground = foreground
        if (!File(filesDir, FOLLOW_FILE).exists()) { sentForeground = null; return }
        if (foreground == sentForeground) return
        sentForeground = foreground
        val app = applicationContext
        sports.execute {
            val keep = try {
                app.contentResolver.call(SPORTS_CONTROL, "foreground", foreground, null)?.getBoolean("follow") ?: false
            } catch (e: Exception) {
                Log.w(TAG, "sports unreachable: ${e.javaClass.simpleName}")
                false
            }
            if (!keep) File(app.filesDir, FOLLOW_FILE).delete()
        }
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val TAG = "LumenRedirect"
        private const val DEBOUNCE_MS = 700L
        /** How long Amazon's home must stay up before we bring Lumen forward (cold Settings: ~0.74 s). */
        private const val SETTLE_MS = 900L
        /** The cover never stays longer than this, whatever happens. */
        private const val COVER_MAX_MS = 3000L
        private const val COVER_FADE_MS = 180L
        private const val COVER_FALLBACK = 0xFF05070D.toInt()
        /**
         * Overlays that change windows without the user leaving where they are: system UI, the keyboard,
         * and the Lumen Sports ticker (appearing during the settle must not cancel a Home redirect).
         */
        private val TRANSIENT_WINDOWS = setOf("android", "com.android.systemui", "com.amazon.tv.ime", "app.lumen.sports")
        private const val FOLLOW_FILE = "sports_follow"
        private val SPORTS_CONTROL: Uri = Uri.parse("content://app.lumen.sports.control")
        private val sports = Executors.newSingleThreadExecutor()
        @Volatile private var instance: HomeRedirectService? = null

        /**
         * Lumen Sports starts or stops wanting foreground changes (auto mode). A marker file, like the
         * redirect switch; on start the current app is sent at once so the ticker can decide now.
         */
        fun setFollowSports(context: Context, on: Boolean) {
            val f = File(context.filesDir, FOLLOW_FILE)
            if (on) f.createNewFile() else f.delete()
            val s = instance ?: return
            s.handler.post {
                s.sentForeground = null
                s.lastForeground?.let { if (on) s.reportForeground(it) }
            }
        }
    }
}
