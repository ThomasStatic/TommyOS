package app.lumen.home

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Lumen's own preferences: theme, wallpaper, clock, Home button behaviour, hidden apps.
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
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            addView(list)
        }, MATCH, MATCH)
        setContentView(root)
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

    private fun section(title: String) = list.addView(text(title.uppercase(), Type.OVERLINE, Ink.TEXT_SECONDARY, medium = true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(if (cinema) 40 else 32), 0, dp(Space.S))
    })

    private fun note(message: String) = list.addView(text(message, Type.CAPTION, Ink.TEXT_TERTIARY).apply {
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
        list.addView(row, LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(Space.XS) })
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
        const val WP = "wp:"
        const val ROW_WIDTH_DP = 640
        const val PREVIEW_DELAY_MS = 220L
        const val PREVIEW_DIM = 0.28f
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
