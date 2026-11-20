package com.brotik.portfolio

import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Entry
import com.brotik.portfolio.core.Portfolio
import java.time.LocalDate

/**
 * Тестовый портфель: приложение стартует пустым (в assets лежит пустой сид),
 * поэтому тесты сами готовят данные, а не полагаются на личные записи.
 */
object TestFixture {

    const val BTC_INVESTED = 1500.0

    fun portfolio(): Portfolio {
        val p = Portfolio()

        val btc = Asset("BTC", "Bitcoin", "#F7931A")
        btc.entries.add(
            Entry(
                date = LocalDate.parse("2026-01-02"),
                dateRaw = "02.01.2026",
                invested = 1000.0,
                buyPrice = 50_000.0,
            ),
        )
        btc.entries.add(
            Entry(
                date = LocalDate.parse("2026-02-02"),
                dateRaw = "02.02.2026",
                invested = 500.0,
                buyPrice = 62_500.0,
            ),
        )
        p.assets.add(btc)

        p.assets.add(Asset("ARB", "Arbitrum", "#28A0F0"))
        return p
    }
}
