package app.lumen.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Anything on the home screen that can take focus and name itself in the hero title. */
interface FocusItem {
    val key: String
    val title: String
    val subtitle: String
}

/**
 * A focusable tile: a rounded card (app banner, app icon, or glyph) that lifts on focus.
 * How it looks and moves comes from its [TileStyle]: Cinema scales, rises and lifts with a soft white
 * glow and a diagonal sheen while the rest of the row dims; Classic adds a white outline and a caption.
 */
class TileView(context: Context, private val style: TileStyle) : LinearLayout(context), FocusItem {
    /** Stable identity, also stored as the view tag so focus can be restored after rebuilds. */
    override var key: String = ""
        set(value) { field = value; tag = value }
    override var title: String = ""
    override var subtitle: String = ""
    var onFocused: ((FocusItem) -> Unit)? = null

    private val density = resources.displayMetrics.density
    // Children never overlap in a way that matters, so let alpha apply per-draw instead of
    // forcing an offscreen buffer for every dimmed tile (that cost ~tens of MB of graphics memory).
    private val card = object : FrameLayout(context) {
        override fun hasOverlappingRendering() = false
    }
    private val image = ImageView(context)
    private val inner = LinearLayout(context)
    private val innerIcon = ImageView(context)
    private val innerLabel = TextView(context)
    private val caption = TextView(context)
    private val cardBg = GradientDrawable().apply { cornerRadius = style.cornerDp * density; setColor(0x33FFFFFF) }
    private val ring = GradientDrawable().apply {
        cornerRadius = style.cornerDp * density
        setStroke((2 * density).toInt(), Color.WHITE)
        alpha = 0
    }
    // Light falling across the focused card from the top left; faded in with the focus.
    private val sheen = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x38FFFFFF, 0x0AFFFFFF, 0x00FFFFFF)).apply {
        cornerRadius = style.cornerDp * density
        alpha = 0
    }
    private var ringAnimator: ValueAnimator? = null
    private var sheenAnimator: ValueAnimator? = null
    private var moving = false

    init {
        orientation = VERTICAL
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false

        card.background = cardBg
        card.foreground = when {
            style.sheen -> LayerDrawable(arrayOf(sheen, ring))
            style.ring -> ring
            else -> null
        }
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.alpha = style.restAlpha
        if (style.glow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // A white shadow under the lifted card reads as a soft glow; drawn by the GPU, no blur pass.
            card.outlineAmbientShadowColor = Ink.GLOW
            card.outlineSpotShadowColor = Ink.GLOW
        }

        image.scaleType = ImageView.ScaleType.CENTER_CROP
        card.addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        inner.orientation = VERTICAL
        inner.gravity = Gravity.CENTER
        inner.addView(innerIcon, LayoutParams(dp(36), dp(36)))
        innerLabel.apply {
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL - 2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(Space.S), dp(6), dp(Space.S), 0)
        }
        inner.addView(innerLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        inner.visibility = GONE
        card.addView(inner, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(dp(style.widthDp), dp(style.heightDp)))

        caption.apply {
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            alpha = 0f
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
        }
        if (style.caption) {
            addView(caption, LayoutParams(dp(style.widthDp), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Space.M) })
        }
    }

    /** Full-bleed artwork (app banners, wallpaper thumbnails). Set [title] first: it becomes the caption. */
    fun setBanner(drawable: Drawable) {
        image.setImageDrawable(drawable)
        image.visibility = VISIBLE
        inner.visibility = GONE
        cardBg.setColor(Color.BLACK)
        caption.text = title
    }

    /** App without a TV banner: its icon and name on a card tinted from the icon. */
    fun setIcon(drawable: Drawable, label: String, tint: Int) {
        showInner(label)
        innerIcon.setImageDrawable(drawable)
        innerIcon.layoutParams = LayoutParams(dp(40), dp(40))
        cardBg.setColor(tint)
    }

    /** System tiles (inputs, settings): a white glyph on glass. */
    fun setGlyph(resId: Int, label: String) {
        showInner(label)
        innerIcon.setImageResource(resId)
        innerIcon.layoutParams = LayoutParams(dp(30), dp(30))
        cardBg.setColor(0x2EFFFFFF)
    }

    fun setMoving(isMoving: Boolean) {
        moving = isMoving
        animateFocus(isFocused)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocus(gainFocus)
        if (gainFocus) onFocused?.invoke(this)
    }

    private fun showInner(label: String) {
        image.visibility = GONE
        inner.visibility = VISIBLE
        innerLabel.text = label
        caption.text = ""
    }

    private fun animateFocus(focused: Boolean) {
        val scale = if (!focused) 1f else if (moving) style.moveScale else style.focusScale
        card.animate().cancel()
        val rise = if (focused && Motion.enabled) -style.riseDp * density else 0f
        card.animate()
            .scaleX(scale).scaleY(scale)
            .translationY(rise)
            .translationZ(if (focused) style.focusLiftDp * density else 0f)
            .alpha(if (focused) 1f else style.restAlpha)
            .setDuration(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS)
            .setInterpolator(if (focused) EASE_OUT else DecelerateInterpolator())
            .start()

        if (style.sheen) {
            sheenAnimator?.cancel()
            sheenAnimator = ValueAnimator.ofInt(sheen.alpha, if (focused) 255 else 0).apply {
                duration = if (focused) FOCUS_IN_MS else FOCUS_OUT_MS
                addUpdateListener { sheen.alpha = it.animatedValue as Int }
                start()
            }
        }

        if (style.ring || moving) {
            if (card.foreground == null) card.foreground = ring
            ringAnimator?.cancel()
            val targetRing = if (!focused) 0 else if (moving) 255 else 140
            ringAnimator = ValueAnimator.ofInt(ring.alpha, targetRing).apply {
                duration = 200
                addUpdateListener { ring.alpha = it.animatedValue as Int }
                start()
            }
        }

        if (style.caption) {
            caption.animate().cancel()
            caption.animate()
                .alpha(if (focused) 1f else 0f)
                .translationY(if (focused) 6 * density else 0f)
                .setDuration(200)
                .start()
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val FOCUS_IN_MS = 240L
        private const val FOCUS_OUT_MS = 180L
        /** Gentle ease-out with a hint of settle; no bouncy overshoot. */
        private val EASE_OUT = PathInterpolator(0.2f, 0.9f, 0.25f, 1.02f)
    }
}
