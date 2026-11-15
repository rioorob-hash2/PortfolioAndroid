package com.brotik.portfolio

import com.brotik.portfolio.core.CandleParser
import com.brotik.portfolio.core.CandleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Парсинг свечей без сети: формат Bybit v5 и CoinGecko OHLC. */
class CandleParserTest {

    @Test
    fun `bybit klines are parsed and ordered oldest first`() {
        val json = """
            {"retCode":0,"result":{"category":"spot","symbol":"BTCUSDT","list":[
              ["1762905600000","74000","75100","73500","74800","12.5","935000"],
              ["1762819200000","73000","74500","72800","74000","10.1","742000"]
            ]}}
        """.trimIndent()
        val candles = CandleParser.fromBybit(json)
        assertEquals(2, candles.size)
        // в ответе Bybit новые сверху → после парсинга старые первыми
        assertEquals(1762819200000L, candles[0].time)
        assertEquals(73000.0, candles[0].open, 1e-9)
        assertEquals(74800.0, candles[1].close, 1e-9)
    }

    @Test
    fun `bybit garbage rows are skipped`() {
        val json = """
            {"result":{"list":[
              ["1762905600000","74000","75100","73500"],
              ["bad","1","2","3","4"],
              ["1762819200000","73000","74500","72800","74000","1","1"]
            ]}}
        """.trimIndent()
        val candles = CandleParser.fromBybit(json)
        assertEquals(1, candles.size)
        assertEquals(74000.0, candles[0].close, 1e-9)
    }

    @Test
    fun `bybit empty answer gives empty list`() {
        assertTrue(CandleParser.fromBybit("""{"result":{}}""").isEmpty())
        assertTrue(CandleParser.fromBybit("not json").isEmpty())
    }

    @Test
    fun `coingecko ohlc is parsed`() {
        val json = """
            [[1762819200000,73000,74500,72800,74000],
             [1762905600000,74000,75100,73500,74800]]
        """.trimIndent()
        val candles = CandleParser.fromCoinGecko(json)
        assertEquals(2, candles.size)
        assertEquals(73000.0, candles[0].open, 1e-9)
        assertEquals(74800.0, candles[1].close, 1e-9)
    }

    @Test
    fun `ranges map to bybit intervals`() {
        assertEquals("D", CandleRange.DAY.bybitInterval)
        assertEquals("W", CandleRange.WEEK.bybitInterval)
        assertEquals(120, CandleRange.DAY.limit)
        assertEquals(52, CandleRange.WEEK.limit)
        // «Месяц» — 30 дневных свечей
        assertEquals("D", CandleRange.MONTH.bybitInterval)
        assertEquals(30, CandleRange.MONTH.limit)
    }
}
