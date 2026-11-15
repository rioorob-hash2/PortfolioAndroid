package com.brotik.portfolio

import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Entry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Сверка расчётов с исходной Google-таблицей (эталоны сняты из листов BTC/ETH/TON/OP/SOL/LTC). */
class CalcTest {

    private fun entry(date: String, invested: Double?, price: Double) = Entry(
        date = LocalDate.parse(date),
        dateRaw = date,
        invested = invested,
        buyPrice = price,
    )

    private fun btcEntries(): List<Entry> = listOf(
        entry("2025-11-24", 50.0, 81102.0),
        entry("2026-01-08", 200.0, 98440.0),
        entry("2026-05-28", 100.0, 72800.0),
        entry("2026-06-02", 120.0, 68018.0),
        entry("2026-06-04", 220.0, 61000.0),
        entry("2026-07-17", 200.0, 63000.0),
        entry("2026-07-27", 150.0, 64500.0),
        entry("2026-09-06", 120.0, 79830.0),
        entry("2026-09-23", null, 85000.0),
        entry("2026-11-11", 100.0, 73700.0),
    )

    @Test
    fun `btc totals match spreadsheet`() {
        val t = Calc.totals(btcEntries(), 73700.0)
        assertEquals(1260.0, t.invested, 1e-9)
        assertEquals(0.0177528556, t.amount, 1e-10)
        assertEquals(1308.38546, t.marketValue, 1e-4)
        assertEquals(48.38546, t.pnl, 1e-4)
        assertEquals(70974.49707, t.avgPrice, 1e-4)
    }

    @Test
    fun `price mark row keeps cumulative amount and matches pnl`() {
        val rows = Calc.rows(btcEntries())
        val mark = rows.first { it.entry.isMark }
        // Строка 23.09.2026 в таблице: накопительно 0,01639600353, вложено 1160, PnL 233,6602998
        assertEquals(0.01639600353, mark.cumAmount, 1e-10)
        assertEquals(1160.0, mark.investedCum, 1e-9)
        assertEquals(1393.6603, mark.valueAtBuy, 1e-4)
        assertEquals(233.6602998, mark.pnlAtBuy, 1e-4)
    }

    @Test
    fun `eth with a sale matches spreadsheet`() {
        val t = Calc.totals(
            listOf(
                entry("2026-01-26", 290.0, 3101.0),
                entry("2026-02-02", 60.0, 2240.0),
                entry("2026-03-13", -100.0, 2140.0),
                entry("2026-06-04", 100.0, 1700.0),
                entry("2026-06-24", 100.0, 1600.0),
                entry("2026-09-23", null, 2750.0),
            ),
            null,
        )
        assertEquals(450.0, t.invested, 1e-9)
        assertEquals(0.1948984917, t.amount, 1e-10)
    }

    @Test
    fun `zero price does not divide by zero`() {
        val t = Calc.totals(listOf(entry("2026-01-01", 100.0, 0.0)), null)
        assertEquals(0.0, t.amount, 1e-9)
        assertEquals(0.0, t.marketValue, 1e-9)
    }

    @Test
    fun `portfolio totals sum assets`() {
        val btc = Asset("BTC", "Bitcoin", "#F7931A", btcEntries().toMutableList())
        val eth = Asset(
            "ETH", "Ethereum", "#627EEA",
            mutableListOf(entry("2026-01-26", 290.0, 3101.0)),
        )
        val totals = Calc.portfolioTotals(listOf(btc, eth), mapOf("BTC" to 73700.0, "ETH" to 3000.0))
        assertEquals(1550.0, totals.invested, 1e-9)
        assertEquals(1308.38546 + 0.09351822 * 3000.0, totals.value, 1e-4)
    }

    @Test
    fun `points use buy price historical values`() {
        val pts = Calc.points(btcEntries())
        assertEquals(10, pts.size)
        assertEquals(0.0006165076077, pts.first().amount, 1e-12)
        assertEquals(LocalDate.parse("2025-11-24"), pts.first().date)
    }

    @Test
    fun `entries sort by date regardless of insertion order`() {
        val entries = listOf(
            entry("2026-06-04", 220.0, 61000.0),
            entry("2025-11-24", 50.0, 81102.0),
        )
        val rows = Calc.rows(entries)
        assertEquals(LocalDate.parse("2025-11-24"), rows.first().entry.date)
        assertEquals(0.0006165076077, rows.first().cumAmount, 1e-12)
    }
}
