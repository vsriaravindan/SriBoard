// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

/**
 * Sriboard: progress drawable shown on AI toolbar keys while an AI request is in flight.
 *
 * Two modes:
 *  - Determinate: draws a ring arc + "NN%" in the center (percent from Content-Length).
 *  - Indeterminate: rotating arc (used when the response length is unknown).
 *
 * Must be used from the main thread (the toolbar runs on the main thread).
 */
class AiProgressDrawable(
    private val color: Int = Color.rgb(0x8A, 0xB4, 0xF8),
    density: Float = 1f
) : Drawable() {

    private val densityScale = if (density > 0f) density else 1f
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
        color = color
    }

    private var percent: Int? = null          // null = indeterminate
    private var indeterminateAngle = 0f
    private var animating = false
    private val arc = RectF()

    private val animationRunnable = object : Runnable {
        override fun run() {
            indeterminateAngle = (indeterminateAngle + 8f) % 360f
            invalidateSelf()
            if (animating) handler.postDelayed(this, 16)
        }
    }

    /**
     * @param value null or 0/100 = keep the spinner (fast responses deliver one chunk,
     *              so 0/100 carries no visual information); 1..99 = real intermediate
     *              progress, drawn as a ring + percentage.
     */
    fun update(value: Int?) {
        val determinate = value != null && value in 1..99
        if (!determinate) {
            percent = null
            if (!animating) {
                animating = true
                handler.post(animationRunnable)
            }
        } else {
            percent = value
            if (animating) {
                animating = false
                handler.removeCallbacks(animationRunnable)
            }
            invalidateSelf()
        }
    }

    /** Stop the indeterminate animation. Call when the icon is restored. */
    fun stop() {
        animating = false
        handler.removeCallbacks(animationRunnable)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        val size = minOf(w, h)
        val stroke = size * 0.10f
        backgroundPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        val inset = stroke / 2f + size * 0.02f
        arc.set(inset, inset, w - inset, h - inset)

        canvas.drawArc(arc, 0f, 360f, false, backgroundPaint)

        if (percent != null) {
            val sweep = 360f * percent!! / 100f
            canvas.drawArc(arc, -90f, sweep, false, arcPaint)
            textPaint.textSize = size * 0.34f
            val text = "$percent%"
            val y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, w / 2f, y, textPaint)
        } else {
            canvas.drawArc(arc, indeterminateAngle, 80f, false, arcPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        arcPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        arcPaint.colorFilter = colorFilter
        backgroundPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = dp(24f).toInt()

    override fun getIntrinsicHeight(): Int = dp(24f).toInt()

    private fun dp(value: Float): Float = value * densityScale
}
