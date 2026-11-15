package com.brotik.portfolio.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Кольцевая диаграмма распределения портфеля. */
class DonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    data class Slice(val value: Double, val color: Int)

    private var slices: List<Slice> = emptyList()
    private var total = 0.0
    private var progress = 1f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(16f)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(16f)
        color = 0xFF1B2338.toInt()
    }
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        interpolator = DecelerateInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    fun setSlices(slices: List<Slice>) {
        this.slices = slices.filter { it.value > 0.0 && it.color != 0 }
        total = this.slices.sumOf { it.value }
        progress = 0f
        animator.cancel()
        animator.start()
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val stroke = dp(16f)
        val pad = stroke / 2f + dp(2f)
        rect.set(
            (width - size) / 2f + pad,
            (height - size) / 2f + pad,
            (width + size) / 2f - pad,
            (height + size) / 2f - pad,
        )
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        if (total <= 0.0) return

        var start = -90f
        val gap = if (slices.size > 1) 2.2f else 0f
        slices.forEach { s ->
            val sweep = (s.value / total * 360.0).toFloat()
            if (sweep <= 0f) return@forEach
            ringPaint.color = s.color
            val drawSweep = (sweep - gap) * progress
            if (drawSweep > 0f) {
                canvas.drawArc(rect, start + gap / 2f, drawSweep, false, ringPaint)
            }
            start += sweep
        }
    }
}
