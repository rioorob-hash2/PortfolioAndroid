package com.brotik.portfolio.core

import java.time.LocalDate

/** Расчёты повторяют столбцы Google-таблицы: куплено, накопительно, стоимость, PnL, средняя. */
object Calc {

    /** Строка истории по одной записи (аналог строки листа). */
    data class Row(
        val entry: Entry,
        val bought: Double,
        val cumAmount: Double,
        val investedCum: Double,
        val valueAtBuy: Double,
        val pnlAtBuy: Double,
        val avgAtBuy: Double,
    )

    /** Итоги по активу на текущую цену. */
    data class Totals(
        val invested: Double,
        val amount: Double,
        val avgPrice: Double,
        val marketValue: Double,
        val pnl: Double,
        val pnlPct: Double,
    )

    fun rows(entries: List<Entry>): List<Row> {
        val sorted = entries.sortedWith(
            compareBy<Entry> { it.date ?: LocalDate.MAX }.thenBy { it.dateRaw }
        )
        val out = ArrayList<Row>(sorted.size)
        var cum = 0.0
        var cumInv = 0.0
        for (e in sorted) {
            val bought = if (e.buyPrice > 0.0) (e.invested ?: 0.0) / e.buyPrice else 0.0
            cum += bought
            cumInv += e.invested ?: 0.0
            val value = cum * e.buyPrice
            val avg = if (cum > 0.0) cumInv / cum else 0.0
            out.add(
                Row(
                    entry = e,
                    bought = bought,
                    cumAmount = cum,
                    investedCum = cumInv,
                    valueAtBuy = value,
                    pnlAtBuy = if (e.buyPrice > 0.0) value - cumInv else 0.0,
                    avgAtBuy = avg,
                )
            )
        }
        return out
    }

    fun totals(entries: List<Entry>, currentPrice: Double?): Totals {
        var invested = 0.0
        var amount = 0.0
        for (e in entries) {
            invested += e.invested ?: 0.0
            if (e.buyPrice > 0.0) amount += (e.invested ?: 0.0) / e.buyPrice
        }
        val price = currentPrice ?: entries.lastOrNull { it.buyPrice > 0.0 }?.buyPrice ?: 0.0
        val value = amount * price
        val pnl = value - invested
        val pct = if (invested != 0.0) pnl / invested * 100.0 else 0.0
        val avg = if (amount > 0.0) invested / amount else 0.0
        return Totals(invested, amount, avg, value, pnl, pct)
    }

    /** Точка графика: дата, накопленное количество и PnL на момент записи. */
    data class Point(val date: LocalDate?, val amount: Double, val pnl: Double, val value: Double, val invested: Double)

    fun points(entries: List<Entry>): List<Point> =
        rows(entries).map { r -> Point(r.entry.date, r.cumAmount, r.pnlAtBuy, r.valueAtBuy, r.investedCum) }

    /** Итоги по всему портфелю: суммируем текущую стоимость активов с живыми ценами. */
    data class PortfolioTotals(
        val invested: Double,
        val value: Double,
        val pnl: Double,
        val pnlPct: Double,
    )

    fun portfolioTotals(assets: List<Asset>, prices: Map<String, Double>): PortfolioTotals {
        var invested = 0.0
        var value = 0.0
        for (a in assets) {
            val t = totals(a.entries, prices[a.symbol])
            invested += t.invested
            value += t.marketValue
        }
        val pnl = value - invested
        val pct = if (invested != 0.0) pnl / invested * 100.0 else 0.0
        return PortfolioTotals(invested, value, pnl, pct)
    }
}
