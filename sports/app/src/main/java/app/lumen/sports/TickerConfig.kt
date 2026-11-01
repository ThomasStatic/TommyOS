package app.lumen.sports

import android.content.Context
import android.os.Bundle

/**
 * Ticker settings that persist across shows, kept in shared preferences. Changed through
 * [ControlProvider] (Lumen, `scripts/sports.sh set`) or the phone controller; every source goes
 * through [merge], which clamps each value to its range and ignores anything malformed.
 *
 * Keys: `height` 40-96 dp, `opacity` 0-1 (background only), `speed` 10-240 dp/s, `fps` 30|60,
 * `alert` 0-15 s per banner (0: none), `edge` top|bottom, `auto` on|off (show only while an [AutoRules]
 * screen is in front), `apps` the auto-mode rules.
 */
class TickerConfig(context: Context) {
    data class Value(
        val heightDp: Int, val opacity: Float, val speedDp: Int, val fps: Int, val alertSeconds: Int, val top: Boolean,
        val auto: Boolean = false, val autoApps: String = AutoRules.DEFAULT,
    )

    private val prefs = context.getSharedPreferences("ticker", Context.MODE_PRIVATE)

    fun load() = Value(
        heightDp = prefs.getInt(HEIGHT, DEFAULT_HEIGHT_DP),
        opacity = prefs.getFloat(OPACITY, DEFAULT_OPACITY),
        speedDp = prefs.getInt(SPEED, DEFAULT_SPEED_DP),
        fps = prefs.getInt(FPS, DEFAULT_FPS),
        alertSeconds = prefs.getInt(ALERT, DEFAULT_ALERT_S),
        top = prefs.getString(EDGE, BOTTOM) == TOP,
        auto = prefs.getBoolean(AUTO, false),
        autoApps = prefs.getString(APPS, null) ?: AutoRules.DEFAULT,
    )

    /** Saves whichever settings [extras] carries (see [merge]) and returns the result. */
    fun update(extras: Bundle?): Value = update(extras?.let { b -> b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) } }.orEmpty())

    fun update(changes: Map<String, Any?>): Value {
        val v = merge(load(), changes)
        prefs.edit()
            .putInt(HEIGHT, v.heightDp).putFloat(OPACITY, v.opacity).putInt(SPEED, v.speedDp).putInt(FPS, v.fps)
            .putInt(ALERT, v.alertSeconds).putString(EDGE, if (v.top) TOP else BOTTOM)
            .putBoolean(AUTO, v.auto).putString(APPS, v.autoApps)
            .apply()
        return v
    }

    companion object {
        const val HEIGHT = "height"
        const val OPACITY = "opacity"
        const val SPEED = "speed"
        const val FPS = "fps"
        const val ALERT = "alert"
        const val EDGE = "edge"
        const val AUTO = "auto"
        const val APPS = "apps"
        const val TOP = "top"
        const val BOTTOM = "bottom"
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

        val DEFAULTS = Value(DEFAULT_HEIGHT_DP, DEFAULT_OPACITY, DEFAULT_SPEED_DP, DEFAULT_FPS, DEFAULT_ALERT_S, top = false)

        /**
         * [old] with the recognised keys of [changes] applied, each clamped to its range. Numbers may
         * arrive as any numeric type or a numeric string (intent extras, provider bundles, JSON);
         * anything else, and unknown keys, are ignored.
         */
        fun merge(old: Value, changes: Map<String, Any?>): Value {
            fun num(key: String): Double? = when (val v = changes[key]) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }
            val edge = (changes[EDGE] as? String)?.trim()?.lowercase()
            val auto = when (val a = changes[AUTO]) {
                is Boolean -> a
                is Number -> a.toInt() != 0
                is String -> when (a.trim().lowercase()) { "on", "true", "1" -> true; "off", "false", "0" -> false; else -> null }
                else -> null
            }
            val apps = (changes[APPS] as? String)?.replace(" ", "")?.takeIf(AutoRules::valid)
            return old.copy(
                heightDp = num(HEIGHT)?.toInt()?.coerceIn(40, 96) ?: old.heightDp,
                opacity = num(OPACITY)?.toFloat()?.coerceIn(0f, 1f) ?: old.opacity,
                speedDp = num(SPEED)?.toInt()?.coerceIn(10, 240) ?: old.speedDp,
                fps = num(FPS)?.let { if (it >= 60) 60 else 30 } ?: old.fps,
                alertSeconds = num(ALERT)?.toInt()?.coerceIn(0, 15) ?: old.alertSeconds,
                top = when (edge) { TOP -> true; BOTTOM -> false; else -> old.top },
                auto = auto ?: old.auto,
                autoApps = apps ?: old.autoApps,
            )
        }
    }
}
