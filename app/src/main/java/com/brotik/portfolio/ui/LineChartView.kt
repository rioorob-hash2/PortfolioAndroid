package com.brotik.portfolio.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Гладкий линейный график на Canvas: несколько серий, заливка градиентом,
 * отметка нуля, подписи осей. Без внешних библиотек.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    data class Series(
        val values: List<Double>,
        val color: Int,
        val fill: Boolean = false,
        val dashed: Boolean = false,
        val strokeDp: Float = 2.2f,
    )

    data class Labels(
        val first: String = "",
        val last: String = "",
        val yFormat: (Double) -> String = { it.toString() },
        val xLabels: List<String> = emptyList(),
    )

    private var series: List<Series> = emptyList()
    private var labels = Labels()
    private var progress = 1f
    private var minY = 0.0
    private var maxY = 1.0
    private var zeroLine = false
    private var hasData = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val contentRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 550
        interpolator = DecelerateInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    init {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        fillPaint.style = Paint.Style.FILL
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = dp(1f)
        gridPaint.color = Color.parseColor("#1E2740")
        textPaint.textSize = dp(10f)
        textPaint.color = Color.parseColor("#7B87A0")
        dotPaint.style = Paint.Style.FILL
    }

    fun setData(series: List<Series>, labels: Labels = Labels()) {
        this.series = series.filter { it.values.isNotEmpty() }
        this.labels = labels
        hasData = this.series.isNotEmpty() && this.series.any { it.values.size >= 2 }
        computeRange()
        progress = 0f
        animator.cancel()
        animator.start()
        invalidate()
    }

    private fun computeRange() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var neg = false
        series.forEach { s ->
            s.values.forEach { v ->
                if (v.isFinite()) {
                    lo = min(lo, v)
                    hi = max(hi, v)
                    if (v < 0) neg = true
                }
            }
        }
        zeroLine = neg
        if (lo == Double.MAX_VALUE) { lo = 0.0; hi = 1.0 }
        if (lo == hi) { lo -= 1.0; hi += 1.0 }
        if (neg) lo = min(lo, 0.0)
        val pad = (hi - lo) * 0.12
        minY = if (neg) min(lo, 0.0) - pad * 0.4 else lo - pad
        maxY = hi + pad
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = dp(6f)
        val right = w - dp(7f)
        val top = dp(16f)
        val bottom = h - dp(22f)
        contentRect.set(left, top, right, bottom)

        drawGrid(canvas)

        if (!hasData) {
            drawEmpty(canvas)
            return
        }

        val maxCount = series.maxOf { it.values.size }
        val step = if (maxCount > 1) (right - left) / (maxCount - 1) else 0f

        series.forEach { s ->
            if (s.values.size < 2) {
                if (s.values.size == 1) drawSingleDot(canvas, s, left, right)
                return@forEach
            }
            val n = s.values.size
            val sStep = (right - left) / (n - 1)
            path.reset()
            val pts = ArrayList<Pair<Float, Float>>(n)
            for (i in 0 until n) {
                val x = left + sStep * i
                val y = yFor(s.values[i], bottom, top)
                pts.add(x to y)
            }
            smoothPath(pts, path)

            if (s.fill) {
                val fillPath = Path(path)
                fillPath.lineTo(pts.last().first, bottom)
                fillPath.lineTo(pts.first().first, bottom)
                fillPath.close()
                fillPaint.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(alpha(s.color, 90), alpha(s.color, 8)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                fillPaint.alpha = (255 * progress).toInt().coerceIn(0, 255)
                canvas.drawPath(fillPath, fillPaint)
                fillPaint.shader = null
            }

            linePaint.color = s.color
            linePaint.strokeWidth = dp(s.strokeDp)
            linePaint.pathEffect = if (s.dashed) DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) else null
            canvas.save()
            canvas.clipRect(left, top - dp(2f), left + (right - left) * progress, bottom + dp(2f))
            canvas.drawPath(path, linePaint)
            canvas.restore()
            linePaint.pathEffect = null

            // точка на последнем значении
            val lastX = pts.last().first
            val lastY = pts.last().second
            dotPaint.color = alpha(s.color, 40)
            canvas.drawCircle(lastX, lastY, dp(6f), dotPaint)
            dotPaint.color = s.color
            canvas.drawCircle(lastX, lastY, dp(3f), dotPaint)
        }

        if (zeroLine) {
            val zy = yFor(0.0, bottom, top)
            gridPaint.color = Color.parseColor("#3A476A")
            canvas.drawLine(left, zy, right, zy, gridPaint)
        }

        drawXLabels(canvas, left, right, h)
    }

    private fun drawGrid(canvas: Canvas) {
        val rows = 3
        gridPaint.color = Color.parseColor("#1B2338")
        for (i in 0..rows) {
            val y = contentRect.top + (contentRect.height()) * i / rows
            canvas.drawLine(contentRect.left, y, contentRect.right, y, gridPaint)
            if (i < rows) {
                val value = maxY - (maxY - minY) * i / rows
                val label = labels.yFormat(value)
                // верхнюю подпись рисуем под линией, чтобы её не срезало краем
                val ty = if (i == 0) y + dp(13f) else y - dp(3f)
                canvas.drawText(label, contentRect.left + dp(2f), ty, textPaint)
            }
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        textPaint.textSize = dp(12f)
        textPaint.color = Color.parseColor("#5F6B82")
        val msg = "мало данных для графика"
        val tw = textPaint.measureText(msg)
        canvas.drawText(msg, (width - tw) / 2f, contentRect.centerY(), textPaint)
        textPaint.textSize = dp(10f)
        textPaint.color = Color.parseColor("#7B87A0")
    }

    private fun drawSingleDot(canvas: Canvas, s: Series, left: Float, right: Float) {
        val x = (left + right) / 2f
        val y = yFor(s.values[0], contentRect.bottom, contentRect.top)
        dotPaint.color = s.color
        canvas.drawCircle(x, y, dp(4f), dotPaint)
    }

    private fun drawXLabels(canvas: Canvas, left: Float, right: Float, h: Float) {
        textPaint.textSize = dp(10f)
        textPaint.color = Color.parseColor("#7B87A0")
        val y = h - dp(6f)
        if (labels.xLabels.size >= 2) {
            val n = labels.xLabels.size
            labels.xLabels.forEachIndexed { i, label ->
                val tw = textPaint.measureText(label)
                val x = when (i) {
                    0 -> left
                    n - 1 -> right - tw
                    else -> left + (right - left) * i / (n - 1) - tw / 2f
                }
                canvas.drawText(label, x, y, textPaint)
            }
            return
        }
        if (labels.first.isNotEmpty()) canvas.drawText(labels.first, left, y, textPaint)
        if (labels.last.isNotEmpty()) {
            val tw = textPaint.measureText(labels.last)
            canvas.drawText(labels.last, right - tw, y, textPaint)
        }
    }

    private fun yFor(v: Double, bottom: Float, top: Float): Float {
        if (maxY == minY) return (bottom + top) / 2f
        val t = ((v - minY) / (maxY - minY)).toFloat().coerceIn(0f, 1f)
        return bottom - t * (bottom - top)
    }

    /** Catmull-Rom → кубические Безье: линия без резких изломов. */
    private fun smoothPath(pts: List<Pair<Float, Float>>, out: Path) {
        if (pts.size < 2) return
        out.moveTo(pts[0].first, pts[0].second)
        if (pts.size == 2) {
            out.lineTo(pts[1].first, pts[1].second)
            return
        }
        for (i in 0 until pts.size - 1) {
            val p0 = pts[max(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[min(pts.size - 1, i + 2)]
            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f
            out.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
        }
    }

    private fun alpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)
}
