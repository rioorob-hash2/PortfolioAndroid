package com.brotik.portfolio.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Одна запись журнала: либо докупка (invested != null), либо только пометка цены
 * (invested == null) — как строка с датой и ценой, но без суммы.
 */
data class Entry(
    var id: String = UUID.randomUUID().toString(),
    var date: LocalDate? = null,
    var dateRaw: String = "",
    var invested: Double? = null,
    var buyPrice: Double = 0.0,
    var note: String? = null,
    var ambiguousDate: Boolean = false,
) {
    val isMark: Boolean get() = invested == null
    val boughtAmount: Double get() = if (buyPrice > 0.0) (invested ?: 0.0) / buyPrice else 0.0

    fun copy(): Entry = Entry(id, date, dateRaw, invested, buyPrice, note, ambiguousDate)
}

data class Asset(
    val symbol: String,
    val title: String,
    val color: String,
    val entries: MutableList<Entry> = mutableListOf(),
    /** Целевая цена для уведомления «цель достигнута»; null — цель не задана. */
    var targetPrice: Double? = null,
) {
    val colorInt: Int
        get() = runCatching {
            val c = color.removePrefix("#")
            val v = c.toLong(16)
            if (c.length <= 6) (0xFF000000L or v).toInt() else v.toInt()
        }.getOrDefault(0xFF888888.toInt())

    fun sortedEntries(): List<Entry> = entries.sortedWith(
        compareBy<Entry> { it.date ?: LocalDate.MAX }.thenBy { it.dateRaw }
    )
}

data class Portfolio(
    val assets: MutableList<Asset> = mutableListOf(),
) {
    fun asset(symbol: String): Asset? = assets.firstOrNull { it.symbol == symbol }
}

object EntryJson {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun toJson(p: Portfolio): JSONObject = JSONObject().apply {
        put("version", 1)
        put("savedAt", System.currentTimeMillis())
        put("assets", JSONArray().also { arr ->
            p.assets.forEach { a ->
                arr.put(JSONObject().apply {
                    put("symbol", a.symbol)
                    put("title", a.title)
                    put("color", a.color)
                    put("targetPrice", a.targetPrice ?: JSONObject.NULL)
                    put("entries", JSONArray().also { ea ->
                        a.entries.forEach { e -> ea.put(entryToJson(e)) }
                    })
                })
            }
        })
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("date", e.date?.format(ISO) ?: JSONObject.NULL)
        put("dateRaw", e.dateRaw)
        put("invested", e.invested ?: JSONObject.NULL)
        put("buyPrice", e.buyPrice)
        put("note", e.note ?: JSONObject.NULL)
        put("ambiguousDate", e.ambiguousDate)
    }

    fun fromJson(root: JSONObject): Portfolio {
        val assets = mutableListOf<Asset>()
        val arr = root.optJSONArray("assets") ?: throw IllegalArgumentException("нет массива assets")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val symbol = a.optString("symbol")
            if (symbol.isBlank()) continue
            val asset = Asset(
                symbol = symbol,
                title = a.optString("title", symbol),
                color = a.optString("color", "#8899AA"),
                targetPrice = if (a.isNull("targetPrice")) null
                else a.optDouble("targetPrice", 0.0).takeIf { it > 0.0 },
            )
            val ea = a.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until ea.length()) {
                val e = ea.getJSONObject(j)
                asset.entries.add(entryFromJson(e))
            }
            assets.add(asset)
        }
        return Portfolio(assets)
    }

    private fun entryFromJson(e: JSONObject): Entry {
        val dateStr = if (e.isNull("date")) null else e.optString("date").takeIf { it.isNotBlank() }
        val price = when {
            e.isNull("buyPrice") -> 0.0
            else -> e.optDouble("buyPrice", 0.0)
        }
        return Entry(
            id = e.optString("id").ifBlank { UUID.randomUUID().toString() },
            date = dateStr?.let { runCatching { LocalDate.parse(it, ISO) }.getOrNull() },
            dateRaw = e.optString("dateRaw", dateStr.orEmpty()),
            invested = if (e.isNull("invested")) null else e.optDouble("invested", 0.0),
            buyPrice = price,
            note = if (e.isNull("note")) null else e.optString("note").takeIf { it.isNotBlank() },
            ambiguousDate = e.optBoolean("ambiguousDate", false),
        )
    }
}
