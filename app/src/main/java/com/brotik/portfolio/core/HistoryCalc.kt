package com.brotik.portfolio.core

import java.time.ZoneId

/** Периоды графика истории на главном экране. */
enum class HistoryRange(
    val bybitInterval: String,
    val limit: Int,
    val coingeckoDays: String,
) {
    H24("60", 24, "1"),
    D7("60", 168, "7"),
    D30("240", 180, "30"),
    D90("D", 90, "90"),

    /** Год: недельные свечи (52 точки) — дешевле по трафику, чем 365 дневных. */
    YEAR("W", 52, "365"),
}

/** Ряд стоимости портфеля: таймстемпы и значения (в $). */
data class HistorySeries(
    val times: List<Long>,
    val values: List<Double>,
    /** Вложенные средства к каждой точке (нарастающим итогом, $). */
    val invested: List<Double>? = null,
)

/**
 * Стоимость портфеля во времени: для каждой точки берём цену каждой монеты
 * и количество, которое было накоплено к этому моменту.
 */
object HistoryCalc {

    fun valueSeries(assets: List<Asset>, candles: Map<String, List<Candle>>): HistorySeries? {
        val base = candles.values.maxByOrNull { it.size }?.takeIf { it.size >= 2 } ?: return null
        val prepared = assets.mapNotNull { a ->
            val series = candles[a.symbol]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val amounts = amountTimeline(a.entries)
            Triple(a, series, amounts)
        }
        if (prepared.isEmpty()) return null

        val values = ArrayList<Double>(base.size)
        for (point in base) {
            var sum = 0.0
            prepared.forEach { (_, series, amounts) ->
                val candle = candleAt(series, point.time) ?: return@forEach
                val amount = amountAt(amounts, point.time)
                if (amount > 0.0) sum += amount * candle.close
            }
            values.add(sum)
        }
        return HistorySeries(base.map { it.time }, values)
    }

    /** Вложенные средства нарастающим итогом по тем же точкам времени, $. */
    fun investedSeries(assets: List<Asset>, times: List<Long>): List<Double> {
        if (times.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val events = assets.flatMap { a ->
            a.entries.mapNotNull { e ->
                val d = e.date ?: return@mapNotNull null
                val sum = e.invested ?: return@mapNotNull null
                if (sum == 0.0) return@mapNotNull null
                d.atStartOfDay(zone).toInstant().toEpochMilli() to sum
            }
        }.sortedBy { it.first }
        val out = ArrayList<Double>(times.size)
        var i = 0
        var cum = 0.0
        for (t in times) {
            while (i < events.size && events[i].first <= t) {
                cum += events[i].second
                i++
            }
            out.add(cum)
        }
        return out
    }

    /** Сумма купленного (с учётом продаж) к моменту времени. */
    fun amountAt(timeline: List<Pair<Long, Double>>, time: Long): Double {
        var lo = 0
        var hi = timeline.size - 1
        var ans = 0.0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (timeline[mid].first <= time) {
                ans = timeline[mid].second
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Пары (время покупки, накопленное количество) по возрастанию времени. */
    fun amountTimeline(entries: List<Entry>): List<Pair<Long, Double>> {
        val zone = ZoneId.systemDefault()
        val sorted = entries.mapNotNull { e ->
            val d = e.date ?: return@mapNotNull null
            val t = d.atStartOfDay(zone).toInstant().toEpochMilli()
            t to e.boughtAmount
        }.sortedBy { it.first }
        var cum = 0.0
        return sorted.map { (t, bought) ->
            cum += bought
            t to cum
        }
    }

    private fun candleAt(series: List<Candle>, time: Long): Candle? {
        if (series.isEmpty()) return null
        var lo = 0
        var hi = series.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (series[mid].time <= time) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (idx >= 0) series[idx] else series.firstOrNull()
    }

    /** Столько-то дат по оси X для подписи графика (для суток — время, для года — месяц-год). */
    fun axisLabels(times: List<Long>, range: HistoryRange = HistoryRange.D30, count: Int = 4): List<String> {
        if (times.isEmpty()) return emptyList()
        val pattern = when (range) {
            HistoryRange.H24 -> "HH:mm"
            HistoryRange.YEAR -> "MMM yy"
            else -> "d MMM"
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern(pattern)
        val zone = ZoneId.systemDefault()
        val n = times.size
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val idx = if (count == 1) 0 else (n - 1) * i / (count - 1)
            out.add(java.time.Instant.ofEpochMilli(times[idx]).atZone(zone).format(fmt))
        }
        return out
    }
}
