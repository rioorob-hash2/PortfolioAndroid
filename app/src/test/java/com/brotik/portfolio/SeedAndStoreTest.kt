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
        assertEquals(1, loaded!!.assets.size)
        assertNotNull(loaded.asset("DOGE"))
        assertEquals("#C2A633", loaded.asset("DOGE")!!.color)
        assertEquals(0, loaded.asset("DOGE")!!.entries.size)
    }

    @Test
    fun `removing an asset drops it from storage`() {
        store.save(TestFixture.portfolio())
        val p = store.load()!!
        p.assets.removeAll { it.symbol == "ARB" }
        store.save(p)

        assertNull(store.load()!!.asset("ARB"))
        assertEquals(1, store.load()!!.assets.size)
    }

    @Test
    fun `public seed has no personal assets`() {
        val p = store.seed()
        assertNotNull(p)
        // В публичной сборке личных данных в assets нет — пользователь начинает с пустого портфеля.
        assertEquals(0, p!!.assets.size)
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
        val p = TestFixture.portfolio()
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
