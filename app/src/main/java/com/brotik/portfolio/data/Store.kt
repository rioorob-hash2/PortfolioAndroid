package com.brotik.portfolio.data

import android.content.Context
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.EntryJson
import com.brotik.portfolio.core.Portfolio
import org.json.JSONObject
import java.io.File

/**
 * Хранилище портфеля: один JSON-файл во внутренней папке приложения.
 * JSON — он же формат резервных копий, поэтому бэкап и данные не расходятся.
 */
class Store(private val ctx: Context) {

    private val file = File(ctx.filesDir, "portfolio.json")
    private val tmp = File(ctx.filesDir, "portfolio.json.tmp")

    fun exists(): Boolean = file.exists()

    fun lastModified(): Long = if (file.exists()) file.lastModified() else 0L

    fun load(): Portfolio? {
        if (!file.exists()) return null
        return runCatching {
            EntryJson.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    /** Исходные данные из таблицы, зашитые в assets (на случай полной потери). */
    fun seed(): Portfolio? = runCatching {
        val text = ctx.assets.open("seed.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        EntryJson.fromJson(JSONObject(text))
    }.getOrNull()

    /** Если данных нет — первичный импорт из таблицы. */
    fun loadOrSeed(): Portfolio {
        load()?.let { return it }
        val p = seed() ?: Portfolio()
        if (p.assets.isNotEmpty()) save(p)
        return p
    }

    @Synchronized
    fun save(p: Portfolio) {
        val json = EntryJson.toJson(p)
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** Сколько всего записей — для проверки после импорта. */
    fun countEntries(p: Portfolio): Int = p.assets.sumOf { it.entries.size }

    fun assetColorMap(p: Portfolio): Map<String, Int> = p.assets.associate { it.symbol to it.colorInt }

    fun emptyAsset(symbol: String, title: String, color: String): Asset = Asset(symbol, title, color)
}
