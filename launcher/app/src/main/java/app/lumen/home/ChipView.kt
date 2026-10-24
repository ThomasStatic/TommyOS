package app.lumen.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A secondary control (input, Settings, Apps, Lumen) as a small translucent pill. On focus it
 * turns into a solid silver-white pill with dark text, the way tvOS highlights buttons.
 */
class ChipView(context: Context) : LinearLayout(context), FocusItem {
    override var key: String = ""
        set(value) { field = value; tag = value }
    override var title: String = ""
    override var subtitle: String = ""
    var onFocused: ((FocusItem) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val pill = GradientDrawable().apply {
        cornerRadius = HEIGHT_DP * density / 2
        setColor(Ink.CHIP_REST)
        setStroke(density.toInt().coerceAtLeast(1), Ink.CHIP_STROKE)
    }
    private var colorAnimator: ValueAnimator? = null
    private var fill = Ink.CHIP_REST
    private var ink = Ink.TEXT

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        background = pill
        minimumHeight = dp(HEIGHT_DP)
        setPadding(dp(Space.XL - 2), 0, dp(Space.XL), 0)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(icon, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(Space.M - 2) })
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            includeFontPadding = false
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyInk(Ink.TEXT)
    }

    fun set(iconRes: Int, text: String) {
        icon.setImageResource(iconRes)
        label.text = text
        applyInk(ink)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animate().cancel()
        animate().scaleX(if (gainFocus) FOCUS_SCALE else 1f).scaleY(if (gainFocus) FOCUS_SCALE else 1f)
            .translationY(if (gainFocus && Motion.enabled) -2 * density else 0f)
            .translationZ(if (gainFocus) 10 * density else 0f)
            .setDuration(200).setInterpolator(DecelerateInterpolator()).start()

        colorAnimator?.cancel()
        val fromFill = fill
        val fromInk = ink
        val toFill = if (gainFocus) Ink.CHIP_FOCUS else Ink.CHIP_REST
        val toInk = if (gainFocus) Ink.TEXT_ON_LIGHT else Ink.TEXT
        val argb = ArgbEvaluator()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val t = it.animatedFraction
                fill = argb.evaluate(t, fromFill, toFill) as Int
                pill.setColor(fill)
                applyInk(argb.evaluate(t, fromInk, toInk) as Int)
            }
            start()
        }
        if (gainFocus) onFocused?.invoke(this)
    }

    private fun applyInk(color: Int) {
        ink = color
        label.setTextColor(color)
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val HEIGHT_DP = 48
        const val FOCUS_SCALE = 1.08f
    }
}
