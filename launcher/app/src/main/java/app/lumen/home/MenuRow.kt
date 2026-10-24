package app.lumen.home

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One row of a menu (the app options dialog, Lumen settings): a label and an optional value on the
 * right. Cinema focuses rows the way tvOS does, as a solid silver-white pill with dark text; Classic
 * keeps the original translucent highlight with white text.
 */
class MenuRow(context: Context, private val cinema: Boolean) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val label = TextView(context)
    private val value = TextView(context)
    private val pill = GradientDrawable().apply {
        cornerRadius = (if (cinema) 16 else 12) * density
        setColor(REST)
    }
    private var colorAnimator: ValueAnimator? = null
    private var fill = REST
    private var ink = Ink.TEXT

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        background = pill
        minimumHeight = dp(if (cinema) 54 else 48)
        setPadding(dp(if (cinema) Space.XL else 14), dp(Space.S), dp(if (cinema) Space.XL else 14), dp(Space.S))
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (cinema) Type.BODY else 18f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        value.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (cinema) Type.BODY else 18f)
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.END
        }
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Space.XL) })
        applyInk(Ink.TEXT)
    }

    fun set(text: String, detail: String? = null, emphasis: Boolean = false): MenuRow {
        label.text = text
        label.typeface = Typeface.create(if (emphasis) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        value.text = detail.orEmpty()
        value.visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE
        return this
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (cinema) {
            animate().cancel()
            animate().scaleX(if (gainFocus) 1.02f else 1f).scaleY(if (gainFocus) 1.02f else 1f)
                .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        }
        val toFill = if (!gainFocus) REST else if (cinema) Ink.CHIP_FOCUS else CLASSIC_FOCUS
        val toInk = if (gainFocus && cinema) Ink.TEXT_ON_LIGHT else Ink.TEXT
        colorAnimator?.cancel()
        val fromFill = fill
        val fromInk = ink
        val argb = ArgbEvaluator()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            addUpdateListener {
                val t = it.animatedFraction
                fill = argb.evaluate(t, fromFill, toFill) as Int
                pill.setColor(fill)
                applyInk(argb.evaluate(t, fromInk, toInk) as Int)
            }
            start()
        }
    }

    private fun applyInk(color: Int) {
        ink = color
        label.setTextColor(color)
        // The value reads as secondary at rest and full strength on the focused pill.
        value.setTextColor(if (color == Ink.TEXT) Ink.TEXT_SECONDARY else color)
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val REST = 0x00FFFFFF
        const val CLASSIC_FOCUS = 0x38FFFFFF
    }
}
