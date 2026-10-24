package app.lumen.home

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** All user choices. Every write bumps [stamp] so screens can tell when to refresh. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    private val noRedirectFile = File(context.filesDir, NO_REDIRECT_FILE)

    val stamp: Long get() = sp.getLong("stamp", 0L)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) =
        sp.edit().apply(block).putLong("stamp", stamp + 1).apply()

    /** Ordered package names pinned to the Dock. */
    var dock: List<String>
        get() = sp.getString("dock", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(value) = edit { putString("dock", value.distinct().joinToString(",")) }

    val dockInitialized: Boolean get() = sp.contains("dock")

    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", DEFAULT_HIDDEN) ?: DEFAULT_HIDDEN)
        set(value) = edit { putStringSet("hidden", HashSet(value)) }

    /** "builtin:<index>" or "file:<name>" (see [Wallpapers]). */
    var wallpaper: String
        get() = sp.getString("wallpaper", "builtin:0") ?: "builtin:0"
        set(value) = edit { putString("wallpaper", value) }

    val hasTheme: Boolean get() = sp.contains("theme")

    var theme: ThemeId
        get() = if (sp.getString("theme", null) == ThemeId.CLASSIC.name) ThemeId.CLASSIC else ThemeId.CINEMA
        set(value) = edit { putString("theme", value.name) }

    /** One-off upgrades of saved choices, run at startup. */
    fun migrate() {
        // First run of the themed build: existing installs were on the original (Classic) look.
        if (!hasTheme) {
            if (wallpaper == Themes.CLASSIC.defaultWallpaper) wallpaper = Themes.CINEMA.defaultWallpaper
            theme = ThemeId.CINEMA
        }
        // Scene wallpapers replace Obsidian as Cinema's default; Obsidian stays available.
        if (!sp.getBoolean("scenes_v1", false)) {
            if (theme == ThemeId.CINEMA && wallpaper == Themes.OLD_CINEMA_DEFAULT) wallpaper = Themes.CINEMA.defaultWallpaper
            edit { putBoolean("scenes_v1", true) }
        }
    }

    /**
     * Switch look. If the wallpaper is still the old theme's default, swap to the new theme's default
     * too, so each look arrives with the wallpaper it was designed around; a custom choice is kept.
     */
    fun switchTheme(to: ThemeId) {
        val from = Themes.of(theme)
        if (wallpaper == from.defaultWallpaper) wallpaper = Themes.of(to).defaultWallpaper
        theme = to
    }

    var use24h: Boolean
        get() = sp.getBoolean("use24h", false)
        set(value) = edit { putBoolean("use24h", value) }

    /**
     * Whether the Home button should bring Lumen forward over Amazon's home screen.
     * A marker file rather than SharedPreferences: the redirect service runs in its own process.
     */
    var redirect: Boolean
        get() = !noRedirectFile.exists()
        set(value) {
            if (value) noRedirectFile.delete() else noRedirectFile.createNewFile()
            edit { }
        }

    companion object {
        private const val NO_REDIRECT_FILE = "redirect_off"
        private const val SUPPRESS_FILE = "redirect_suppressed"

        /** One-off visit to Amazon's home (set by Lumen settings, cleared when another app opens). Cross-process. */
        fun isSuppressed(context: Context) = File(context.filesDir, SUPPRESS_FILE).exists()

        fun setSuppressed(context: Context, value: Boolean) {
            val f = File(context.filesDir, SUPPRESS_FILE)
            if (value) f.createNewFile() else if (f.exists()) f.delete()
        }

        /** Vendor test/diagnostic entries that ship with a launcher icon. Unhide from Lumen settings. */
        val DEFAULT_HIDDEN = setOf(
            "com.droidlogic.exoplayer2.demo",
            "com.amazon.firetv.troubleshooting",
            "com.amazon.ftv.profilepicker",      // Fire TV Profile UI
            "com.amazon.tv.quicksettings.ui",    // Quick Settings (also on long-press Home)
            "com.amazon.ftv.screensaver",        // Screen Saver settings
            "com.amazon.ssm",                    // System Status Monitor
        )
    }
}
