package app.lumen.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
    val tint: Int,
)

data class InputEntry(val id: String, val label: String)

object Apps {
    private val PREFERRED_DOCK = listOf(
        "com.netflix.ninja",
        "com.amazon.firetv.youtube",
        "com.spotify.tv.android",
        "ca.tsn.mobile.android",
    )

    /** Every launchable app (TV-first entries preferred), sorted by name. Disabled packages never appear. */
    fun load(context: Context, tileWidthDp: Int, tileHeightDp: Int): List<AppEntry> {
        val pm = context.packageManager
        val density = context.resources.displayMetrics.density
        // Sized for the focused (scaled-up) tile so artwork stays crisp.
        val bannerW = (tileWidthDp * density * FOCUS_HEADROOM).toInt()
        val bannerH = (tileHeightDp * density * FOCUS_HEADROOM).toInt()
        val iconPx = (48 * density).toInt()
        val found = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg == context.packageName || pkg == HomeRedirectService.AMAZON_LAUNCHER || pkg in found) continue
                val intent = (if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) pm.getLeanbackLaunchIntentForPackage(pkg) else null)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val icon = ri.loadIcon(pm)
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                found[pkg] = AppEntry(
                    pkg = pkg,
                    label = ai.applicationInfo.loadLabel(pm).toString(),
                    intent = intent,
                    // Apps ship banners/icons at arbitrary sizes; keep only tile-sized copies.
                    banner = banner?.let { rasterize(context, it, bannerW, bannerH) },
                    icon = rasterize(context, icon, iconPx, iconPx),
                    tint = cardTint(icon),
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun defaultDock(apps: List<AppEntry>): List<String> {
        val present = apps.mapTo(HashSet()) { it.pkg }
        return PREFERRED_DOCK.filter { it in present }.ifEmpty { apps.take(5).map { it.pkg } }
    }

    /** Physical HDMI ports (not the per-device CEC children), labelled as Fire OS names them. */
    fun inputs(context: Context): List<InputEntry> {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val listed = tim?.tvInputList.orEmpty()
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(context) }
            .map { InputEntry(it.id, (it.loadCustomLabel(context) ?: it.loadLabel(context)).toString().replace(Regex("^HDMI(\\d)"), "HDMI $1")) }
            .sortedBy { it.label }
        // Fallback: the two hardware ports discovered on this TV (AFTCU864A2) during Phase 1.
        return listed.ifEmpty {
            listOf(
                InputEntry("com.droidlogic.tvinput/.services.Hdmi1InputService/HW5", "HDMI 1"),
                InputEntry("com.droidlogic.tvinput/.services.Hdmi2InputService/HW6", "HDMI 2"),
            )
        }
    }

    private const val FOCUS_HEADROOM = 1.2f

    private fun rasterize(context: Context, drawable: Drawable, width: Int, height: Int): Drawable {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bmp))
        return BitmapDrawable(context.resources, bmp)
    }

    /** A deep, desaturated card colour from an icon's average opaque colour. */
    private fun cardTint(icon: Drawable): Int {
        val size = 12
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until size) for (y in 0 until size) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) > 160) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ }
        }
        bmp.recycle()
        if (n == 0) return 0xFF2A2A30.toInt()
        return Color.rgb((r / n * 0.42).toInt(), (g / n * 0.42).toInt(), (b / n * 0.42).toInt())
    }
}
