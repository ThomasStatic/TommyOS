package app.lumen.home

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Artwork shown at the top of the dialog: an app banner, or its icon on a tinted card. */
class MenuArt(val drawable: Drawable, val isBanner: Boolean, val tint: Int)

/**
 * Menu of actions, opened with the remote's Menu button. Cinema shows it as a centred floating
 * dialog over a dimmed backdrop; Classic as the original right-hand sheet. Traps focus while open
 * and gives it back to whatever had it when closed.
 */
class OptionsPanel(private val root: FrameLayout, private val centred: Boolean) {
    private var overlay: View? = null
    private var previousFocus: View? = null
    private val blocked = mutableListOf<Pair<ViewGroup, Int>>()
    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = overlay != null

    fun show(title: String, items: List<Pair<String, () -> Unit>>, art: MenuArt? = null) {
        dismiss()
        previousFocus = root.findFocus()
        val card = if (centred) dialogCard(title, art) else sheetCard(title)
        val rows = items.map { (label, action) ->
            MenuRow(root.context, cinema = centred).set(label).apply {
                setOnClickListener { dismiss(); action() }
                card.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(if (centred) Space.XS else 0) })
            }
        }

        val scrim = FrameLayout(root.context).apply {
            setBackgroundColor(if (centred) Ink.BACKDROP else 0x99000000.toInt())
            isClickable = true
            clipChildren = false
            val params = if (centred) {
                FrameLayout.LayoutParams(dp(DIALOG_WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            } else {
                FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = dp(48) }
            }
            addView(card, params)
        }
        for (i in 0 until root.childCount) {
            val layer = root.getChildAt(i) as? ViewGroup ?: continue
            blocked += layer to layer.descendantFocusability
            layer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = scrim

        card.alpha = 0f
        if (centred) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(160).start()
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        } else {
            card.translationX = 40 * density
            card.animate().alpha(1f).translationX(0f).setDuration(220).start()
        }
        rows.firstOrNull()?.requestFocus()
    }

    fun dismiss() {
        val ov = overlay ?: return
        overlay = null
        blocked.forEach { (layer, mode) -> layer.descendantFocusability = mode }
        blocked.clear()
        previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        previousFocus = null
        if (centred && Motion.enabled) {
            // Focus is already back on the home screen; the dialog just fades away.
            ov.isClickable = false
            ov.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(ov) }.start()
        } else {
            root.removeView(ov)
        }
    }

    private fun dialogCard(title: String, art: MenuArt?) = LinearLayout(root.context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 30 * density
            setColor(Ink.DIALOG)
            setStroke(density.toInt().coerceAtLeast(1), Ink.DIALOG_STROKE)
        }
        elevation = 24 * density
        setPadding(dp(Space.XL), dp(Space.XL), dp(Space.XL), dp(Space.XL))
        clipChildren = false
        clipToPadding = false

        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Space.S), 0, dp(Space.S), dp(Space.L))
        }
        if (art != null) header.addView(artView(art), LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(Space.XL) })
        header.addView(TextView(context).apply {
            text = title
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.HEADLINE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(header)
    }

    private fun artView(art: MenuArt) = ImageView(root.context).apply {
        // A fresh copy: the original drawable belongs to the tile on screen.
        setImageDrawable(art.drawable.constantState?.newDrawable(resources)?.mutate() ?: art.drawable)
        background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(if (art.isBanner) Color.BLACK else art.tint)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        if (art.isBanner) {
            scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(Space.L)
            setPadding(pad, pad, pad, pad)
        }
    }

    private fun sheetCard(title: String) = LinearLayout(root.context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 24 * density; setColor(0xF2161618.toInt()) }
        setPadding(dp(16), dp(20), dp(16), dp(16))
        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, dp(12), dp(12))
        })
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val DIALOG_WIDTH_DP = 460

        /** Soft white pill behind the focused row (Classic settings screen). */
        fun focusBackground(density: Float) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply { cornerRadius = 12 * density; setColor(0x38FFFFFF) })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }
}
