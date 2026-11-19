package com.brotik.portfolio.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.brotik.portfolio.App
import com.brotik.portfolio.R
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.HistoryCalc
import com.brotik.portfolio.core.HistoryRange
import com.brotik.portfolio.core.HistorySeries
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.data.RestorePick
import com.brotik.portfolio.databinding.ActivityMainBinding
import com.brotik.portfolio.databinding.DialogAddAssetBinding
import com.brotik.portfolio.widget.WidgetRenderer
import com.brotik.portfolio.work.RefreshScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        val PALETTE = listOf(
            "#F7931A", "#627EEA", "#0098EA", "#FF0420", "#14F195", "#28A0F0",
            "#A6A9AA", "#E91E63", "#9C27B0", "#00BCD4", "#8BC34A", "#FFB020",
        )
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MainAdapter

    private var portfolio: Portfolio? = null
    private val prices = HashMap<String, Double>()
    private var loading = false
    private var updatedAt = 0L
    private var historyRange = HistoryRange.H24
    private var historySeries: HistorySeries? = null
    private val historyCache = HashMap<HistoryRange, HistorySeries>()
    private var historyLoading = false
    private var historyFetchedAt = 0L
    private var restoreDialogVisible = false

    private val app: App get() = App.instance

    private val askNotifications =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        applyInsets()

        adapter = MainAdapter(
            onAssetClick = { asset ->
                startActivity(
                    Intent(this, CoinActivity::class.java).putExtra(CoinActivity.EXTRA_SYMBOL, asset.symbol)
                )
            },
            onAssetLongClick = { asset -> showDeleteAssetDialog(asset) },
            onHistoryRange = { range ->
                historyRange = range
                loadHistory()
            },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        b.swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface))
        b.swipe.setOnRefreshListener { refreshPrices(manual = true) }

        loadInitial()
        // Убираем снимки, накопленные старыми версиями: оставляем только portfolio-latest.json.
        lifecycleScope.launch(Dispatchers.IO) { app.backup.cleanupOldSnapshots() }
        if (portfolio != null) loadHistory()
        ensureBackgroundWork()
        requestNotificationsOnce()
    }

    /** Периодическая проверка цен и уведомлений (WorkManager, не чаще 15 минут). */
    private fun ensureBackgroundWork() {
        RefreshScheduler.ensureScheduled(this)
    }

    /** Один раз спрашиваем разрешение на уведомления (Android 13+). */
    private fun requestNotificationsOnce(attempt: Int = 0) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("alerts", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            return
        }
        // Не наезжаем на диалог восстановления: спрашиваем, когда он закрыт.
        b.root.postDelayed({
            if (isFinishing || attempt >= 20) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                return@postDelayed
            }
            if (restoreDialogVisible) {
                requestNotificationsOnce(attempt + 1)
                return@postDelayed
            }
            prefs.edit().putBoolean("asked_notifications", true).apply()
            runCatching { askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }, 2500)
    }

    override fun onResume() {
        super.onResume()
        val current = portfolio ?: return
        // Цена могла протухнуть — обновляем без спроса, если кэшу больше 5 минут.
        if (System.currentTimeMillis() - updatedAt > 5 * 60_000L) refreshPrices(manual = false)
        if (System.currentTimeMillis() - historyFetchedAt > 5 * 60_000L) loadHistory(force = true)
        render(current)
        runCatching { WidgetRenderer.refreshAll(this) }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun loadInitial() {
        updatedAt = app.prices.lastUpdateAt()
        prices.putAll(app.prices.cached())

        val stored = app.store.load()
        if (stored != null) {
            portfolio = stored
            render(stored)
            if (prices.isEmpty()) refreshPrices(manual = false)
            return
        }

        // Внутренних данных нет (переустановка/очистка): показываем таблицу, а копии ищем в фоне.
        fallbackToSeed()
        lifecycleScope.launch {
            val candidate = withContext(Dispatchers.IO) { restoreCandidate() }
            if (candidate != null && !isFinishing) showRestoreDialog(candidate)
        }
    }

    private fun showRestoreDialog(candidate: RestorePick.Candidate) {
        restoreDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_title)
            .setMessage(getString(R.string.restore_found, Fmt.dateTime(candidate.timestamp)))
            .setPositiveButton(R.string.restore_ok) { _, _ ->
                restoreDialogVisible = false
                app.store.save(candidate.portfolio)
                portfolio = candidate.portfolio
                render(candidate.portfolio)
                backupQuietly()
                refreshPrices(manual = false)
                toast(getString(R.string.restore_done))
            }
            .setNegativeButton(R.string.restore_skip) { _, _ -> restoreDialogVisible = false }
            .setOnCancelListener { restoreDialogVisible = false }
            .setCancelable(false)
            .show()
    }

    /** Лучшая из доступных копий: SAF-папка / Загрузки / приватная — по времени изменения. */
    private fun restoreCandidate(): RestorePick.Candidate? {
        val list = ArrayList<RestorePick.Candidate>()
        if (app.backup.hasFolder()) {
            val ts = app.backup.readLatestTimestamp()
            app.backup.readLatest()?.let {
                list.add(RestorePick.Candidate(it, ts, RestorePick.Source.SAF_FOLDER))
            }
        }
        val dlTs = app.backup.downloadLatestTimestamp()
        app.backup.readDownloadLatest()?.let {
            list.add(RestorePick.Candidate(it, dlTs, RestorePick.Source.DOWNLOADS))
        }
        app.backup.readLocalCopy()?.let {
            list.add(RestorePick.Candidate(it, app.backup.localCopyTimestamp(), RestorePick.Source.LOCAL_COPY))
        }
        return RestorePick.best(list)
    }

    private fun fallbackToSeed() {
        val seeded = app.store.loadOrSeed()
        portfolio = seeded
        render(seeded)
        refreshPrices(manual = false)
    }

    private fun render(p: Portfolio) {
        adapter.submit(p, prices, updatedAt, historySeries, historyRange, historyLoading)
    }

    private fun loadHistory(force: Boolean = false) {
        val p = portfolio ?: return
        if (!force) {
            historyCache[historyRange]?.let { cached ->
                historySeries = cached
                render(p)
                return
            }
        }
        if (historyLoading) return
        historyLoading = true
        render(p)
        val requested = historyRange
        lifecycleScope.launch {
            val map = runCatching {
                app.historyFetcher(p.assets.map { it.symbol }, requested)
            }.getOrDefault(emptyMap())
            val series = HistoryCalc.valueSeries(p.assets, map)?.let { base ->
                base.copy(invested = HistoryCalc.investedSeries(p.assets, base.times))
            }
            historyLoading = false
            historyFetchedAt = System.currentTimeMillis()
            if (series != null) historyCache[requested] = series
            if (requested == historyRange) {
                historySeries = series
                render(p)
            }
        }
    }

    private fun refreshPrices(manual: Boolean) {
        val p = portfolio ?: return
        if (loading) return
        loading = true
        if (manual) b.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val fresh = app.priceFetcher(p.assets.map { it.symbol })
                prices.putAll(fresh)
                updatedAt = app.prices.lastUpdateAt()
                render(p)
                if (manual) toast(getString(R.string.updated_at, Fmt.timeOfDay(updatedAt)))
            } catch (e: Exception) {
                if (manual) {
                    toast(getString(R.string.prices_error, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                loading = false
                b.swipe.isRefreshing = false
            }
        }
    }

    private fun backupQuietly() {
        val p = portfolio ?: return
        // Копия в Загрузках пишется и без SAF-папки, поэтому авто-копию не блокируем.
        if (!app.backup.autoBackup()) return
        // SAF/MediaStore — диск: только в фоне, иначе ANR на облачной папке.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { app.backup.backup(p) } }
            runCatching { WidgetRenderer.refreshAll(this@MainActivity) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add_asset -> { showAddAssetDialog(); true }
        R.id.action_refresh -> { refreshPrices(manual = true); true }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- assets

    private fun showAddAssetDialog() {
        val p = portfolio ?: return
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        var selectedColor = 0
        val circles = ArrayList<View>()
        val dp = resources.displayMetrics.density

        fun px(v: Int): Int = (v * dp).toInt()

        fun paintColors() {
            circles.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(PALETTE[i]))
                    if (i == selectedColor) {
                        setStroke(px(3), ContextCompat.getColor(this@MainActivity, R.color.text))
                    } else {
                        setStroke(0, Color.TRANSPARENT)
                    }
                }
            }
        }

        PALETTE.forEachIndexed { index, _ ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(px(34), px(34))
            lp.marginEnd = px(8)
            dot.layoutParams = lp
            dot.setOnClickListener {
                selectedColor = index
                paintColors()
            }
            circles.add(dot)
            d.colorRow.addView(dot)
        }
        paintColors()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_asset)
            .setView(d.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add_asset, null)
            .create()
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val symbol = d.symbolInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
            if (!symbol.matches(Regex("^[A-Z0-9]{2,10}$"))) {
                d.symbolWrap.error = getString(R.string.ticker_invalid)
                return@setOnClickListener
            }
            if (p.asset(symbol) != null) {
                d.symbolWrap.error = getString(R.string.ticker_exists)
                return@setOnClickListener
            }
            d.symbolWrap.error = null
            val title = d.nameInput.text?.toString()?.trim().orEmpty().ifBlank { symbol }
            p.assets.add(Asset(symbol, title, PALETTE[selectedColor]))
            app.store.save(p)
            render(p)
            backupQuietly()
            refreshPrices(manual = false)
            historyCache.clear()
            loadHistory(force = true)
            toast(getString(R.string.asset_added))
            dialog.dismiss()
        }
    }

    private fun showDeleteAssetDialog(asset: Asset) {
        val p = portfolio ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_asset_title, asset.symbol))
            .setMessage(getString(R.string.delete_asset_confirm, asset.symbol))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                p.assets.removeAll { it.symbol == asset.symbol }
                app.store.save(p)
                render(p)
                backupQuietly()
                historyCache.clear()
                loadHistory(force = true)
                toast(getString(R.string.asset_deleted))
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
