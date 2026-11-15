package com.brotik.portfolio

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Entry
import com.brotik.portfolio.core.EntryJson
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.data.DownloadBackups
import com.brotik.portfolio.widget.WidgetRenderer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Виджет, цель цены в JSON и копии в Загрузках — без реального устройства. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class WidgetAndRestoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        java.io.File(ctx.filesDir, "portfolio.json").delete()
    }

    private fun portfolio(): Portfolio {
        val btc = Asset("BTC", "Bitcoin", "#F7931A").apply {
            entries.add(
                Entry(
                    date = LocalDate.parse("2026-01-10"),
                    dateRaw = "10.01.2026",
                    invested = 100.0,
                    buyPrice = 100.0,
                )
            )
        }
        return Portfolio(mutableListOf(btc))
    }

    private fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test
    fun `widget shows portfolio value and pnl`() {
        val views = WidgetRenderer.buildViews(ctx, portfolio(), mapOf("BTC" to 120.0), 1_700_000_000_000L)
        val root = views.apply(ctx, FrameLayout(ctx))
        val value = root.findViewById<TextView>(R.id.widgetValue)
        val pnl = root.findViewById<TextView>(R.id.widgetPnl)
        val updated = root.findViewById<TextView>(R.id.widgetUpdated)
        assertEquals("$120,00", norm(value.text.toString()))
        assertEquals("+$20,00 +20 %", norm(pnl.text.toString()))
        assertTrue("время цен должно быть в виджете: ${updated.text}", updated.text.contains(":"))
    }

    @Test
    fun `widget without prices shows dash`() {
        val views = WidgetRenderer.buildViews(ctx, portfolio(), emptyMap(), 0L)
        val root = views.apply(ctx, FrameLayout(ctx))
        val value = root.findViewById<TextView>(R.id.widgetValue)
        val updated = root.findViewById<TextView>(R.id.widgetUpdated)
        assertEquals("—", value.text.toString())
        assertEquals(ctx.getString(R.string.widget_no_data), updated.text.toString())
    }

    @Test
    fun `widget pnl is red for losses`() {
        val views = WidgetRenderer.buildViews(ctx, portfolio(), mapOf("BTC" to 80.0), 10L)
        val root = views.apply(ctx, FrameLayout(ctx))
        val pnl = root.findViewById<TextView>(R.id.widgetPnl)
        assertEquals("-$20,00 -20 %", norm(pnl.text.toString()))
        assertEquals(
            androidx.core.content.ContextCompat.getColor(ctx, R.color.down),
            pnl.currentTextColor,
        )
    }

    @Test
    fun `target price survives json roundtrip`() {
        val p = portfolio()
        p.assets[0].targetPrice = 150_000.0
        val back = EntryJson.fromJson(JSONObject(EntryJson.toJson(p).toString()))
        assertEquals(150_000.0, back.assets[0].targetPrice!!, 1e-9)

        p.assets[0].targetPrice = null
        val cleared = EntryJson.fromJson(JSONObject(EntryJson.toJson(p).toString()))
        assertNull(cleared.assets[0].targetPrice)
    }

    @Test
    fun `backup names are filtered`() {
        assertTrue(DownloadBackups.isBackupName("portfolio-latest.json"))
        assertTrue(DownloadBackups.isBackupName("portfolio-2026.11.13-19-00-00.json"))
        assertFalse(DownloadBackups.isBackupName("notes.json"))
        assertFalse(DownloadBackups.isBackupName("portfolio.json"))
        assertFalse(DownloadBackups.isBackupName(null))
        assertFalse(DownloadBackups.isBackupName(""))
    }

    @Test
    fun `newest download copy is chosen`() {
        val old = DownloadBackups.StoredBackup(Uri.parse("content://x/1"), "portfolio-old.json", 100L, 10L)
        val latest = DownloadBackups.StoredBackup(Uri.parse("content://x/2"), DownloadBackups.LATEST, 200L, 10L)
        val mid = DownloadBackups.StoredBackup(Uri.parse("content://x/3"), "portfolio-mid.json", 150L, 10L)
        assertEquals(latest, DownloadBackups.newest(listOf(old, latest, mid)))
        // При равном времени «latest» приоритетнее снимка.
        val same = latest.copy(modifiedAt = 150L)
        assertEquals(latest.name, DownloadBackups.newest(listOf(mid, same))!!.name)
        assertNull(DownloadBackups.newest(emptyList()))
    }

    @Test
    fun `backup writes private copy even without saf folder`() {
        val p = portfolio()
        val result = App.instance.backup.backup(p)
        assertTrue("бэкап не должен падать: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(App.instance.backup.localCopyExists())
        val restored = App.instance.backup.readLocalCopy()
        assertNotNull(restored)
        assertEquals(1, restored!!.assets.size)
        assertEquals(1, restored.assets[0].entries.size)
    }

    @Test
    fun `portfolio totals used by widget match calc`() {
        val t = Calc.portfolioTotals(portfolio().assets, mapOf("BTC" to 120.0))
        assertEquals(120.0, t.value, 1e-9)
        assertEquals(20.0, t.pnl, 1e-9)
        assertEquals(20.0, t.pnlPct, 1e-9)
    }

    @Test
    fun `refreshWithoutWidgetsDoesNotCrash`() {
        assertEquals(0, WidgetRenderer.widgetIds(ctx).size)
        WidgetRenderer.refreshAll(ctx) // без виджетов на столе — просто ничего не делает
    }
}
