package app.lumen.home

/**
 * Lumen's design system. Every visual decision that differs between looks lives here as a token,
 * so screens read the active [LumenTheme] instead of hard-coding sizes and colours.
 *
 * - [Themes.CINEMA]: tvOS-inspired. Cinematic scene wallpapers, a floating smoked-glass dock, a lifted
 *   focus with a soft glow and sheen, secondary controls as pills, centred dialogs. The default.
 * - [Themes.CLASSIC]: the original Lumen layout, kept as a switchable fallback.
 */
enum class ThemeId { CINEMA, CLASSIC }

/** Colour tokens (ARGB). Neutral by design: near-black, graphite, silver, white. */
object Ink {
    const val TEXT = 0xFFF5F5F7.toInt()
    const val TEXT_SECONDARY = 0xB3F5F5F7.toInt()
    const val TEXT_TERTIARY = 0x80F5F5F7.toInt()
    const val TEXT_ON_LIGHT = 0xFF141416.toInt()
    const val GRAPHITE = 0xFF1C1C1F.toInt()
    const val NEAR_BLACK = 0xF2050506.toInt()
    const val CHIP_REST = 0x1FFFFFFF
    const val CHIP_FOCUS = 0xF2F2F2F4.toInt()
    const val CHIP_STROKE = 0x24FFFFFF
    const val GLOW = 0xFFFFFFFF.toInt()
    const val DIALOG = 0xF21C1C20.toInt()
    const val DIALOG_STROKE = 0x1FFFFFFF
    const val BACKDROP = 0xB3000000.toInt()
}

/**
 * Type scale in sp, sized for a 40" 1080p screen viewed from a couch (1 sp = 2 px on this TV).
 * Layout XML uses the same values; keep them in step.
 */
object Type {
    const val CLOCK = 88f
    const val TITLE = 34f      // screen titles: Apps, Lumen
    const val HEADLINE = 28f   // dialog titles, the focused app's name above the dock
    const val BODY = 20f       // menu rows, the date
    const val LABEL = 17f      // control pills, tile captions
    const val CAPTION = 16f    // notes and secondary lines
    const val OVERLINE = 15f   // section headers (upper case, tracked)
}

/** Motion policy: honours the system animation scale ("Remove animations" sets it to 0). */
object Motion {
    val enabled: Boolean
        get() = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
}

/** Spacing scale in dp. Use these steps only, so rhythm stays consistent. */
object Space {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    const val XXXL = 48
    const val HUGE = 64
}

data class TileStyle(
    val widthDp: Int,
    val heightDp: Int,
    val cornerDp: Int,
    val gapDp: Int,
    val focusScale: Float,
    val moveScale: Float,
    val restAlpha: Float,
    /** Elevation while focused; with a white shadow colour this reads as a soft glow. */
    val focusLiftDp: Int,
    val glow: Boolean,
    /** Classic white outline on focus. */
    val ring: Boolean,
    /** Name under the tile on focus (Cinema shows names above the dock instead). */
    val caption: Boolean,
    /** Upward drift on focus, on top of the scale (dp). */
    val riseDp: Int = 0,
    /** Soft diagonal light across the focused card. */
    val sheen: Boolean = false,
)

data class GlassStyle(
    val radiusDp: Int,
    val darken: Int,
    /** Light wash over the glass, top to bottom (equal values = flat). */
    val sheenTop: Int,
    val sheenBottom: Int,
    /** Edge line, top to bottom: brighter on top reads as light catching the rim. */
    val hairlineTop: Int,
    val hairlineBottom: Int,
    val liftDp: Int,
)

data class LumenTheme(
    val id: ThemeId,
    val styleRes: Int,
    val layoutRes: Int,
    val dockTile: TileStyle,
    val gridTile: TileStyle,
    val glass: GlassStyle,
    /** Inputs/Settings/Lumen as small secondary pills (Cinema) or full tiles (Classic). */
    val controlsAsChips: Boolean,
    /** Menus as a centred floating dialog with white focus pills (Cinema) or a right-hand sheet (Classic). */
    val centredMenus: Boolean,
    val showHeroSubtitle: Boolean,
    val gridSidePaddingDp: Int,
    val defaultWallpaper: String,
)

object Themes {
    val CINEMA = LumenTheme(
        id = ThemeId.CINEMA,
        styleRes = R.style.Theme_Lumen_Cinema,
        layoutRes = R.layout.activity_home_cinema,
        dockTile = TileStyle(
            widthDp = 160, heightDp = 90, cornerDp = 14, gapDp = 22,
            focusScale = 1.12f, moveScale = 1.16f, restAlpha = 0.76f,
            focusLiftDp = 16, glow = true, ring = false, caption = false, riseDp = 5, sheen = true,
        ),
        gridTile = TileStyle(
            widthDp = 152, heightDp = 86, cornerDp = 14, gapDp = 24,
            focusScale = 1.10f, moveScale = 1.10f, restAlpha = 0.78f,
            focusLiftDp = 16, glow = true, ring = false, caption = true, riseDp = 3, sheen = true,
        ),
        glass = GlassStyle(
            radiusDp = 32, darken = 0x6B000000, sheenTop = 0x1CFFFFFF, sheenBottom = 0x00FFFFFF,
            hairlineTop = 0x52FFFFFF, hairlineBottom = 0x0AFFFFFF, liftDp = 12,
        ),
        controlsAsChips = true,
        centredMenus = true,
        showHeroSubtitle = false,
        gridSidePaddingDp = Space.XXXL,
        defaultWallpaper = "scene:blue-hour",
    )

    val CLASSIC = LumenTheme(
        id = ThemeId.CLASSIC,
        styleRes = R.style.Theme_Lumen,
        layoutRes = R.layout.activity_home,
        dockTile = TileStyle(
            widthDp = 152, heightDp = 86, cornerDp = 14, gapDp = 20,
            focusScale = 1.12f, moveScale = 1.18f, restAlpha = 0.84f,
            focusLiftDp = 18, glow = false, ring = true, caption = true,
        ),
        gridTile = TileStyle(
            widthDp = 152, heightDp = 86, cornerDp = 14, gapDp = 20,
            focusScale = 1.12f, moveScale = 1.18f, restAlpha = 0.84f,
            focusLiftDp = 18, glow = false, ring = true, caption = true,
        ),
        glass = GlassStyle(
            radiusDp = 28, darken = 0x38000000, sheenTop = 0x17FFFFFF, sheenBottom = 0x17FFFFFF,
            hairlineTop = 0x2EFFFFFF, hairlineBottom = 0x2EFFFFFF, liftDp = 0,
        ),
        controlsAsChips = false,
        centredMenus = false,
        showHeroSubtitle = true,
        gridSidePaddingDp = Space.XXXL,
        defaultWallpaper = "builtin:0", // Midnight
    )

    fun of(id: ThemeId) = if (id == ThemeId.CLASSIC) CLASSIC else CINEMA

    /** Cinema's default before scene wallpapers existed (Obsidian); migrated once to the new default. */
    const val OLD_CINEMA_DEFAULT = "builtin:5"
}
