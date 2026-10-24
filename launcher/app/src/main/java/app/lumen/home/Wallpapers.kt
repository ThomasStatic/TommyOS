package app.lumen.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.File
import kotlin.math.max

/**
 * Three kinds of wallpaper, keyed by prefix:
 * - "scene:<name>": cinematic images in assets/scenes (rendered offline by tools/scenes.py, 1280x720).
 * - "builtin:<index>": gradients drawn procedurally (soft glows on a near-black base).
 * - "file:<name>": the user's own images in the app's external files dir, added with
 *   adb push photo.jpg /sdcard/Android/data/app.lumen.home/files/wallpapers/
 */
object Wallpapers {
    private class Glow(val x: Float, val y: Float, val radius: Float, val color: Int)
    private class Palette(val name: String, val base: Int, val glows: List<Glow>)

    private val BUILTIN = listOf(
        Palette("Midnight", 0xFF05070D.toInt(), listOf(
            Glow(0.78f, 0.18f, 0.85f, 0xE61B2A55.toInt()),
            Glow(0.12f, 0.90f, 0.70f, 0xB32A1446.toInt()),
            Glow(0.45f, 0.55f, 0.45f, 0x590E3A4A.toInt()),
        )),
        Palette("Aurora", 0xFF03080A.toInt(), listOf(
            Glow(0.25f, 0.15f, 0.80f, 0xCC0F5C4F.toInt()),
            Glow(0.70f, 0.35f, 0.60f, 0x991D7A5A.toInt()),
            Glow(0.85f, 0.95f, 0.70f, 0xB33B1F66.toInt()),
        )),
        Palette("Ember", 0xFF0A0505.toInt(), listOf(
            Glow(0.80f, 0.85f, 0.80f, 0xCC5A1E0E.toInt()),
            Glow(0.60f, 0.30f, 0.50f, 0x807A3B12.toInt()),
            Glow(0.10f, 0.20f, 0.70f, 0x992B0F2E.toInt()),
        )),
        Palette("Graphite", 0xFF0B0B0D.toInt(), listOf(
            Glow(0.50f, 0.00f, 1.00f, 0x993A3D45.toInt()),
            Glow(0.10f, 1.00f, 0.60f, 0x662A2C33.toInt()),
            Glow(0.90f, 0.70f, 0.50f, 0x4D1A1B20.toInt()),
        )),
        Palette("Tide", 0xFF02060C.toInt(), listOf(
            Glow(0.20f, 0.80f, 0.90f, 0xCC0B3D6B.toInt()),
            Glow(0.75f, 0.25f, 0.60f, 0x990A5C7A.toInt()),
            Glow(0.95f, 0.90f, 0.50f, 0x80102040.toInt()),
        )),
        // Neutral, cinematic set for the Cinema theme (appended so saved "builtin:N" keys keep their meaning).
        Palette("Obsidian", 0xFF060607.toInt(), listOf(
            Glow(0.72f, 0.02f, 0.95f, 0x4DC9CDD6.toInt()),
            Glow(0.12f, 0.98f, 0.60f, 0x2E4A4E57.toInt()),
            Glow(0.98f, 0.70f, 0.45f, 0x1F9AA0AA.toInt()),
        )),
        Palette("Silver Mist", 0xFF0B0C0E.toInt(), listOf(
            Glow(0.50f, -0.05f, 1.10f, 0x66A9AEB8.toInt()),
            Glow(0.00f, 1.00f, 0.70f, 0x382C2F36.toInt()),
            Glow(1.00f, 1.00f, 0.60f, 0x2E5A5F68.toInt()),
        )),
        Palette("Eclipse", 0xFF030303.toInt(), listOf(
            Glow(0.62f, 0.36f, 0.50f, 0x40E3E4E8.toInt()),
            Glow(0.62f, 0.36f, 0.22f, 0x26FFFFFF),
            Glow(0.20f, 1.05f, 0.80f, 0x33303237.toInt()),
        )),
    )
    /** Asset name to display name, in presentation order. */
    private val SCENES = linkedMapOf(
        "blue-hour" to "Blue Hour",
        "ember-peaks" to "Ember Peaks",
        "still-lake" to "Still Lake",
        "moonlit-dunes" to "Moonlit Dunes",
        "aurora" to "Aurora",
        "silk" to "Silk",
    )
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_BUILTIN_WIDTH = 1280f
    private const val SCENE_WIDTH = 1280

    // Single-entry cache for the full-screen bitmap (the largest allocation in the app).
    private var cacheId: String? = null
    private var cache: Bitmap? = null

    const val COVER_FILE = "cover.jpg"

    /**
     * Saves a small, darkened copy of the wallpaper for the redirect service's cover, which runs in
     * another process. Soft at 480 px wide, which reads fine for the ~1 s it is visible.
     */
    fun writeCover(context: Context, wallpaper: Bitmap) {
        val w = 480
        val h = max(1, w * wallpaper.height / wallpaper.width)
        val out = Bitmap.createScaledBitmap(wallpaper, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        // Match the home screen's scrim so the hand-off to Lumen is seamless.
        Canvas(out).drawPaint(Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xA6000000.toInt(), 0x14000000, 0x73000000, 0xE6000000.toInt()),
                floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        val tmp = File(context.filesDir, "$COVER_FILE.tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(File(context.filesDir, COVER_FILE))
        out.recycle()
    }

    fun clearCache() {
        cache = null
        cacheId = null
    }

    fun userDir(context: Context): File? = context.getExternalFilesDir("wallpapers")?.also { it.mkdirs() }

    fun scenes(): List<String> = SCENES.keys.map { "scene:$it" }

    /** Neutral gradients first (they suit Cinema), then the original coloured set. */
    fun gradients(): List<String> = (listOf(5, 6, 7) + (0..4)).map { "builtin:$it" }

    fun photos(context: Context): List<String> = userDir(context)
        ?.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
        ?.sortedBy { it.name }
        ?.map { "file:${it.name}" }
        .orEmpty()

    fun label(key: String): String = when {
        key.startsWith("file:") -> key.substringAfter(':').substringBeforeLast('.')
        key.startsWith("scene:") -> SCENES[key.substringAfter(':')] ?: "Scene"
        else -> BUILTIN.getOrNull(key.substringAfter(':').toIntOrNull() ?: 0)?.name ?: "Wallpaper"
    }

    /**
     * The wallpaper at roughly [width] x [height]. Full-screen loads share a one-entry cache unless
     * [shared] is false, in which case the caller owns the bitmap (and may recycle it).
     */
    fun load(context: Context, key: String, width: Int, height: Int, shared: Boolean = true): Bitmap {
        val id = "$key@${width}x$height"
        if (shared && cacheId == id) cache?.let { return it }
        val bitmap = when {
            key.startsWith("file:") -> fromFile(context, key.substringAfter(':'), width, height)
            key.startsWith("scene:") -> scene(context, key.substringAfter(':'), width)
            else -> null
        } ?: builtin(key.substringAfter(':').toIntOrNull() ?: 0, width, height)
        if (shared && width >= 640) { cache = bitmap; cacheId = id }
        return bitmap
    }

    /** A tiny, heavily blurred copy; drawn scaled-up (with filtering) it reads as frosted glass. */
    fun blur(src: Bitmap): Bitmap {
        val w = max(1, src.width / 24)
        val h = max(1, src.height / 24)
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(src, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            boxPass(px, w, h, radius = 2, horizontal = true)
            boxPass(px, w, h, radius = 2, horizontal = false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun builtin(index: Int, requestedWidth: Int, requestedHeight: Int): Bitmap {
        val palette = BUILTIN[index.mod(BUILTIN.size)]
        // Soft glows look identical at 720p; the ImageView scales it up (≈3.7 MB instead of 8.3 MB).
        val shrink = max(1f, requestedWidth / MAX_BUILTIN_WIDTH)
        val width = (requestedWidth / shrink).toInt()
        val height = (requestedHeight / shrink).toInt()
        // Render small and scale up: gradients stay smooth and it costs a few milliseconds.
        val sw = max(1, width / 4)
        val sh = max(1, height / 4)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        canvas.drawColor(palette.base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val span = max(sw, sh).toFloat()
        for (g in palette.glows) {
            val clear = g.color and 0x00FFFFFF
            val half = ((g.color ushr 24) / 2 shl 24) or clear
            paint.shader = RadialGradient(
                g.x * sw, g.y * sh, g.radius * span,
                intArrayOf(g.color, half, clear), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), paint)
        }
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        if (scaled !== small) small.recycle()
        return scaled
    }

    /**
     * Scenes ship at 1280x720: soft, atmospheric images that hold up when the ImageView scales them to
     * 1080p, at 3.7 MB decoded instead of 8.3 MB. Thumbnails decode subsampled (a few hundred KB).
     */
    private fun scene(context: Context, name: String, width: Int): Bitmap? {
        if (name !in SCENES) return null
        var sample = 1
        while (SCENE_WIDTH / (sample * 2) >= width) sample *= 2
        return try {
            context.assets.open("scenes/$name.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun fromFile(context: Context, name: String, width: Int, height: Int): Bitmap? {
        val file = File(userDir(context) ?: return null, name)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Centre-crop to exactly the target size so it lines up with the frosted panel.
        val scale = max(width.toFloat() / src.width, height.toFloat() / src.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - src.width * scale) / 2f, (height - src.height * scale) / 2f)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun boxPass(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val line = IntArray(n)
        val d = 2 * radius + 1
        for (l in 0 until lines) {
            for (i in 0 until n) line[i] = px[if (horizontal) l * w + i else i * w + l]
            for (i in 0 until n) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val p = line[(i + k).coerceIn(0, n - 1)]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                px[if (horizontal) l * w + i else i * w + l] = ((a / d) shl 24) or ((r / d) shl 16) or ((g / d) shl 8) or (b / d)
            }
        }
    }
}
