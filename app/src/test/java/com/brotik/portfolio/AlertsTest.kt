package com.brotik.portfolio

import com.brotik.portfolio.core.AlertKind
import com.brotik.portfolio.core.Alerts
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Правила уведомлений: порог «ниже средней», цель, анти-спам. */
class AlertsTest {

    private val now = 1_800_000_000_000L

    private fun buy(invested: Double, price: Double) = Entry(
        date = LocalDate.parse("2026-01-10"),
        dateRaw = "10.01.2026",
        invested = invested,
        buyPrice = price,
    )

    /** 1 монета по средней $100. */
    private fun btc(target: Double? = null) = Asset("BTC", "Bitcoin", "#F7931A").apply {
        entries.add(buy(100.0, 100.0))
        targetPrice = target
    }

    @Test
    fun `price below average minus threshold fires`() {
        val alerts = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 90.0),
            settings = Alerts.Settings(thresholdPct = 5.0),
            now = now,
        )
        assertEquals(1, alerts.size)
        val a = alerts.first()
        assertEquals(AlertKind.BELOW_AVG, a.kind)
        assertEquals("BTC", a.symbol)
        assertEquals(10.0, a.dropPct, 1e-9)
        assertEquals("BELOW_AVG:BTC", a.key)
    }

    @Test
    fun `price just above threshold does not fire`() {
        val alerts = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 96.0),
            settings = Alerts.Settings(thresholdPct = 5.0),
            now = now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `bigger threshold suppresses alert`() {
        val alerts = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 90.0),
            settings = Alerts.Settings(thresholdPct = 15.0),
            now = now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `target reached fires`() {
        val alerts = Alerts.evaluate(
            assets = listOf(btc(target = 120.0)),
            prices = mapOf("BTC" to 125.0),
            settings = Alerts.Settings(thresholdPct = 5.0),
            now = now,
        )
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.TARGET_HIT, alerts.first().kind)
        assertEquals(120.0, alerts.first().targetPrice, 1e-9)
    }

    @Test
    fun `both rules can fire for one coin`() {
        // Цель ниже текущей цены, а средняя сильно выше: срабатывают оба правила.
        val asset = Asset("ETH", "Ethereum", "#627EEA").apply {
            entries.add(buy(300.0, 300.0)) // 1 ETH, средняя 300
            targetPrice = 100.0            // цель уже пройдена
        }
        val alerts = Alerts.evaluate(
            assets = listOf(asset),
            prices = mapOf("ETH" to 120.0),
            settings = Alerts.Settings(thresholdPct = 5.0),
            now = now,
        )
        assertEquals(2, alerts.size)
        assertEquals(setOf(AlertKind.BELOW_AVG, AlertKind.TARGET_HIT), alerts.map { it.kind }.toSet())
    }

    @Test
    fun `throttle blocks repeat within a day`() {
        val recent = mapOf("BELOW_AVG:BTC" to now - 60_000L)
        val alerts = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 90.0),
            settings = Alerts.Settings(),
            lastSent = recent,
            now = now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `throttle lets alert through after a day and when forced`() {
        val old = mapOf("BELOW_AVG:BTC" to now - Alerts.MIN_INTERVAL_MS - 1)
        val afterDay = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 90.0),
            settings = Alerts.Settings(),
            lastSent = old,
            now = now,
        )
        assertEquals(1, afterDay.size)

        val recent = mapOf("BELOW_AVG:BTC" to now - 1000L)
        val forced = Alerts.evaluate(
            assets = listOf(btc()),
            prices = mapOf("BTC" to 90.0),
            settings = Alerts.Settings(),
            lastSent = recent,
            now = now,
            ignoreThrottle = true,
        )
        assertEquals(1, forced.size)
    }

    @Test
    fun `disabled settings suppress everything`() {
        val alerts = Alerts.evaluate(
            assets = listOf(btc(target = 50.0)),
            prices = mapOf("BTC" to 40.0),
            settings = Alerts.Settings(enabled = false),
            now = now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `coin without purchases and without price is skipped`() {
        val empty = Asset("ARB", "Arbitrum", "#28A0F0")
        val alerts = Alerts.evaluate(
            assets = listOf(empty),
            prices = mapOf("ARB" to 1.0),
            settings = Alerts.Settings(),
            now = now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `threshold is clamped to sane range`() {
        assertEquals(0.5, Alerts.Settings(thresholdPct = 0.0).safeThreshold, 1e-9)
        assertEquals(90.0, Alerts.Settings(thresholdPct = 500.0).safeThreshold, 1e-9)
        assertEquals(5.0, Alerts.Settings().safeThreshold, 1e-9)
    }

    @Test
    fun `prune drops stale marks`() {
        val map = mapOf(
            "BELOW_AVG:BTC" to now - Alerts.MIN_INTERVAL_MS - 5,
            "TARGET_HIT:ETH" to now - 1000,
        )
        val pruned = Alerts.prune(map, now)
        assertEquals(setOf("TARGET_HIT:ETH"), pruned.keys)
    }
}
