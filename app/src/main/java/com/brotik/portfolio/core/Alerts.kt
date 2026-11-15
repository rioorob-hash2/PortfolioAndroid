package com.brotik.portfolio.core

/** Что именно случилось с ценой монеты. */
enum class AlertKind { BELOW_AVG, TARGET_HIT }

/**
 * Сработавшее уведомление. [key] — стабильный идентификатор «монета + тип»:
 * по нему хранится время последнего показа (анти-спам 24 часа).
 */
data class PriceAlert(
    val kind: AlertKind,
    val symbol: String,
    val price: Double,
    val avgPrice: Double = 0.0,
    val targetPrice: Double = 0.0,
    val dropPct: Double = 0.0,
) {
    val key: String get() = kind.name + ":" + symbol
}

/**
 * Правила уведомлений по цене. Чистая логика без Android — проверяется тестами:
 *  - «ниже средней»: цена опустилась ниже средней цены накопления на порог [Settings.thresholdPct];
 *  - «цель достигнута»: цена дошла до [Asset.targetPrice].
 * Анти-спам: не чаще одного раза в сутки на пару «монета + тип».
 */
object Alerts {

    const val MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val DEFAULT_THRESHOLD_PCT = 5.0

    data class Settings(
        val enabled: Boolean = true,
        val belowAvg: Boolean = true,
        val thresholdPct: Double = DEFAULT_THRESHOLD_PCT,
        val target: Boolean = true,
    ) {
        /** Порог всегда в разумных рамках, чтобы не ловить шум 0 % и абсурд 100 %. */
        val safeThreshold: Double get() = thresholdPct.coerceIn(0.5, 90.0)
    }

    /**
     * Возвращает список уведомлений, которые надо показать сейчас.
     * [lastSent] — когда последний раз показывали уведомление с таким ключом.
     * [ignoreThrottle] — ручная проверка из настроек: показываем, даже если недавно было.
     */
    fun evaluate(
        assets: List<Asset>,
        prices: Map<String, Double>,
        settings: Settings,
        lastSent: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        ignoreThrottle: Boolean = false,
    ): List<PriceAlert> {
        if (!settings.enabled) return emptyList()
        val threshold = settings.safeThreshold
        val out = ArrayList<PriceAlert>()

        for (asset in assets) {
            val price = prices[asset.symbol]?.takeIf { it > 0.0 } ?: continue
            val totals = Calc.totals(asset.entries, price)
            if (totals.amount <= 0.0) continue

            if (settings.belowAvg && totals.avgPrice > 0.0) {
                val limit = totals.avgPrice * (1.0 - threshold / 100.0)
                if (price <= limit) {
                    val alert = PriceAlert(
                        kind = AlertKind.BELOW_AVG,
                        symbol = asset.symbol,
                        price = price,
                        avgPrice = totals.avgPrice,
                        dropPct = (totals.avgPrice - price) / totals.avgPrice * 100.0,
                    )
                    if (allowed(alert, lastSent, now, ignoreThrottle)) out.add(alert)
                }
            }

            val target = asset.targetPrice?.takeIf { it > 0.0 }
            if (settings.target && target != null && price >= target) {
                val alert = PriceAlert(
                    kind = AlertKind.TARGET_HIT,
                    symbol = asset.symbol,
                    price = price,
                    avgPrice = totals.avgPrice,
                    targetPrice = target,
                )
                if (allowed(alert, lastSent, now, ignoreThrottle)) out.add(alert)
            }
        }
        return out
    }

    private fun allowed(
        alert: PriceAlert,
        lastSent: Map<String, Long>,
        now: Long,
        ignoreThrottle: Boolean,
    ): Boolean {
        if (ignoreThrottle) return true
        val last = lastSent[alert.key] ?: return true
        return now - last >= MIN_INTERVAL_MS || last > now
    }

    /** Чистим устаревшие отметки при записи, чтобы prefs не пухли. */
    fun prune(lastSent: Map<String, Long>, now: Long = System.currentTimeMillis()): Map<String, Long> =
        lastSent.filterValues { now - it < MIN_INTERVAL_MS }.toMap()
}
