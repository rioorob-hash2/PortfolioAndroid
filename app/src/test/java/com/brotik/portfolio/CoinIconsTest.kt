package com.brotik.portfolio

import com.brotik.portfolio.ui.CoinIcons
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Иконки монет: набор из res/drawable-nodpi должен резолвиться по тикеру. */
class CoinIconsTest {

    @Test
    fun seededSymbolsHaveIcons() {
        listOf("BTC", "ETH", "SOL", "LTC", "TON", "OP", "ARB").forEach { symbol ->
            assertNotNull("нет иконки для $symbol", CoinIcons.drawableFor(symbol))
        }
    }

    @Test
    fun symbolLookupIsCaseInsensitive() {
        assertNotNull(CoinIcons.drawableFor("btc"))
        assertNotNull(CoinIcons.drawableFor(" eth "))
    }

    @Test
    fun unknownSymbolFallsBackToLetterBadge() {
        assertNull(CoinIcons.drawableFor("HYPE"))
    }
}
