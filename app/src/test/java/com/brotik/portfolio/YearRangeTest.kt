package com.brotik.portfolio

import com.brotik.portfolio.core.CandleRange
import com.brotik.portfolio.core.HistoryCalc
import com.brotik.portfolio.core.HistoryRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Период «Год»: недельные свечи истории и графика цены, подписи оси. */
class YearRangeTest {

    private val zone = ZoneId.systemDefault()

    @Test
    fun `year history uses weekly candles`() {
        assertEquals("W", HistoryRange.YEAR.bybitInterval)
        assertEquals(52, HistoryRange.YEAR.limit)
        assertEquals("365", HistoryRange.YEAR.coingeckoDays)
    }

    @Test
    fun `year candles use weekly interval`() {
        assertEquals("W", CandleRange.YEAR.bybitInterval)
        assertEquals(52, CandleRange.YEAR.limit)
        assertEquals("365", CandleRange.YEAR.coingeckoDays)
    }

    @Test
    fun `year axis labels show month and year`() {
        val times = (0..51L).map { 1_780_000_000_000L + it * 7L * 86_400_000L }
        val labels = HistoryCalc.axisLabels(times, HistoryRange.YEAR)
        assertEquals(4, labels.size)
        val fmt = DateTimeFormatter.ofPattern("MMM yy")
        assertEquals(Instant.ofEpochMilli(times.first()).atZone(zone).format(fmt), labels.first())
        assertEquals(Instant.ofEpochMilli(times.last()).atZone(zone).format(fmt), labels.last())
    }

    @Test
    fun `short ranges keep day labels`() {
        val times = (1..30L).map { it * 86_400_000L }
        val labels = HistoryCalc.axisLabels(times, HistoryRange.D30)
        assertTrue(labels.first().isNotBlank())
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        assertEquals(Instant.ofEpochMilli(times.first()).atZone(zone).format(fmt), labels.first())
    }
}
