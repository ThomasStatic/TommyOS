package app.lumen.sports

import android.content.Context
import android.content.Intent

/**
 * Ticker settings that persist across shows: set with intent extras on any start command
 * (`--ei height 40-96` dp, `--ef opacity 0-1` for the background, `--ei speed 10-240` dp/s,
 * `--ei fps 30|60`, `--ei alert 0-15` s per banner (0: none), `--es edge top|bottom`; see
 * scripts/sports.sh `set`) and kept in shared preferences.
 */
class TickerConfig(context: Context) {
    data class Value(val heightDp: Int, val opacity: Float, val speedDp: Int, val fps: Int, val alertSeconds: Int, val top: Boolean)

    private val prefs = context.getSharedPreferences("ticker", Context.MODE_PRIVATE)

    fun load() = Value(
        heightDp = prefs.getInt(HEIGHT, DEFAULT_HEIGHT_DP),
        opacity = prefs.getFloat(OPACITY, DEFAULT_OPACITY),
        speedDp = prefs.getInt(SPEED, DEFAULT_SPEED_DP),
        fps = prefs.getInt(FPS, DEFAULT_FPS),
        alertSeconds = prefs.getInt(ALERT, DEFAULT_ALERT_S),
        top = prefs.getString(EDGE, BOTTOM) == TOP,
    )

    /** Saves whichever settings [intent] carries, clamped to their ranges, and returns the result. */
    fun update(intent: Intent?): Value {
        val e = prefs.edit()
        intent?.takeIf { it.hasExtra(HEIGHT) }?.let { e.putInt(HEIGHT, it.getIntExtra(HEIGHT, DEFAULT_HEIGHT_DP).coerceIn(40, 96)) }
        intent?.takeIf { it.hasExtra(OPACITY) }?.let { e.putFloat(OPACITY, it.getFloatExtra(OPACITY, DEFAULT_OPACITY).coerceIn(0f, 1f)) }
        intent?.takeIf { it.hasExtra(FPS) }?.let { e.putInt(FPS, if (it.getIntExtra(FPS, DEFAULT_FPS) >= 60) 60 else 30) }
        intent?.takeIf { it.hasExtra(ALERT) }?.let { e.putInt(ALERT, it.getIntExtra(ALERT, DEFAULT_ALERT_S).coerceIn(0, 15)) }
        intent?.takeIf { it.hasExtra(SPEED) }?.let { e.putInt(SPEED, it.getIntExtra(SPEED, DEFAULT_SPEED_DP).coerceIn(10, 240)) }
        intent?.getStringExtra(EDGE)?.takeIf { it == TOP || it == BOTTOM }?.let { e.putString(EDGE, it) }
        e.apply()
        return load()
    }

    companion object {
        const val HEIGHT = "height"
        const val OPACITY = "opacity"
        const val SPEED = "speed"
        const val FPS = "fps"
        const val ALERT = "alert"
        const val EDGE = "edge"
        private const val TOP = "top"
        private const val BOTTOM = "bottom"
        const val DEFAULT_HEIGHT_DP = 56
        const val DEFAULT_OPACITY = 0.9f
        /** 4 px per 30 fps frame on this TV: whole-pixel steps keep the text crisp and the motion even. */
        const val DEFAULT_SPEED_DP = 60
        /**
         * Every frame is GPU-composited by SurfaceFlinger on this TV, sharing the GPU with the video:
         * 60 fps cost ~25 % of a core in the app plus ~15 % in SurfaceFlinger and missed 12–50 % of its
         * frames; 30 fps cost ~12 % plus ~8 % and missed almost none (see REPORT.md).
         */
        const val DEFAULT_FPS = 30
        const val DEFAULT_ALERT_S = 5
    }
}
