package app.lumen.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Frosted-glass container. Wraps its content (the dock row) and draws glass behind it without
 * RenderEffect (API 31+; this TV is API 30): the pre-blurred wallpaper, aligned to where the panel
 * sits on screen, then the [GlassStyle] tint, a top-lit wash and a rim that catches the light at the top.
 * Gradients are built once per size; no per-frame blur.
 * Children are not clipped, so focused tiles can lift and glow past the glass edge.
 * Call invalidate() when the panel moves (e.g. on scroll).
 */
class FrostedPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var blurred: Bitmap? = null
        set(value) {
            field = value
            bitmapPaint.shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }

    var glass: GlassStyle = Themes.CLASSIC.glass
        set(value) {
            field = value
            radius = value.radiusDp * density
            darken.color = value.darken
            elevation = value.liftDp * density
            updateGradients()
            invalidateOutline()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var radius = glass.radiusDp * density
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val darken = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glass.darken }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val location = IntArray(2)
    private val shape = RectF()
    private val shaderMatrix = Matrix()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Outline only casts the soft shadow; it does not clip the content.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradients()
    }

    private fun updateGradients() {
        val h = height.coerceAtLeast(1).toFloat()
        val g = glass
        sheen.shader = LinearGradient(0f, 0f, 0f, h, g.sheenTop, g.sheenBottom, Shader.TileMode.CLAMP)
        // The rim fades out by mid-height, like light along the top edge of a pane of glass.
        hairline.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(g.hairlineTop, g.hairlineBottom, g.hairlineBottom),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        shape.set(0f, 0f, width.toFloat(), height.toFloat())
        blurred?.let {
            // The wallpaper fills the screen: map the small blurred copy onto screen coordinates.
            getLocationOnScreen(location)
            val dm = resources.displayMetrics
            shaderMatrix.setScale(dm.widthPixels.toFloat() / it.width, dm.heightPixels.toFloat() / it.height)
            shaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            bitmapPaint.shader?.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(shape, radius, radius, bitmapPaint)
        }
        canvas.drawRoundRect(shape, radius, radius, darken)
        canvas.drawRoundRect(shape, radius, radius, sheen)
        val inset = density / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, hairline)
    }
}
