package app.lumen.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var look: LumenTheme
    private lateinit var root: FrameLayout
    private lateinit var content: ViewGroup
    private lateinit var wallpaperView: ImageView
    private lateinit var frosted: FrostedPanel
    private lateinit var dockRow: LinearLayout
    private lateinit var systemRow: LinearLayout
    private lateinit var appsGrid: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var clock: TextClock
    private lateinit var options: OptionsPanel
    // Present only in some layouts: Classic scrolls its rows; Cinema keeps All Apps in an overlay.
    private var scroll: ScrollView? = null
    private var appsLabel: View? = null
    private var appsOverlay: View? = null
    private var scrim: View? = null

    private var movingPkg: String? = null
    private var lastFocusKey: String? = null
    private var loadedWallpaper: String? = null
    private var coverWallpaper: String? = null // wallpaper last written for the redirect cover
    private var seenStamp = -1L
    private val density by lazy { resources.displayMetrics.density }
    private val location = IntArray(2)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = rebuildApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        prefs.migrate()
        look = Themes.of(prefs.theme)
        setTheme(look.styleRes)
        super.onCreate(savedInstanceState)
        setContentView(look.layoutRes)

        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        wallpaperView = findViewById(R.id.wallpaper)
        frosted = findViewById(R.id.frosted)
        dockRow = findViewById(R.id.dockRow)
        systemRow = findViewById(R.id.systemRow)
        appsGrid = findViewById(R.id.appsGrid)
        heroTitle = findViewById(R.id.heroTitle)
        heroSubtitle = findViewById(R.id.heroSubtitle)
        clock = findViewById(R.id.clock)
        scroll = findViewById(R.id.scroll)
        appsLabel = findViewById(R.id.appsLabel)
        appsOverlay = findViewById(R.id.appsOverlay)
        scrim = findViewById(R.id.scrim)
        options = OptionsPanel(root, look.centredMenus)
        frosted.glass = look.glass

        // The frosted dock samples the wallpaper by screen position, so redraw it as rows scroll.
        scroll?.setOnScrollChangeListener { _, _, _, _, _ -> frosted.invalidate() }
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        buildSystemRow()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.theme != look.id) { recreate(); return } // theme switched in Lumen settings
        applyWallpaper()
        val format = if (prefs.use24h) "HH:mm" else "h:mm"
        clock.format12Hour = format
        clock.format24Hour = format
        if (prefs.stamp != seenStamp) rebuildApps() else restoreFocus()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // In the background under memory pressure: drop the wallpaper and tile artwork (most of our
        // memory) so streaming apps keep theirs. Everything is rebuilt in onResume.
        if (level >= TRIM_MEMORY_BACKGROUND && !hasWindowFocus()) {
            wallpaperView.setImageDrawable(null)
            frosted.blurred = null
            Wallpapers.clearCache()
            dockRow.removeAllViews()
            appsGrid.removeAllViews()
            loadedWallpaper = null
            seenStamp = -1L
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Arrives on every Home press (via the redirect service). Keep the last focus, drop transient modes.
        if (movingPkg != null) endMove()
        options.dismiss()
        if (isAppsOpen()) closeApps(restore = false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (options.isShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (!down) options.dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (movingPkg != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveDock(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveDock(+1); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU -> { if (down) endMove(); return true }
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (!down) (currentFocus as? TileView)?.takeIf { it.isLongClickable }?.performLongClick()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Back closes All Apps; on the home screen itself there is nowhere to go back to.
                if (!down && isAppsOpen()) closeApps(restore = true)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyWallpaper() {
        val key = prefs.wallpaper
        if (key == loadedWallpaper) return
        val dm = resources.displayMetrics
        val bitmap = Wallpapers.load(this, key, dm.widthPixels, dm.heightPixels)
        wallpaperView.setImageBitmap(bitmap)
        val blurred = Wallpapers.blur(bitmap)
        frosted.blurred = blurred
        // All Apps sits on the same scene, frosted and deeply shaded, so it feels like one place.
        appsOverlay?.background = LayerDrawable(arrayOf(BitmapDrawable(resources, blurred), ColorDrawable(APPS_SHADE)))
        if (key != coverWallpaper) {
            Wallpapers.writeCover(this, bitmap)
            coverWallpaper = key
        }
        loadedWallpaper = key
    }

    // ---- Controls row: inputs, Settings, (Apps), Lumen ----

    private fun buildSystemRow() {
        systemRow.removeAllViews()
        for (input in Apps.inputs(this)) {
            addControl("input:${input.id}", input.label, "Input", R.drawable.ic_hdmi) {
                launch(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))
            }
        }
        addControl("sys:settings", "Settings", "Fire TV settings", R.drawable.ic_gear) {
            // Open Amazon's settings grid directly; the generic SETTINGS intent detours via Amazon's home.
            val direct = Intent(AMAZON_SETTINGS_ACTION)
                .addCategory(AMAZON_SETTINGS_CATEGORY)
                .setClassName(HomeRedirectService.AMAZON_LAUNCHER, "${HomeRedirectService.AMAZON_LAUNCHER}.ui.MainSettingsActivity")
            if (direct.resolveActivity(packageManager) != null) launch(direct) else launch(Intent(Settings.ACTION_SETTINGS))
        }
        if (appsOverlay != null) {
            addControl("sys:apps", "Apps", "All apps", R.drawable.ic_apps) { openApps() }
        }
        addControl("sys:lumen", "Lumen", "Wallpaper, theme, clock and Home button", R.drawable.ic_tune) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun addControl(key: String, title: String, subtitle: String, glyph: Int, action: () -> Unit) {
        val view: View = if (look.controlsAsChips) {
            ChipView(this).apply {
                this.key = key; this.title = title; this.subtitle = subtitle
                set(glyph, title)
                setOnClickListener { action() }
                onFocused = this@HomeActivity::showHero
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(Space.S - 2); marginEnd = dp(Space.S - 2) }
            }
        } else {
            TileView(this, look.dockTile).apply {
                this.key = key; this.title = title; this.subtitle = subtitle
                setGlyph(glyph, title)
                setOnClickListener { action() }
                onFocused = this@HomeActivity::showHero
                layoutParams = tileParams(look.dockTile)
            }
        }
        systemRow.addView(view)
    }

    // ---- Dock and All Apps ----

    private fun rebuildApps() {
        val apps = Apps.load(this, look.dockTile.widthDp, look.dockTile.heightDp)
        if (!prefs.dockInitialized) prefs.dock = Apps.defaultDock(apps)
        seenStamp = prefs.stamp
        val byPkg = apps.associateBy { it.pkg }
        val dockPkgs = prefs.dock.filter { it in byPkg }
        val hidden = prefs.hidden

        dockRow.removeAllViews()
        dockPkgs.forEach { dockRow.addView(appTile(byPkg.getValue(it), inDock = true)) }
        if (dockPkgs.isEmpty()) {
            val where = if (appsOverlay != null) "in Apps" else "below"
            dockRow.addView(hint("Press the Menu button (☰) on any app $where to add it here"))
        }

        appsGrid.removeAllViews()
        val rest = apps.filter { it.pkg !in dockPkgs && it.pkg !in hidden }
        if (appsOverlay == null) appsLabel?.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        val columns = gridColumns()
        if (appsOverlay != null) centreGrid(columns)
        rest.chunked(columns).forEach { chunk ->
            val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            chunk.forEach { row.addView(appTile(it, inDock = false)) }
            appsGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(if (look.gridTile.caption) Space.L else Space.XL) })
        }
        restoreFocus()
    }

    private fun gridColumns(): Int {
        val style = look.gridTile
        val availableDp = resources.displayMetrics.widthPixels / density - 2 * look.gridSidePaddingDp
        return max(1, ((availableDp + style.gapDp) / (style.widthDp + style.gapDp)).toInt())
    }

    /** Cinema's All Apps sheet: centre the columns so the grid sits evenly on the screen. */
    private fun centreGrid(columns: Int) {
        val style = look.gridTile
        val screenDp = resources.displayMetrics.widthPixels / density
        val sideDp = max(look.gridSidePaddingDp.toFloat(), (screenDp - columns * (style.widthDp + style.gapDp)) / 2f)
        val side = (sideDp * density).toInt()
        appsGrid.setPadding(side, appsGrid.paddingTop, side, appsGrid.paddingBottom)
        (appsLabel?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = side + dp(style.gapDp) / 2
            appsLabel?.layoutParams = it
        }
    }

    private fun appTile(app: AppEntry, inDock: Boolean): TileView {
        val style = if (inDock) look.dockTile else look.gridTile
        return TileView(this, style).apply {
            key = "app:${app.pkg}"
            title = app.label
            subtitle = if (inDock) "In your Dock" else "App"
            if (app.banner != null) setBanner(app.banner) else setIcon(app.icon, app.label, app.tint)
            setOnClickListener { if (movingPkg == null) launch(app.intent) }
            setOnLongClickListener { showAppOptions(app, inDock); true }
            onFocused = this@HomeActivity::showHero
            layoutParams = tileParams(style)
        }
    }

    /** Even spacing either side, so a centred dock stays visually centred. */
    private fun tileParams(style: TileStyle) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (look.id == ThemeId.CINEMA) {
                marginStart = dp(style.gapDp) / 2
                marginEnd = dp(style.gapDp) / 2
            } else {
                marginEnd = dp(style.gapDp)
            }
        }

    private fun isAppsOpen() = appsOverlay?.visibility == View.VISIBLE

    private fun openApps() {
        val overlay = appsOverlay ?: return
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        // Once the sheet is opaque, stop drawing home underneath (saves GPU work on this TV).
        overlay.animate().alpha(1f).setDuration(200).withEndAction { setHomeLayersVisible(false) }.start()
        lastFocusKey = null
        restoreFocus()
    }

    private fun closeApps(restore: Boolean) {
        val overlay = appsOverlay ?: return
        overlay.animate().cancel()
        setHomeLayersVisible(true)
        overlay.visibility = View.GONE
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (restore) {
            lastFocusKey = "sys:apps"
            restoreFocus()
        }
    }

    private fun setHomeLayersVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        content.visibility = v
        wallpaperView.visibility = v
        scrim?.visibility = v
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Ink.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.CAPTION)
        setPadding(dp(Space.L), dp(Space.XXL), dp(Space.L), dp(Space.XXL))
    }

    private fun showHero(item: FocusItem) {
        lastFocusKey = item.key
        if (isAppsOpen()) return // All Apps tiles carry their own captions
        if (look.controlsAsChips && item is ChipView) {
            // Controls carry their own labels; the title above the dock is for apps only.
            heroTitle.animate().cancel()
            heroTitle.animate().alpha(0f).setDuration(160).setInterpolator(EASE).start()
            return
        }
        if (look.id == ThemeId.CINEMA) {
            heroSubtitle.text = if (movingPkg != null) MOVE_HINT else ""
            heroSubtitle.visibility = if (movingPkg != null) View.VISIBLE else View.GONE
            heroSubtitle.alpha = 0.8f
            (item as? View)?.let { glideTitle(item.title, it) }
            return
        }
        if (heroTitle.alpha < 1f && heroTitle.text == item.title) heroTitle.animate().alpha(1f).setDuration(200).start()
        val subtitle = when {
            movingPkg != null -> MOVE_HINT
            look.showHeroSubtitle -> item.subtitle
            else -> ""
        }
        heroSubtitle.visibility = if (subtitle.isEmpty() && !look.showHeroSubtitle) View.GONE else View.VISIBLE
        if (heroTitle.text == item.title) { heroSubtitle.text = subtitle; return }
        heroTitle.animate().cancel()
        heroSubtitle.animate().cancel()
        heroTitle.text = item.title
        heroSubtitle.text = subtitle
        heroTitle.alpha = 0f
        heroSubtitle.alpha = 0f
        heroTitle.translationX = -10 * density
        heroTitle.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(EASE).start()
        heroSubtitle.animate().alpha(0.65f).setDuration(300).start()
    }

    /**
     * Cinema: the focused app's name sits just above its tile and glides along the dock with the
     * focus (the old name fades as it starts to move, the new one settles in), clamped to the glass.
     */
    private fun glideTitle(title: String, anchor: View) {
        if (!anchor.isLaidOut || frosted.width == 0) {
            anchor.post { if (anchor.isFocused) glideTitle(title, anchor) } // first focus lands before layout
            return
        }
        val target = titleOffset(anchor, title)
        heroTitle.animate().cancel()
        when {
            heroTitle.text == title ->
                heroTitle.animate().alpha(1f).translationX(target).translationY(0f).setDuration(200).setInterpolator(EASE).start()
            heroTitle.alpha < 0.05f || !Motion.enabled -> {
                heroTitle.text = title
                heroTitle.translationX = target
                heroTitle.translationY = if (Motion.enabled) 6 * density else 0f
                heroTitle.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(EASE).start()
            }
            else -> {
                val mid = heroTitle.translationX + (target - heroTitle.translationX) * 0.4f
                heroTitle.animate().alpha(0f).translationX(mid).translationY(0f).setDuration(90).setInterpolator(EASE)
                    .withEndAction {
                        heroTitle.text = title
                        heroTitle.animate().alpha(1f).translationX(target).setDuration(190).setInterpolator(EASE).start()
                    }.start()
            }
        }
    }

    private fun titleOffset(anchor: View, title: String): Float {
        val parent = heroTitle.parent as? View ?: return 0f
        anchor.getLocationOnScreen(location)
        val anchorCenter = location[0] + anchor.width / 2f
        parent.getLocationOnScreen(location)
        val parentCenter = location[0] + parent.width / 2f
        val half = max(0f, (frosted.width - heroTitle.paint.measureText(title)) / 2f - dp(Space.L))
        return (anchorCenter - parentCenter).coerceIn(-half, half)
    }

    private fun showAppOptions(app: AppEntry, inDock: Boolean) {
        val items = mutableListOf<Pair<String, () -> Unit>>("Open" to { launch(app.intent) })
        if (inDock) {
            items += "Move" to { startMove(app.pkg) }
            items += "Remove from Dock" to { prefs.dock = prefs.dock - app.pkg; rebuildApps() }
        } else {
            items += "Add to Dock" to { prefs.dock = prefs.dock + app.pkg; lastFocusKey = null; rebuildApps() }
            items += "Hide" to { prefs.hidden = prefs.hidden + app.pkg; lastFocusKey = null; rebuildApps() }
        }
        items += "App info" to {
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        options.show(app.label, items, MenuArt(app.banner ?: app.icon, isBanner = app.banner != null, tint = app.tint))
    }

    private fun startMove(pkg: String) {
        val tile = dockRow.findViewWithTag<TileView>("app:$pkg") ?: return
        movingPkg = pkg
        tile.requestFocus()
        tile.setMoving(true)
        heroSubtitle.visibility = View.VISIBLE
        heroSubtitle.text = MOVE_HINT
    }

    private fun moveDock(direction: Int) {
        val pkg = movingPkg ?: return
        val tile = dockRow.findViewWithTag<TileView>("app:$pkg") ?: return
        val from = dockRow.indexOfChild(tile)
        val to = from + direction
        if (to !in 0 until dockRow.childCount) return
        dockRow.removeViewAt(from)
        dockRow.addView(tile, to)
        tile.requestFocus()
        tile.setMoving(true)
        // Persist the visible order; keep pins for apps that are currently uninstalled at the end.
        val visible = (0 until dockRow.childCount).mapNotNull { (dockRow.getChildAt(it) as? TileView)?.key?.removePrefix("app:") }
        prefs.dock = visible + (prefs.dock - visible.toSet())
        seenStamp = prefs.stamp
    }

    private fun endMove() {
        movingPkg?.let { dockRow.findViewWithTag<TileView>("app:$it")?.setMoving(false) }
        movingPkg = null
        val focused = currentFocus as? FocusItem
        heroSubtitle.text = if (look.showHeroSubtitle) focused?.subtitle.orEmpty() else ""
        if (!look.showHeroSubtitle) heroSubtitle.visibility = View.GONE
    }

    private fun restoreFocus() {
        if (options.isShowing) return
        if (isAppsOpen()) {
            val target = lastFocusKey?.let { appsGrid.findViewWithTag<View>(it) }
                ?: (appsGrid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
            target?.requestFocus()
            return
        }
        val target = lastFocusKey?.let { content.findViewWithTag<View>(it) }
            ?: dockRow.getChildAt(0)?.takeIf { it.isFocusable }
            ?: systemRow.getChildAt(0)
        target?.requestFocus()
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val MOVE_HINT = "Use ◀ ▶ to move · press OK when done"
        private const val APPS_SHADE = 0xC9060607.toInt()
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        private const val AMAZON_SETTINGS_ACTION = "amazon.intent.action.MAINSETTINGS"
        private const val AMAZON_SETTINGS_CATEGORY = "android.intent.category.MAINSETTINGS"
    }
}
