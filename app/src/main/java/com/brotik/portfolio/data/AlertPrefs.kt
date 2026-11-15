package com.brotik.portfolio.data

import android.content.Context
import com.brotik.portfolio.core.Alerts
import com.brotik.portfolio.core.PriceAlert
import org.json.JSONObject

/**
 * Настройки уведомлений по цене + отметки «когда показывали», чтобы не спамить
 * чаще одного раза в сутки на пару «монета + тип».
 */
class AlertPrefs(private val ctx: Context) {

    companion object {
        private const val PREFS = "alerts"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BELOW = "below_avg"
        private const val KEY_TARGET = "target"
        private const val KEY_THRESHOLD = "threshold_pct"
        private const val KEY_SENT = "sent_at"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(): Alerts.Settings = Alerts.Settings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        belowAvg = prefs.getBoolean(KEY_BELOW, true),
        thresholdPct = prefs.getFloat(KEY_THRESHOLD, Alerts.DEFAULT_THRESHOLD_PCT.toFloat()).toDouble(),
        target = prefs.getBoolean(KEY_TARGET, true),
    )

    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun setBelowAvg(value: Boolean) = prefs.edit().putBoolean(KEY_BELOW, value).apply()

    fun setTarget(value: Boolean) = prefs.edit().putBoolean(KEY_TARGET, value).apply()

    fun setThreshold(pct: Double) =
        prefs.edit().putFloat(KEY_THRESHOLD, pct.coerceIn(0.5, 90.0).toFloat()).apply()

    fun lastSent(): Map<String, Long> = runCatching {
        val raw = prefs.getString(KEY_SENT, null) ?: return emptyMap()
        val o = JSONObject(raw)
        val out = HashMap<String, Long>()
        o.keys().forEach { k -> out[k] = o.optLong(k, 0L) }
        out
    }.getOrDefault(emptyMap())

    /** Запоминаем время показа; старые отметки выбрасываем. */
    @Synchronized
    fun record(alerts: List<PriceAlert>, now: Long = System.currentTimeMillis()) {
        if (alerts.isEmpty()) return
        val map = Alerts.prune(lastSent(), now).toMutableMap()
        alerts.forEach { map[it.key] = now }
        write(map)
    }

    /**
     * Ручная проверка: показываем уведомление, но НЕ сдвигаем уже существующую метку —
     * иначе «Проверить сейчас» заглушило бы фоновые уведомления на сутки.
     */
    @Synchronized
    fun recordIfNew(alerts: List<PriceAlert>, now: Long = System.currentTimeMillis()) {
        if (alerts.isEmpty()) return
        val map = Alerts.prune(lastSent(), now).toMutableMap()
        var changed = false
        alerts.forEach { alert ->
            if (!map.containsKey(alert.key)) {
                map[alert.key] = now
                changed = true
            }
        }
        if (changed) write(map)
    }

    private fun write(map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_SENT, o.toString()).apply()
    }

    /** Сброс анти-спама — нужен тестам и «полной» ручной проверке. */
    fun clearThrottle() = prefs.edit().remove(KEY_SENT).apply()
}
