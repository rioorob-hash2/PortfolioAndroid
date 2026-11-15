package com.brotik.portfolio

import androidx.test.core.app.ApplicationProvider
import com.brotik.portfolio.core.AlertKind
import com.brotik.portfolio.core.Alerts
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.EntryJson
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.core.PriceAlert
import com.brotik.portfolio.data.AlertPrefs
import com.brotik.portfolio.data.RestorePick
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Совместимость и восстановление: старые JSON без новых полей читаются,
 * из копий выбирается самая свежая, ручная проверка не сдвигает анти-спам.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class RestoreAndCompatTest {

    private fun portfolio(symbol: String): Portfolio = Portfolio(
        mutableListOf(Asset(symbol, symbol, "#8899AA"))
    )

    // ------------------------------------------------------- старый JSON (v1.6)

    @Test
    fun `legacy json without target price is read`() {
        // Такой файл писал «Портфель» 1.6: у актива нет targetPrice.
        val legacy = """
            {"version":1,"savedAt":1794000000000,"assets":[
              {"symbol":"BTC","title":"Bitcoin","color":"#F7931A","entries":[
                {"id":"e1","date":"2026-01-10","dateRaw":"10.01.2026","invested":100.0,
                 "buyPrice":50000.0,"note":null,"ambiguousDate":false}
              ]}
            ]}
        """.trimIndent()
        val p = EntryJson.fromJson(JSONObject(legacy))
        assertEquals(1, p.assets.size)
        assertNull("старый файл не должен получать цель", p.assets[0].targetPrice)
        assertEquals(100.0, p.assets[0].entries[0].invested!!, 1e-9)
    }

    @Test
    fun `null and zero target price are treated as not set`() {
        val json = """
            {"assets":[
              {"symbol":"ETH","title":"Ethereum","color":"#627EEA","targetPrice":null,"entries":[]},
              {"symbol":"TON","title":"Ton","color":"#0098EA","targetPrice":0,"entries":[]}
            ]}
        """.trimIndent()
        val p = EntryJson.fromJson(JSONObject(json))
        assertNull(p.assets[0].targetPrice)
        assertNull(p.assets[1].targetPrice)
    }

    // ------------------------------------------------------------- выбор копии

    @Test
    fun `newest backup wins regardless of source order`() {
        val saf = RestorePick.Candidate(portfolio("BTC"), 1000L, RestorePick.Source.SAF_FOLDER)
        val downloads = RestorePick.Candidate(portfolio("ETH"), 5000L, RestorePick.Source.DOWNLOADS)
        val local = RestorePick.Candidate(portfolio("TON"), 3000L, RestorePick.Source.LOCAL_COPY)
        // Не первый в списке, а самый свежий.
        assertEquals("ETH", RestorePick.best(listOf(saf, downloads, local))!!.portfolio.assets[0].symbol)
    }

    @Test
    fun `at equal time saf folder is preferred`() {
        val saf = RestorePick.Candidate(portfolio("BTC"), 1000L, RestorePick.Source.SAF_FOLDER)
        val local = RestorePick.Candidate(portfolio("TON"), 1000L, RestorePick.Source.LOCAL_COPY)
        assertEquals(RestorePick.Source.SAF_FOLDER, RestorePick.best(listOf(local, saf))!!.source)
    }

    @Test
    fun `empty candidates are ignored`() {
        val empty = RestorePick.Candidate(Portfolio(), 9000L, RestorePick.Source.DOWNLOADS)
        val good = RestorePick.Candidate(portfolio("BTC"), 1000L, RestorePick.Source.LOCAL_COPY)
        assertEquals(RestorePick.Source.LOCAL_COPY, RestorePick.best(listOf(empty, good))!!.source)
        assertNull(RestorePick.best(emptyList()))
        assertNull(RestorePick.best(listOf(empty)))
    }

    // ------------------------------------------------- анти-спам ручной проверки

    @Test
    fun `manual check does not move existing mark`() {
        val alertPrefs = AlertPrefs(ApplicationProvider.getApplicationContext())
        alertPrefs.clearThrottle()
        val alert = PriceAlert(
            kind = AlertKind.BELOW_AVG,
            symbol = "BTC", price = 90.0, avgPrice = 100.0, dropPct = 10.0,
        )
        alertPrefs.record(listOf(alert), now = 1000L)
        alertPrefs.recordIfNew(listOf(alert), now = 2000L)
        assertEquals(1000L, alertPrefs.lastSent()[alert.key])
        alertPrefs.clearThrottle()

        // Новая пара — метка ставится.
        val other = alert.copy(symbol = "ETH")
        alertPrefs.recordIfNew(listOf(other), now = 3000L)
        assertEquals(3000L, alertPrefs.lastSent()[other.key])
        alertPrefs.clearThrottle()
    }

    @Test
    fun `threshold setting is clamped on save`() {
        val alertPrefs = AlertPrefs(ApplicationProvider.getApplicationContext())
        alertPrefs.setThreshold(250.0)
        assertEquals(90.0, alertPrefs.settings().thresholdPct, 1e-9)
        alertPrefs.setThreshold(0.1)
        assertEquals(0.5, alertPrefs.settings().thresholdPct, 1e-9)
        alertPrefs.setThreshold(Alerts.DEFAULT_THRESHOLD_PCT)
        assertEquals(Alerts.DEFAULT_THRESHOLD_PCT, alertPrefs.settings().thresholdPct, 1e-9)
    }

    @Test
    fun `entry dates parse from legacy raw strings`() {
        val text = """
            {"assets":[{"symbol":"SOL","title":"Solana","color":"#14F195","entries":[
              {"id":"x","date":null,"dateRaw":"102.26","invested":null,"buyPrice":150.0}
            ]}]}
        """.trimIndent()
        val p = EntryJson.fromJson(JSONObject(text))
        val e = p.assets[0].entries[0]
        assertNull(e.date)
        assertEquals("102.26", e.dateRaw)
        assertEquals(LocalDate.MAX, e.date ?: LocalDate.MAX)
    }
}
