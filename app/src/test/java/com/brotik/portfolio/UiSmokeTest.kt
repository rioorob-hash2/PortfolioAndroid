package com.brotik.portfolio

import android.content.Intent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.brotik.portfolio.ui.CoinActivity
import com.brotik.portfolio.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class UiSmokeTest {

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<App>()
        // Цены в тестах не тянем из сети: портфель считается по ценам покупки.
        app.priceFetcher = { emptyMap() }
        app.candleFetcher = { _, _ -> emptyList() }
        app.historyFetcher = { _, _ -> emptyMap() }
        // Сид в публичной сборке пустой — данные для UI готовим сами.
        app.store.save(TestFixture.portfolio())
    }

    @Test
    fun emptyPortfolioShowsEmptyState() {
        val app = ApplicationProvider.getApplicationContext<App>()
        java.io.File(app.filesDir, "portfolio.json").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val empty = a.findViewById<android.view.View>(R.id.emptyState)
                val list = a.findViewById<android.view.View>(R.id.list)
                assertEquals(android.view.View.VISIBLE, empty.visibility)
                assertEquals(android.view.View.GONE, list.visibility)
            }
        }
    }

    @Test
    fun mainScreenShowsPortfolio() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val total = a.findViewById<TextView>(R.id.totalValue)
                assertNotNull(total)
                assertTrue("сумма должна быть в долларах: ${total.text}", total.text.toString().startsWith("$"))

                val invested = a.findViewById<TextView>(R.id.investedValue)
                assertEquals(
                    "$1 500,00",
                    invested.text.toString().replace('\u00A0', ' ').replace('\u202F', ' '),
                )

                val list = a.findViewById<RecyclerView>(R.id.list)
                assertNotNull(list)
                // 2 карточки-заголовка (итоги с историей + распределение) + 2 актива из фикстуры
                assertEquals(4, list.adapter!!.itemCount)
            }
        }
    }

    @Test
    fun coinScreenOpensBtc() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            CoinActivity::class.java,
        ).putExtra(CoinActivity.EXTRA_SYMBOL, "BTC")
        ActivityScenario.launch<CoinActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                val price = a.findViewById<TextView>(R.id.priceNow)
                assertTrue("цена должна быть заполнена: ${price.text}", price.text.toString().startsWith("$"))
                val entries = a.findViewById<RecyclerView>(R.id.entries)
                assertNotNull(entries.adapter)
                assertEquals(2, entries.adapter!!.itemCount)
            }
        }
    }

    @Test
    fun unknownSymbolFinishes() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            CoinActivity::class.java,
        ).putExtra(CoinActivity.EXTRA_SYMBOL, "NOPE")
        val activity = org.robolectric.Robolectric.buildActivity(CoinActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
    }
}
