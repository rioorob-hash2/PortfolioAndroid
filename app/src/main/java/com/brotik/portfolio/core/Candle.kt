package com.brotik.portfolio.core

import org.json.JSONArray
import org.json.JSONObject

/** Одна свеча OHLC. */
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

enum class CandleRange(val bybitInterval: String, val limit: Int, val coingeckoDays: String) {
    DAY("D", 120, "30"),
    WEEK("W", 52, "365"),
    MONTH("D", 30, "30"),

    /** Год: недельные свечи (52 шт.) — как в недельном, но ровно год окна. */
    YEAR("W", 52, "365"),
}

/** Парсеры ответов Bybit / CoinGecko — вынесены из сетевого слоя, чтобы тестировать без сети. */
object CandleParser {

    /** Bybit v5 kline: result.list от новых к старым, строки [start, open, high, low, close, …]. */
    fun fromBybit(json: String): List<Candle> = runCatching { parseBybit(json) }.getOrDefault(emptyList())

    private fun parseBybit(json: String): List<Candle> {
        val list = JSONObject(json).optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Candle>(list.length())
        for (i in 0 until list.length()) {
            val row = list.optJSONArray(i) ?: continue
            if (row.length() < 5) continue
            val time = row.optString(0).toLongOrNull() ?: continue
            val open = row.optString(1).toDoubleOrNull() ?: continue
            val high = row.optString(2).toDoubleOrNull() ?: continue
            val low = row.optString(3).toDoubleOrNull() ?: continue
            val close = row.optString(4).toDoubleOrNull() ?: continue
            if (high <= 0.0) continue
            out.add(Candle(time, open, high, low, close))
        }
        out.reverse()
        return out
    }

    /** CoinGecko /coins/{id}/ohlc: массив строк [timestamp, open, high, low, close]. */
    fun fromCoinGecko(json: String): List<Candle> {
        val arr: JSONArray = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 5) continue
            val time = row.optLong(0, 0L)
            val open = row.optDouble(1, Double.NaN)
            val high = row.optDouble(2, Double.NaN)
            val low = row.optDouble(3, Double.NaN)
            val close = row.optDouble(4, Double.NaN)
            if (time <= 0L || high.isNaN() || high <= 0.0) continue
            out.add(Candle(time, open, high, low, close))
        }
        return out
    }
}
