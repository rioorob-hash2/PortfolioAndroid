package com.brotik.portfolio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.brotik.portfolio.core.Candle
import com.brotik.portfolio.core.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Свечной график цены с горизонтальной линией средней цены накопления.
 * Рисуется на Canvas: без сторонних библиотек, четко на любой плотности.
 */
class CandleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private data class Env(
        val candles: List<Candle>,
        val avgPrice: Double?,
        val mode: Mode,
    )

    enum class Mode { DAY, WEEK, MONTH, YEAR }

    private var data = Env(emptyList(), null, Mode.DAY)
    private var minY = 0.0
    private var maxY = 1.0

    private val upColor = Color.parseColor("#34D399")
    private val downColor = Color.parseColor("#F87171")
    private val gridColor = Color.parseColor("#1B2338")
    private val axisColor = Color.parseColor("#3A476A")
    private val textColor = Color.parseColor("#7B87A0")
    private val accentColor = Color.parseColor("#FFB020")

    private val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = gridColor
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = axisColor
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = textColor
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = accentColor
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val avgTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = accentColor
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121826")
    }

    private val area = RectF()
    private val dayFmt = DateTimeFormatter.ofPattern("dd.MM")
    private val weekFmt = DateTimeFormatter.ofPattern("dd.MM.yy")
    private val yearFmt = DateTimeFormatter.ofPattern("MMM yy")

    fun setData(candles: List<Candle>, avgPrice: Double?, mode: Mode) {
        data = Env(candles, avgPrice?.takeIf { it > 0.0 }, mode)
        computeRange()
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun computeRange() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        data.candles.forEach { c ->
            lo = min(lo, c.low)
            hi = max(hi, c.high)
        }
        data.avgPrice?.let {
            lo = min(lo, it)
            hi = max(hi, it)
        }
        if (lo == Double.MAX_VALUE) { lo = 0.0; hi = 1.0 }
        if (lo == hi) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.08
        minY = lo - pad
        maxY = hi + pad
    }

    private fun yFor(v: Double, bottom: Float, top: Float): Float {
        if (maxY == minY) return (bottom + top) / 2f
        val t = ((v - minY) / (maxY - minY)).toFloat().coerceIn(0f, 1f)
        return bottom - t * (bottom - top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = dp(6f)
        val right = w - dp(8f)
        val top = dp(14f)
        val bottom = h - dp(24f)
        area.set(left, top, right, bottom)

        // сетка + подписи оси Y
        textPaint.textSize = dp(10f)
        val rows = 3
        for (i in 0..rows) {
            val y = top + (bottom - top) * i / rows
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (data.candles.isEmpty()) {
            textPaint.color = Color.parseColor("#5F6B82")
            textPaint.textSize = dp(12f)
            val msg = "нет данных с биржи"
            canvas.drawText(msg, (w - textPaint.measureText(msg)) / 2f, area.centerY(), textPaint)
            textPaint.textSize = dp(10f)
            textPaint.color = textColor
            return
        }

        val n = data.candles.size
        val slot = (right - left) / n
        val bodyW = max(dp(1.4f), slot * 0.62f)

        data.candles.forEachIndexed { i, c ->
            val x = left + slot * i + slot / 2f
            val yHigh = yFor(c.high, bottom, top)
            val yLow = yFor(c.low, bottom, top)
            val yOpen = yFor(c.open, bottom, top)
            val yClose = yFor(c.close, bottom, top)
            val up = c.close >= c.open
            val color = if (up) upColor else downColor

            wickPaint.color = color
            canvas.drawLine(x, yHigh, x, yLow, wickPaint)

            bodyPaint.color = color
            val bodyTop = min(yOpen, yClose)
            val bodyBottom = max(yOpen, yClose)
            val bodyHeight = max(dp(1.2f), bodyBottom - bodyTop)
            canvas.drawRect(x - bodyW / 2f, bodyTop, x + bodyW / 2f, bodyTop + bodyHeight, bodyPaint)
        }

        // подписи дат
        textPaint.color = textColor
        val first = fmtDate(data.candles.first().time)
        val last = fmtDate(data.candles.last().time)
        canvas.drawText(first, left, h - dp(6f), textPaint)
        val lw = textPaint.measureText(last)
        canvas.drawText(last, right - lw, h - dp(6f), textPaint)

        // линия средней цены накопления
        data.avgPrice?.let { avg ->
            val y = yFor(avg, bottom, top)
            canvas.drawLine(left, y, right, y, axisPaint.apply { color = axisColor })
            canvas.drawLine(left, y, right, y, avgPaint)

            val label = "средняя " + Fmt.price(avg)
            val tw = avgTextPaint.measureText(label)
            val lx = (right - tw - dp(4f)).coerceAtLeast(left)
            val ly = (y - dp(6f)).coerceAtLeast(top + dp(12f))
            canvas.drawRect(lx - dp(2f), ly - avgTextPaint.textSize, lx + tw + dp(2f), ly + dp(3f), bgPaint)
            canvas.drawText(label, lx, ly, avgTextPaint)
        }

        // подписи оси Y — поверх графика и линии средней, с подложкой
        textPaint.color = textColor
        val avgY = data.avgPrice?.let { yFor(it, bottom, top) }
        for (i in 0 until rows) {
            val y = top + (bottom - top) * i / rows
            // если подпись почти совпала с линией средней — не рисуем, чтобы не наслаивалась
            if (avgY != null && kotlin.math.abs(y - avgY) < dp(12f)) continue
            val value = maxY - (maxY - minY) * i / rows
            val label = Fmt.price(value)
            val tx = left + dp(2f)
            val tw = textPaint.measureText(label)
            canvas.drawRect(tx - dp(1f), y - dp(3f) - textPaint.textSize, tx + tw, y + dp(3f), bgPaint)
            canvas.drawText(label, tx, y - dp(3f), textPaint)
        }
    }

    private fun fmtDate(ts: Long): String {
        val z = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        return when (data.mode) {
            Mode.DAY, Mode.MONTH -> z.format(dayFmt)
            Mode.WEEK -> z.format(weekFmt)
            Mode.YEAR -> z.format(yearFmt)
        }
    }
}
