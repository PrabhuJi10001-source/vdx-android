package com.vdx.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * VedaxiOrbView — the glowing radial dot-matrix orb from the Vedaxi visual identity
 * (golden core fading to violet edge), with a slow breathing pulse.
 * Pure View, no dependencies — mirrors the "Just say it." hero visual.
 */
class VedaxiOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    // identity colors (design tokens)
    private val goldCore = Color.rgb(255, 214, 107)
    private val violetEdge = Color.rgb(139, 92, 246)
    private val deep = Color.rgb(91, 42, 158)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = Math.min(width, height) / 2f * 0.92f

        val breathe = 1f + 0.05f * sin(phase)

        // concentric rings of dots: gold core -> violet edge
        val rings = 7
        for (ring in rings downTo 1) {
            val t = ring / rings.toFloat()
            val r = maxR * t * breathe
            val count = 6 + ring * 4
            val mix = t * t // quadratic: keep core golden longer
            val cr = (Color.red(goldCore) * (1 - mix) + Color.red(violetEdge) * mix).toInt()
            val cg = (Color.green(goldCore) * (1 - mix) + Color.green(violetEdge) * mix).toInt()
            val cb = (Color.blue(goldCore) * (1 - mix) + Color.blue(violetEdge) * mix).toInt()
            val alpha = ((1f - t) * 160 + 70 + 24 * sin(phase * 2 + ring)).toInt().coerceIn(40, 230)
            paint.color = Color.argb(alpha, cr, cg, cb)
            val dotR = maxR * 0.028f * (1.25f - t * 0.55f)
            for (i in 0 until count) {
                val a = phase * 0.35f + (i * 2f * Math.PI.toFloat() / count) + ring * 0.4f
                val x = cx + r * cos(a)
                val y = cy + r * sin(a) * 0.92f // slight vertical squash = orb feel
                canvas.drawCircle(x, y, dotR, paint)
            }
        }

        // breathing core
        paint.color = Color.argb(235, Color.red(goldCore), Color.green(goldCore), Color.blue(goldCore))
        canvas.drawCircle(cx, cy, maxR * 0.10f * breathe, paint)
        paint.color = Color.argb(60, Color.red(deep), Color.green(deep), Color.blue(deep))
        canvas.drawCircle(cx, cy, maxR * 0.16f * breathe, paint)
    }
}
