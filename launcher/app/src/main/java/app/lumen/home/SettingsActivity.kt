package app.lumen.home

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lumen's own preferences: theme, wallpaper, clock, Home button behaviour, hidden apps, and the
 * Lumen Sports ticker when that app is installed (through [SportsLink]).
 * Wallpapers preview live: focusing a thumbnail shows it full screen behind the menu; OK keeps it.
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var look: LumenTheme
    private lateinit var wallpaperView: ImageView
    private lateinit var dim: View
    private lateinit var list: LinearLayout
    private var previewing: String? = null
    private var rebuilding = false
    // Bitmaps only this screen uses (thumbnails, previews): freed as soon as they are replaced or the
    // screen closes, so browsing wallpapers doesn't leave megabytes behind for the home screen.
    private val thumbs = mutableListOf<Bitmap>()
    private var ownedPreview: Bitmap? = null // focus jumps around while rows are rebuilt; don't treat that as browsing
    private val previewTask = Runnable { previewing?.let { showWallpaper(it, preview = true) } }
    private val density by lazy { resources.displayMetrics.density }
    private val cinema get() = look.id == ThemeId.CINEMA
    /** Where the row helpers add views: the list, or the Sports box while it is (re)built on its own. */
    private lateinit var into: LinearLayout
    private val sports by lazy { SportsLink(this) }
    private val recheckSports = Runnable { sports.status(::showSports) }
    private var sportsStatus: SportsLink.Status? = null
    private var sportsLoaded = false
    private var sportsBox: LinearLayout? = null
    private lateinit var pairing: PairingPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        look = Themes.of(prefs.theme)
        setTheme(look.styleRes)
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaperView, MATCH, MATCH)
        dim = View(this)
        root.addView(dim, MATCH, MATCH)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(object : ScrollView(this) {
            // Scroll the focused row clear of the screen edges, where the Sports ticker draws.
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
                super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect).apply { inset(0, -dp(EDGE_CLEARANCE_DP)) })
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            addView(list)
        }, MATCH, MATCH)
        setContentView(root)
        pairing = PairingPanel(root, cinema) {
            sports.status(::showSports)
            sportsBox?.findViewWithTag<View>(SPORTS_PAIR)?.requestFocus()
        }
        styleScreen()
        build()
        list.post { list.findViewWithTag<View>(THEME_ROW)?.requestFocus() }
        // Preview a wallpaper while its thumbnail has focus; put the chosen one back when focus leaves.
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val key = (now as? TileView)?.key?.takeIf { it.startsWith(WP) }?.removePrefix(WP)
            if (rebuilding || key == previewing) return@addOnGlobalFocusChangeListener
            previewing = key
            list.removeCallbacks(previewTask)
            if (key != null) list.postDelayed(previewTask, PREVIEW_DELAY_MS) else showWallpaper(prefs.wallpaper, preview = false)
        }
    }

    override fun onPause() {
        super.onPause()
        list.removeCallbacks(recheckSports)
    }

    override fun onDestroy() {
        wallpaperView.setImageDrawable(null)
        ownedPreview?.recycle()
        ownedPreview = null
        recycleThumbs()
        super.onDestroy()
    }

    private fun recycleThumbs() {
        thumbs.forEach { it.recycle() }
        thumbs.clear()
    }

    override fun onResume() {
        super.onResume()
        showWallpaper(prefs.wallpaper, preview = false)
        if (sportsBox != null) sports.status(::showSports)
    }

    /** Re-read the theme after it is switched here, so this screen restyles too. */
    private fun styleScreen() {
        look = Themes.of(prefs.theme)
        // Cinema: shade the menu side and let the scene show on the right. Classic: flat shade.
        dim.background = if (cinema) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF0000000.toInt(), 0xC7000000.toInt(), 0x8C000000.toInt()))
        } else {
            GradientDrawable().apply { setColor(0xB8000000.toInt()) }
        }
        val side = dp(if (cinema) 88 else 64)
        list.setPadding(side, dp(if (cinema) 56 else 48), side, dp(64))
    }

    private fun showWallpaper(key: String, preview: Boolean) {
        val dm = resources.displayMetrics
        // The saved wallpaper comes from the cache the home screen shares; previews are private copies.
        val shared = !preview && key == prefs.wallpaper
        val bitmap = Wallpapers.load(this, key, dm.widthPixels, dm.heightPixels, shared)
        wallpaperView.setImageBitmap(bitmap)
        ownedPreview?.takeIf { it !== bitmap }?.recycle()
        ownedPreview = if (shared) null else bitmap
        dim.animate().cancel()
        dim.animate().alpha(if (preview) PREVIEW_DIM else 1f).setDuration(260).start()
    }

    private fun build() {
        list.removeAllViews()
        into = list
        recycleThumbs()
        list.addView(text("Lumen", Type.TITLE, Ink.TEXT, medium = true))
        list.addView(text("Your home screen, without the ads.", Type.CAPTION, Ink.TEXT_SECONDARY).apply { setPadding(0, dp(Space.XS), 0, 0) })

        section("Look")
        action("Theme", if (cinema) "Cinema" else "Classic") {
            prefs.switchTheme(if (cinema) ThemeId.CLASSIC else ThemeId.CINEMA)
            styleScreen()
            showWallpaper(prefs.wallpaper, preview = false)
            rebuild(THEME_ROW)
        }.tag = THEME_ROW
        note("Cinema: scene wallpapers, a floating glass dock and minimal controls. Classic: the original Lumen layout.")

        section("Wallpaper")
        thumbnails("Scenes", Wallpapers.scenes())
        thumbnails("Gradients", Wallpapers.gradients())
        thumbnails("Your photos", Wallpapers.photos(this))
        note("Highlight a wallpaper to preview it, press OK to use it.")
        note("Add your own: adb push photo.jpg /sdcard/Android/data/$packageName/files/wallpapers/")

        section("Clock")
        toggle("24-hour clock", { prefs.use24h }) { prefs.use24h = !prefs.use24h }

        section("Home button")
        toggle("Show Lumen when Home is pressed", { prefs.redirect }) { prefs.redirect = !prefs.redirect }
        note(if (redirectServiceEnabled()) "The Home button service is on."
             else "The Home button service is off. It is turned on from a computer with ADB (see the project README).")
        action("Go to the Amazon home screen") {
            Prefs.setSuppressed(this, true)
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeRedirectService.AMAZON_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        sportsBox = if (sports.installed) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also {
            list.addView(it)
            buildSports(it)
        } else null

        val hidden = prefs.hidden.sortedBy { labelOf(it).lowercase() }
        if (hidden.isNotEmpty()) {
            section("Hidden apps")
            for (pkg in hidden) action(labelOf(pkg), "Show") {
                prefs.hidden = prefs.hidden - pkg
                rebuild(THEME_ROW)
            }
        }

        section("About")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        note("Lumen $version · no network access · everything stays on this TV")
    }

    // --- Sports ---

    /** A new status from the Sports app: rebuild its rows only, keeping focus on the same row. */
    private fun showSports(status: SportsLink.Status?) {
        val box = sportsBox ?: return
        // Just shown: the first ESPN answer comes within a second or two, so look again rather than say "Waiting".
        list.removeCallbacks(recheckSports)
        if (status != null && status.visible && status.espn == "unknown") list.postDelayed(recheckSports, RECHECK_MS)
        if (sportsLoaded && status == sportsStatus) return
        sportsLoaded = true
        sportsStatus = status
        val focused = (currentFocus?.takeIf { box.indexOfChild(it) >= 0 }?.tag as? String)
        buildSports(box)
        focused?.let { box.findViewWithTag<View>(it)?.requestFocus() }
    }

    private fun buildSports(box: LinearLayout) {
        box.removeAllViews()
        into = box
        section("Sports")
        val st = sportsStatus
        if (!sportsLoaded) {
            note("Checking Lumen Sports…")
            into = list
            return
        }
        if (st == null) {
            note("Lumen Sports isn't answering. If it was installed after this version of Lumen, reinstall Lumen.")
            into = list
            return
        }
        val toggle = action("Fantasy ticker", onOff(st.enabled)) {}
        toggle.tag = SPORTS_TOGGLE
        toggle.setOnClickListener {
            toggle.set("Fantasy ticker", if (st.enabled) "Turning off…" else "Turning on…")
            if (st.enabled) sports.hide(::showSports) else sports.show(::showSports)
        }
        note(sportsLine(st))
        action("Show it", if (st.auto) "During TSN games" else "Always") {
            sports.set("auto", if (st.auto) "off" else "on", ::showSports)
        }.tag = "sports:auto"
        if (st.auto) note(when {
            !redirectServiceEnabled() -> "During TSN games needs the Home button service, which is off: the ticker won't appear."
            st.enabled && !st.visible -> "On, and waiting: it appears when a TSN game is playing, and steps aside everywhere else."
            else -> "Appears only while TSN is playing a game: not over TSN's menus or sign-in, other apps, or Fire TV settings."
        })
        action("Position", if (st.top) "Top" else "Bottom") {
            sports.set("edge", if (st.top) "bottom" else "top", ::showSports)
        }.tag = "sports:edge"
        stepper("Height", "height", HEIGHTS, st.heightDp) { if (it == 56) "56 (standard)" else "$it" }
        stepper("Background", "opacity", OPACITIES, Math.round(st.opacity * 100)) { "$it%" }
        stepper("Crawl speed", "speed", SPEEDS, st.speedDp) { if (it == 60) "60 (standard)" else "$it" }
        stepper("Score alerts", "alert", ALERTS, st.alertSeconds) { if (it == 0) "Off" else "$it s on screen" }
        note("Left and right change a setting; OK steps through it. The ticker updates as you go.")

        action("Phone control", onOff(st.phone)) { sports.setPhone(!st.phone, ::showSports) }.tag = "sports:phone"
        when {
            !st.phone -> note("Show, hide and move the ticker from your phone over Wi-Fi, without leaving the game.")
            !st.phoneListening -> note("Waiting for Wi-Fi. The phone page comes back by itself when the TV is online.")
            else -> {
                action("Pair a phone", if (st.phonePaired) "Show code" else null) { showPairing(renew = false) }.tag = SPORTS_PAIR
                if (st.phonePaired) action("Reset pairing", "New code") { showPairing(renew = true) }.tag = "sports:repair"
                note(if (st.phonePaired) "Reset pairing makes a new code: phones paired before stop working until they scan it."
                     else "Pair once, then add the page to your iPhone's Home Screen.")
            }
        }
        into = list
    }

    private fun showPairing(renew: Boolean) {
        sports.pair(renew) { b ->
            when {
                b == null -> sports.status(::showSports)
                b.getBoolean("ok") -> pairing.show(b.getString("qr").orEmpty(), b.getString("address").orEmpty(), renew,
                    sportsStatus?.takeIf { it.visible }?.let { if (it.top) Gravity.TOP else Gravity.BOTTOM } ?: 0)
                else -> sports.status(::showSports) // Phone control went off or Wi-Fi dropped: the rows say so.
            }
        }
    }

    override fun onBackPressed() {
        if (pairing.isShowing) pairing.dismiss() else super.onBackPressed()
    }

    /** ESPN connection, last update, and whose team the ticker follows. */
    private fun sportsLine(st: SportsLink.Status): String {
        val updated = st.lastRefresh?.let { " · updated ${clock(it)}" }.orEmpty()
        val espn = when {
            !st.signedIn || st.espn == "signed_out" -> "Not signed in to ESPN. Sign in once from the computer (see the setup guide)"
            st.espn == "expired" -> "ESPN sign-in expired. Sign in again from the computer (see the setup guide)$updated"
            st.espn == "unavailable" -> "ESPN isn't answering; retrying$updated"
            st.espn == "ok" -> "ESPN connected$updated"
            !st.enabled -> "Scores update while the ticker is on$updated"
            else -> "Waiting for ESPN$updated"
        }.let { if (it.endsWith(".")) it else "$it." } // "p.m." already ends the sentence
        val team = listOfNotNull(st.team, st.league?.let { "league $it" }).joinToString(" · ")
        return if (team.isEmpty()) espn else "$espn\n$team"
    }

    private fun clock(ms: Long): String {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_YEAR)
        val time = if (prefs.use24h) "H:mm" else "h:mm a"
        return SimpleDateFormat(if (today) time else "EEE $time", Locale.getDefault()).format(Date(ms))
    }

    /**
     * A row that steps through [values] (OK: next, wrapping; left/right: down/up) and saves each
     * choice as [key] in Sports. [current] picks the nearest value.
     */
    private fun stepper(label: String, key: String, values: List<Int>, current: Int, format: (Int) -> String) {
        var index = values.indices.minByOrNull { Math.abs(values[it] - current) } ?: 0
        val row = action(label, format(values[index])) {}
        row.tag = "sports:$key"
        fun choose(i: Int) {
            index = i
            row.set(label, format(values[i]))
            val v = values[i]
            sports.set(key, if (key == "opacity") v / 100f else v) { sportsStatus = it }
        }
        row.setOnClickListener { choose((index + 1) % values.size) }
        row.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (index > 0) choose(index - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (index < values.size - 1) choose(index + 1); true }
                else -> false
            }
        }
    }

    private fun rebuild(focusTag: String) {
        rebuilding = true
        build()
        list.findViewWithTag<View>(focusTag)?.requestFocus()
        rebuilding = false
    }

    private fun thumbnails(heading: String, keys: List<String>) {
        if (keys.isEmpty()) return
        list.addView(text(heading, Type.LABEL, Ink.TEXT_SECONDARY).apply { setPadding(0, dp(Space.L), 0, 0) })
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        list.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(Space.M), 0, 0)
            addView(row)
        })
        val style = look.gridTile.copy(caption = true)
        for (key in keys) {
            val thumb = Wallpapers.load(this, key, dp(style.widthDp), dp(style.heightDp), shared = false).also { thumbs += it }
            row.addView(TileView(this, style).apply {
                this.key = "$WP$key"
                title = Wallpapers.label(key) + if (key == prefs.wallpaper) "  ✓" else ""
                setBanner(BitmapDrawable(resources, thumb))
                setOnClickListener {
                    prefs.wallpaper = key
                    previewing = key
                    showWallpaper(key, preview = true)
                    rebuild("$WP$key")
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(style.gapDp - 4) })
        }
    }

    private fun redirectServiceEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun section(title: String) = into.addView(text(title.uppercase(), Type.OVERLINE, Ink.TEXT_SECONDARY, medium = true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(if (cinema) 40 else 32), 0, dp(Space.S))
    })

    private fun note(message: String) = into.addView(text(message, Type.CAPTION, Ink.TEXT_TERTIARY).apply {
        setPadding(dp(if (cinema) Space.XL else 14), dp(Space.S), 0, 0)
        maxWidth = dp(ROW_WIDTH_DP + 80)
    })

    private fun toggle(label: String, value: () -> Boolean, flip: () -> Unit) {
        val row = action(label, onOff(value())) {}
        row.setOnClickListener { flip(); row.set(label, onOff(value())) }
    }

    private fun action(label: String, value: String? = null, onClick: () -> Unit): MenuRow {
        val row = MenuRow(this, cinema).set(label, value)
        row.setOnClickListener { onClick() }
        val width = if (cinema) dp(ROW_WIDTH_DP) else WRAP
        into.addView(row, LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(Space.XS) })
        return row
    }

    private fun text(value: String, sp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"
    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val THEME_ROW = "row:theme"
        const val EDGE_CLEARANCE_DP = 112 // a 56 dp ticker and its margin, with room to spare
        const val RECHECK_MS = 2500L
        const val SPORTS_TOGGLE = "sports:on"
        const val SPORTS_PAIR = "sports:pair"
        val HEIGHTS = listOf(44, 48, 52, 56, 60, 64, 68, 72, 80, 88, 96)
        val OPACITIES = listOf(40, 50, 60, 70, 80, 90, 100)
        val SPEEDS = listOf(30, 45, 60, 75, 90, 120)
        val ALERTS = listOf(0, 3, 5, 8, 10, 15)
        const val WP = "wp:"
        const val ROW_WIDTH_DP = 640
        const val PREVIEW_DELAY_MS = 220L
        const val PREVIEW_DIM = 0.28f
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
