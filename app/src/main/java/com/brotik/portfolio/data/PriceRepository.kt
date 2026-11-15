package com.brotik.portfolio.data

import android.content.Context
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Candle
import com.brotik.portfolio.core.CandleParser
import com.brotik.portfolio.core.CandleRange
import com.brotik.portfolio.core.EntryJson
import org.json.JSONObject
import java.util.Locale

/**
 * Живые цены: Bybit spot (один запрос на все пары) → CoinGecko как запасной вариант.
 * При неудаче отдаём последние закэшированные значения.
 */
class PriceRepository(private val ctx: Context) {

    companion object {
        private const val PREFS = "prices"
        private const val KEY_CACHE = "cache"
        private const val KEY_TS = "cache_ts"
        private const val KEY_IDS = "cg_ids"

        private val COINGECKO_IDS = mapOf(
            "BTC" to "bitcoin",
            "ETH" to "ethereum",
            "TON" to "the-open-network",
            "OP" to "optimism",
            "SOL" to "solana",
            "ARB" to "arbitrum",
            "LTC" to "litecoin",
        )
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastUpdateAt(): Long = prefs.getLong(KEY_TS, 0L)

    fun cached(): Map<String, Double> = runCatching {
        val raw = prefs.getString(KEY_CACHE, null) ?: return emptyMap()
        val o = JSONObject(raw)
        val out = HashMap<String, Double>()
        o.keys().forEach { k -> out[k] = o.optDouble(k, 0.0) }
        out
    }.getOrDefault(emptyMap())

    fun saveCache(prices: Map<String, Double>, ts: Long = System.currentTimeMillis()) {
        val o = JSONObject()
        prices.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_CACHE, o.toString()).putLong(KEY_TS, ts).apply()
    }

    /** Основной путь: Bybit отдаёт все spot-пары одним ответом. */
    fun fetch(symbols: Collection<String>): Map<String, Double> {
        val wanted = symbols.map { it.uppercase(Locale.US) }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        var error: Exception? = null

        runCatching {
            val body = Http.get("https://api.bybit.com/v5/market/tickers?category=spot")
            val list = JSONObject(body).optJSONObject("result")?.optJSONArray("list")
                ?: throw IllegalStateException("Bybit: пустой ответ")
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val sym = o.optString("symbol")
                for (w in wanted) {
                    if (sym == "${w}USDT") {
                        val price = o.optString("lastPrice").toDoubleOrNull()
                        if (price != null && price > 0.0) out[w] = price
                    }
                }
            }
        }.onFailure { error = it as? Exception }

        val missing = wanted - out.keys
        if (missing.isNotEmpty()) {
            runCatching {
                val idBySymbol = missing.mapNotNull { sym -> coinGeckoId(sym)?.let { sym to it } }.toMap()
                val ids = idBySymbol.values.joinToString(",")
                if (ids.isNotEmpty()) {
                    val body = Http.get("https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd")
                    val o = JSONObject(body)
                    idBySymbol.forEach { (sym, id) ->
                        val p = o.optJSONObject(id)?.optDouble("usd", 0.0) ?: 0.0
                        if (p > 0.0) out[sym] = p
                    }
                }
            }.onFailure { e -> if (out.isEmpty()) error = e as? Exception }
        }

        if (out.isEmpty()) {
            val cached = cached()
            if (cached.isNotEmpty()) return cached
            throw error ?: IllegalStateException("цены недоступны")
        }
        saveCache(out)
        return out
    }

    /** Свечи цены: Bybit kline, запасной вариант — CoinGecko OHLC. Пусто, если данных нет. */
    fun fetchCandles(symbol: String, range: CandleRange): List<Candle> =
        fetchCandles(symbol, range.bybitInterval, range.limit, range.coingeckoDays)

    fun fetchCandles(symbol: String, range: com.brotik.portfolio.core.HistoryRange): List<Candle> =
        fetchCandles(symbol, range.bybitInterval, range.limit, range.coingeckoDays)

    private fun fetchCandles(symbol: String, interval: String, limit: Int, cgDays: String?): List<Candle> {
        val sym = symbol.uppercase(Locale.US)
        runCatching {
            val url = "https://api.bybit.com/v5/market/kline?category=spot&symbol=${sym}USDT" +
                "&interval=$interval&limit=$limit"
            val list = CandleParser.fromBybit(Http.get(url))
            if (list.isNotEmpty()) return list
        }
        runCatching {
            val id = coinGeckoId(sym) ?: return emptyList()
            val url = "https://api.coingecko.com/api/v3/coins/$id/ohlc?vs_currency=usd&days=${cgDays ?: "30"}"
            return CandleParser.fromCoinGecko(Http.get(url))
        }
        return emptyList()
    }

    /** CoinGecko-id для произвольного тикера: базовый список + поиск + кэш. */
    fun coinGeckoId(symbol: String): String? {
        val sym = symbol.uppercase(Locale.US)
        COINGECKO_IDS[sym]?.let { return it }
        idCache()[sym]?.let { return it }
        val found = runCatching {
            val body = Http.get("https://api.coingecko.com/api/v3/search?query=$sym")
            val coins = JSONObject(body).optJSONArray("coins") ?: return@runCatching null
            var best: String? = null
            for (i in 0 until coins.length()) {
                val c = coins.optJSONObject(i) ?: continue
                val s = c.optString("symbol")
                val id = c.optString("id")
                if (id.isNotBlank() && s.equals(sym, ignoreCase = true)) {
                    best = id
                    break
                }
            }
            best
        }.getOrNull()
        if (found != null) saveId(sym, found)
        return found
    }

    private fun idCache(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY_IDS, null) ?: return emptyMap()
        val o = JSONObject(raw)
        val out = HashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        out
    }.getOrDefault(emptyMap())

    private fun saveId(symbol: String, id: String) {
        val map = idCache().toMutableMap()
        map[symbol] = id
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_IDS, o.toString()).apply()
    }
}
