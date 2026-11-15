package com.brotik.portfolio

import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Candle
import com.brotik.portfolio.core.Entry
import com.brotik.portfolio.core.HistoryCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryCalcTest {

    private val zone = ZoneId.systemDefault()

    private fun ts(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun candle(date: String, close: Double) =
        Candle(ts(date), close, close * 1.01, close * 0.99, close)

    private fun buy(date: String, invested: Double, price: Double) = Entry(
        date = LocalDate.parse(date),
        dateRaw = date,
        invested = invested,
        buyPrice = price,
    )

    @Test
    fun `amount timeline accumulates buys and drops`() {
        val entries = listOf(
            buy("2026-01-10", 100.0, 10.0),   // 10 шт
            buy("2026-02-10", 100.0, 20.0),   // +5 → 15
            buy("2026-03-10", -50.0, 25.0),   // −2 → 13
        )
        val timeline = HistoryCalc.amountTimeline(entries)
        assertEquals(3, timeline.size)
        assertEquals(10.0, timeline[0].second, 1e-9)
        assertEquals(15.0, timeline[1].second, 1e-9)
        assertEquals(13.0, timeline[2].second, 1e-9)
    }

    @Test
    fun `amount at time is binary searched`() {
        val timeline = listOf(1000L to 1.0, 2000L to 3.0, 3000L to 6.0)
        assertEquals(0.0, HistoryCalc.amountAt(timeline, 500L), 1e-9)
        assertEquals(1.0, HistoryCalc.amountAt(timeline, 1000L), 1e-9)
        assertEquals(3.0, HistoryCalc.amountAt(timeline, 2500L), 1e-9)
        assertEquals(6.0, HistoryCalc.amountAt(timeline, 9999L), 1e-9)
    }

    @Test
    fun `portfolio value series multiplies amounts by prices`() {
        val btc = Asset("BTC", "Bitcoin", "#F7931A").apply {
            entries.add(buy("2026-11-10", 100.0, 100.0)) // 1 BTC
        }
        val eth = Asset("ETH", "Ethereum", "#627EEA").apply {
            entries.add(buy("2026-11-11", 40.0, 20.0)) // 2 ETH
        }
        val candles = mapOf(
            "BTC" to listOf(candle("2026-11-10", 100.0), candle("2026-11-11", 110.0), candle("2026-11-12", 120.0)),
            "ETH" to listOf(candle("2026-11-10", 10.0), candle("2026-11-11", 20.0), candle("2026-11-12", 30.0)),
        )
        val series = HistoryCalc.valueSeries(listOf(btc, eth), candles)
        assertNotNull(series)
        series!!
        assertEquals(3, series.values.size)
        assertEquals(100.0, series.values[0], 1e-6) // 1*100 + 0
        assertEquals(150.0, series.values[1], 1e-6) // 1*110 + 2*20
        assertEquals(180.0, series.values[2], 1e-6) // 1*120 + 2*30
    }

    @Test
    fun `empty candles give null`() {
        val btc = Asset("BTC", "Bitcoin", "#F7931A")
        assertNull(HistoryCalc.valueSeries(listOf(btc), emptyMap()))
    }

    @Test
    fun `axis labels span the period`() {
        val times = (1..90L).map { it * 86_400_000L }
        val labels = HistoryCalc.axisLabels(times)
        assertEquals(4, labels.size)
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        assertEquals(
            java.time.Instant.ofEpochMilli(times.first()).atZone(zone).format(fmt),
            labels.first(),
        )
        assertEquals(
            java.time.Instant.ofEpochMilli(times.last()).atZone(zone).format(fmt),
            labels.last(),
        )
    }
}
