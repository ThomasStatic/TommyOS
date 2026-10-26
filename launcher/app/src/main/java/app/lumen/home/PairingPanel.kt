package app.lumen.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The phone pairing screen over Lumen settings: the QR code Lumen Sports made for its pairing link,
 * and how to use it. The link (with its key) exists only in this view and the QR code; nothing here
 * logs or stores it. Back or OK on Done closes it.
 */
class PairingPanel(private val root: FrameLayout, private val cinema: Boolean, private val onClose: () -> Unit) {
    private val context: Context = root.context
    private val density = context.resources.displayMetrics.density
    private var shown: View? = null

    val isShowing get() = shown != null

    /** [qr]: rows of '1' (dark) and '0' from Lumen Sports; [address]: the page's address, for reading out. */
    fun show(qr: String, address: String, renewed: Boolean) {
        dismiss()
        val backdrop = FrameLayout(context).apply {
            setBackgroundColor(Ink.BACKDROP)
            isClickable = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(if (cinema) 28 else 16).toFloat()
                setColor(Ink.DIALOG)
                setStroke(dp(1), Ink.DIALOG_STROKE)
            }
            setPadding(dp(Space.XXXL), dp(Space.XXXL), dp(Space.XXXL), dp(Space.XXXL))
        }
        card.addView(QrView(context, qr), LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)))

        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        side.addView(text("Pair your phone", Type.HEADLINE, Ink.TEXT, medium = true))
        if (renewed) side.addView(text("New code: phones paired before must scan this one.", Type.CAPTION, Ink.TEXT_SECONDARY).apply { setPadding(0, dp(Space.S), 0, 0) })
        for ((i, step) in STEPS.withIndex()) {
            side.addView(text("${i + 1}.  $step", Type.BODY, Ink.TEXT_SECONDARY).apply { setPadding(0, dp(if (i == 0) Space.XL else Space.M), 0, 0) })
        }
        side.addView(text("The page lives on this TV at $address. It can show, hide and move the ticker; nothing about your league leaves the TV.",
            Type.CAPTION, Ink.TEXT_TERTIARY).apply { setPadding(0, dp(Space.XL), 0, 0) })
        val done = MenuRow(context, cinema).set("Done", emphasis = true).apply {
            setOnClickListener { dismiss() }
            setOnKeyListener { _, code, event ->
                // Keep focus here: the settings list underneath must not take it while the code is up.
                event.action == KeyEvent.ACTION_DOWN && code in TRAPPED
            }
        }
        side.addView(done, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Space.XL) })
        card.addView(side, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Space.XXXL) })

        backdrop.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shown = backdrop
        done.requestFocus()
    }

    fun dismiss() {
        val v = shown ?: return
        shown = null
        root.removeView(v)
        onClose()
    }

    private fun text(value: String, sp: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** Dark modules on white with a quiet zone, scaled to whole pixels per module so it stays sharp. */
    private class QrView(context: Context, qr: String) : View(context) {
        private val rows = qr.split('\n').filter { it.isNotEmpty() }
        private val paint = Paint().apply { color = 0xFF000000.toInt() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        override fun onDraw(canvas: Canvas) {
            val n = rows.size
            if (n == 0) return
            val module = minOf(width, height) / (n + 2 * QUIET)
            val side = module * (n + 2 * QUIET)
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            canvas.drawRoundRect(left, top, left + side, top + side, module.toFloat(), module.toFloat(), white)
            for (y in 0 until n) for (x in rows[y].indices) {
                if (rows[y][x] != '1') continue
                val px = left + (x + QUIET) * module
                val py = top + (y + QUIET) * module
                canvas.drawRect(px, py, px + module, py + module, paint)
            }
        }
    }

    private companion object {
        const val QR_DP = 300
        const val QUIET = 4
        val STEPS = listOf(
            "Open the Camera on your iPhone and point it at the code.",
            "Tap the link that appears.",
            "In Safari, tap Share, then Add to Home Screen.",
        )
        val TRAPPED = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
