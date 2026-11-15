package com.brotik.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Entry
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.Portfolio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class SeedAndStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = App.instance.store

    @Before
    fun resetStore() {
        java.io.File(ctx.filesDir, "portfolio.json").delete()
    }

    @Test
    fun `new asset is added and persists`() {
        val p = store.loadOrSeed()
        p.assets.add(com.brotik.portfolio.core.Asset("DOGE", "Dogecoin", "#C2A633"))
        store.save(p)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(8, loaded!!.assets.size)
        assertNotNull(loaded.asset("DOGE"))
        assertEquals("#C2A633", loaded.asset("DOGE")!!.color)
        assertEquals(0, loaded.asset("DOGE")!!.entries.size)
    }

    @Test
    fun `removing an asset drops it from storage`() {
        val p = store.loadOrSeed()
        p.assets.removeAll { it.symbol == "ARB" }
        store.save(p)

        assertNull(store.load()!!.asset("ARB"))
        assertEquals(6, store.load()!!.assets.size)
    }

    @Test
    fun `seed contains seven assets and real spreadsheet totals`() {
        val p = store.seed()
        assertNotNull(p)
        p!!
        assertEquals(7, p.assets.size)
        assertEquals(
            listOf("BTC", "ETH", "TON", "OP", "SOL", "ARB", "LTC"),
            p.assets.map { it.symbol },
        )

        val btc = p.asset("BTC")!!
        val t = Calc.totals(btc.entries, null)
        assertEquals(1260.0, t.invested, 1e-9)
        assertEquals(0.01775285563, t.amount, 1e-10)

        val expectedAmounts = mapOf(
            "ETH" to 0.1948984917,
            "TON" to 411.1815085555,
            "OP" to 559.4910898832,
            "SOL" to 2.5346695613,
            "LTC" to 4.5273368607,
        )
        expectedAmounts.forEach { (sym, expected) ->
            val asset = p.asset(sym)!!
            assertEquals("$sym amount", expected, Calc.totals(asset.entries, null).amount, 1e-9)
        }
        assertEquals(0, p.asset("ARB")!!.entries.size)
    }

    @Test
    fun `all seeded dates are valid and none is in the future`() {
        val p = store.seed()!!
        val today = LocalDate.now()
        p.assets.forEach { a ->
            a.entries.forEach { e ->
                assertNotNull("${a.symbol}: пустая дата", e.date)
                assertTrue("${a.symbol}: дата в будущем ${e.date}", !e.date!!.isAfter(today))
            }
        }
    }

    @Test
    fun `save and load roundtrip keeps entries`() {
        val p = Portfolio()
        val asset = com.brotik.portfolio.core.Asset("BTC", "Bitcoin", "#F7931A")
        asset.entries.add(Entry(date = LocalDate.parse("2026-01-02"), dateRaw = "02.01.2026", invested = 10.0, buyPrice = 50000.0))
        p.assets.add(asset)
        store.save(p)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.assets.size)
        assertEquals(10.0, loaded.assets[0].entries[0].invested!!, 1e-9)
        assertEquals(LocalDate.parse("2026-01-02"), loaded.assets[0].entries[0].date)
    }

    @Test
    fun `json roundtrip of portfolio`() {
        val p = store.seed()!!
        val json = com.brotik.portfolio.core.EntryJson.toJson(p).toString()
        assertTrue(json.contains("\"assets\""))
        val back = com.brotik.portfolio.core.EntryJson.fromJson(JSONObject(json))
        assertEquals(p.assets.sumOf { it.entries.size }, back.assets.sumOf { it.entries.size })
    }

    @Test
    fun `money formatting is russian style`() {
        fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')
        assertEquals("$1 234,50", norm(Fmt.money(1234.5)))
        assertEquals("+$48,39", norm(Fmt.moneySigned(48.3854)))
        assertEquals("-$57,21", norm(Fmt.moneySigned(-57.2109)))
        assertEquals("+1,7 %", norm(Fmt.pct(1.7)))
        assertEquals("0,01775286", norm(Fmt.amount(0.01775285563)))
    }
}
