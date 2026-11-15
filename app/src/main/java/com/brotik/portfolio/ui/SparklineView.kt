package com.brotik.portfolio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Маленький спарклайн для строки списка активов. */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var values: List<Double> = emptyList()
    private var color: Int = 0xFF34D399.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun setData(values: List<Double>, color: Int) {
        this.values = values
        this.color = color
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || values.size < 2) return

        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        values.forEach { v -> lo = min(lo, v); hi = max(hi, v) }
        if (lo == hi) { lo -= 1.0; hi += 1.0 }
        val pad = dp(3f)
        val top = pad
        val bottom = h - pad
        val left = pad
        val right = w - pad

        val step = (right - left) / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            val t = ((v - lo) / (hi - lo)).toFloat()
            (left + step * i) to (bottom - t * (bottom - top))
        }

        path.reset()
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 0 until pts.size - 1) {
            val p0 = pts[max(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[min(pts.size - 1, i + 2)]
            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
        }

        val fillPath = Path(path)
        fillPath.lineTo(pts.last().first, bottom)
        fillPath.lineTo(pts.first().first, bottom)
        fillPath.close()
        fill.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf((color and 0x00FFFFFF) or (70 shl 24), (color and 0x00FFFFFF) or (4 shl 24)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fill)
        fill.shader = null

        line.color = color
        canvas.drawPath(path, line)
    }
}
