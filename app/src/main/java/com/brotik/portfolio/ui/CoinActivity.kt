package com.brotik.portfolio.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
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
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Candle
import com.brotik.portfolio.core.CandleRange
import com.brotik.portfolio.core.Entry
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.databinding.ActivityCoinBinding
import com.brotik.portfolio.databinding.DialogAddEntryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CoinActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    private enum class ChartMode { VALUE, PNL, AMOUNT }

    private lateinit var b: ActivityCoinBinding
    private lateinit var entriesAdapter: EntriesAdapter

    private var portfolio: Portfolio? = null
    private var asset: Asset? = null
    private val prices = HashMap<String, Double>()
    private var chartMode = ChartMode.VALUE
    private var updatedAt = 0L
    private var candleRange = CandleRange.DAY
    private val candlesCache = HashMap<CandleRange, List<Candle>>()
    private var candlesLoading = false

    private val app: App get() = App.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCoinBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()

        val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: run {
            finish()
            return
        }
        portfolio = app.store.loadOrSeed()
        asset = portfolio?.asset(symbol)
        if (asset == null) {
            finish()
            return
        }
        prices.putAll(app.prices.cached())
        updatedAt = app.prices.lastUpdateAt()

        setupToolbar()
        setupList()
        setupChips()
        setupCandleChips()
        b.fab.setOnClickListener { showEntryDialog(null) }

        render()
        loadCandles()
        refreshIfStale()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        val a = asset ?: return
        b.toolbar.title = "${a.symbol} · ${a.title}"
        b.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupList() {
        entriesAdapter = EntriesAdapter(asset?.symbol.orEmpty()) { row -> showEntryActions(row) }
        b.entries.layoutManager = LinearLayoutManager(this)
        b.entries.adapter = entriesAdapter
        b.entries.itemAnimator = null
    }

    private fun setupChips() {
        b.chips.setOnCheckedStateChangeListener { _, checkedIds ->
            chartMode = when (checkedIds.firstOrNull()) {
                R.id.chipPnl -> ChartMode.PNL
                R.id.chipAmount -> ChartMode.AMOUNT
                else -> ChartMode.VALUE
            }
            renderChart()
        }
    }

    private fun setupCandleChips() {
        b.candleChips.setOnCheckedStateChangeListener { _, checkedIds ->
            candleRange = when (checkedIds.firstOrNull()) {
                R.id.chipWeek -> CandleRange.WEEK
                R.id.chipMonth -> CandleRange.MONTH
                R.id.chipYear -> CandleRange.YEAR
                else -> CandleRange.DAY
            }
            loadCandles()
        }
    }

    private fun loadCandles(force: Boolean = false) {
        val a = asset ?: return
        if (!force) {
            candlesCache[candleRange]?.let { renderCandles(it); return }
        }
        if (candlesLoading) return
        candlesLoading = true
        b.candleHint.text = getString(R.string.candles_loading)
        val requested = candleRange
        lifecycleScope.launch {
            val result = runCatching { app.candleFetcher(a.symbol, requested) }.getOrDefault(emptyList())
            candlesCache[requested] = result
            candlesLoading = false
            if (requested == candleRange) renderCandles(result)
        }
    }

    private fun renderCandles(candles: List<Candle>) {
        val a = asset ?: return
        val t = Calc.totals(a.entries, prices[a.symbol])
        val avg = if (t.amount > 0.0) t.avgPrice else null
        val mode = when (candleRange) {
            CandleRange.DAY -> CandleChartView.Mode.DAY
            CandleRange.WEEK -> CandleChartView.Mode.WEEK
            CandleRange.MONTH -> CandleChartView.Mode.MONTH
            CandleRange.YEAR -> CandleChartView.Mode.YEAR
        }
        b.candleChart.setData(candles, avg, mode)
        b.candleHint.text = when {
            t.amount <= 0.0 -> getString(R.string.candles_no_avg)
            candles.isEmpty() -> getString(R.string.candles_empty)
            else -> getString(R.string.candle_avg, Fmt.price(t.avgPrice), Fmt.amountShort(t.amount), a.symbol)
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val a = asset ?: return
        val t = Calc.totals(a.entries, prices[a.symbol])
        val priceNow = prices[a.symbol]
            ?: a.entries.lastOrNull { it.buyPrice > 0.0 }?.buyPrice
            ?: 0.0

        b.priceNow.text = if (priceNow > 0.0) Fmt.price(priceNow) else "—"
        b.value.text = Fmt.money(t.marketValue)
        b.pnl.text = Fmt.moneySigned(t.pnl)
        b.pnl.setTextColor(
            ContextCompat.getColor(this, if (t.pnl >= 0) R.color.up else R.color.down)
        )
        b.invested.text = Fmt.money(t.invested)
        b.avgPrice.text = if (t.amount > 0.0) Fmt.price(t.avgPrice) else "—"
        b.amountRow.text = if (t.amount > 0.0) {
            "Количество: ${Fmt.amount(t.amount)} ${a.symbol} · PnL ${Fmt.pct(t.pnlPct)}"
        } else {
            "Пока ни одной покупки"
        }

        val rows = Calc.rows(a.entries)
        entriesAdapter.submit(rows)
        b.emptyHint.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        bindTarget(a)
        renderChart()
        renderCandles(candlesCache[candleRange].orEmpty())
    }

    /** Строка «Цель по цене» — по нажатию задаём/убираем targetPrice монеты. */
    private fun bindTarget(a: Asset) {
        val target = a.targetPrice?.takeIf { it > 0.0 }
        b.targetRow.text = if (target == null) {
            getString(R.string.target_none)
        } else {
            getString(R.string.target_set, Fmt.price(target))
        }
        b.targetRow.setOnClickListener { showTargetDialog(a) }
    }

    private fun showTargetDialog(a: Asset) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.target_dialog_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            a.targetPrice?.let { setText(plain(it)) }
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.target_title)
            .setView(input)
            .setNeutralButton(R.string.target_clear, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = parseNum(input.text?.toString())
                a.targetPrice = value?.takeIf { it > 0.0 }
                app.store.save(portfolio ?: return@setPositiveButton)
                backupAfterTargetChange()
                render()
                toast(getString(if (a.targetPrice == null) R.string.target_removed else R.string.target_saved))
            }
            .show()
            .also { dialog ->
                // Нейтральная кнопка вешается после show() — иначе диалог закрывается сразу.
                (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(
                    android.app.AlertDialog.BUTTON_NEUTRAL
                )?.setOnClickListener {
                    a.targetPrice = null
                    app.store.save(portfolio ?: return@setOnClickListener)
                    backupAfterTargetChange()
                    render()
                    toast(getString(R.string.target_removed))
                    dialog.dismiss()
                }
            }
    }

    /** Цель тоже меняет данные — копию обновляем, чтобы бэкап не отставал. */
    private fun backupAfterTargetChange() {
        val p = portfolio ?: return
        if (!app.backup.autoBackup()) return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { app.backup.backup(p) } }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderChart() {
        val a = asset ?: return
        val rows = Calc.rows(a.entries)
        if (rows.isEmpty()) {
            b.chart.setData(emptyList())
            b.chartHint.text = ""
            return
        }
        val upColor = ContextCompat.getColor(this, R.color.up)
        val downColor = ContextCompat.getColor(this, R.color.down)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim = ContextCompat.getColor(this, R.color.text_faint)

        val labels = LineChartView.Labels(
            first = Fmt.date(rows.first().entry.date),
            last = Fmt.date(rows.last().entry.date),
            yFormat = { v ->
                when (chartMode) {
                    ChartMode.AMOUNT -> Fmt.amount(v)
                    else -> Fmt.money(v)
                }
            },
        )

        val series = when (chartMode) {
            ChartMode.VALUE -> listOf(
                LineChartView.Series(rows.map { it.valueAtBuy }, accent, fill = true),
                LineChartView.Series(rows.map { it.investedCum }, dim, dashed = true, fill = false),
            )
            ChartMode.PNL -> {
                val last = rows.last().pnlAtBuy
                listOf(LineChartView.Series(rows.map { it.pnlAtBuy }, if (last >= 0) upColor else downColor, fill = true))
            }
            ChartMode.AMOUNT -> listOf(LineChartView.Series(rows.map { it.cumAmount }, accent, fill = true))
        }
        b.chart.setData(series, labels)

        val t = Calc.totals(a.entries, prices[a.symbol])
        b.chartHint.text = when (chartMode) {
            ChartMode.VALUE -> "Стоимость на момент покупки · пунктир — вложено · сейчас: ${Fmt.money(t.marketValue)}"
            ChartMode.PNL -> "PnL сейчас: ${Fmt.moneySigned(t.pnl)} (${Fmt.pct(t.pnlPct)})"
            ChartMode.AMOUNT -> "Всего: ${Fmt.amount(t.amount)} ${a.symbol}"
        }
    }

    // ---------------------------------------------------------------- dialog

    private fun showEntryDialog(existing: Entry?) {
        val a = asset ?: return
        val d = DialogAddEntryBinding.inflate(LayoutInflater.from(this))
        var date: LocalDate? = existing?.date ?: LocalDate.now()
        val dateBounds = existing?.dateRaw.orEmpty()

        fun updateDateText() {
            d.dateInput.setText(date?.format(DMY) ?: "")
        }
        updateDateText()

        d.dateInput.setOnClickListener {
            val base = date ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, day ->
                date = LocalDate.of(y, m + 1, day)
                updateDateText()
            }, base.year, base.monthValue - 1, base.dayOfMonth).show()
        }

        val wasSell = (existing?.invested ?: 0.0) < 0.0
        d.kindGroup.check(if (wasSell) R.id.kindSell else R.id.kindBuy)
        fun updateSumHint(sell: Boolean) {
            d.sumWrap.hint = getString(if (sell) R.string.sum_sell_usd else R.string.sum_usd)
            d.priceWrap.hint = getString(if (sell) R.string.price_sell_usd else R.string.price_usd)
        }
        updateSumHint(wasSell)
        d.kindGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) updateSumHint(checkedId == R.id.kindSell)
        }

        if (existing != null) {
            d.sumInput.setText(if (existing.invested != null) plain(Math.abs(existing.invested!!)) else "")
            d.priceInput.setText(plain(existing.buyPrice))
            d.markSwitch.isChecked = existing.isMark
            d.sumWrap.isEnabled = !existing.isMark
            d.sumInput.isEnabled = !existing.isMark
        } else {
            prices[a.symbol]?.let { d.priceInput.setText(plain(it)) }
        }
        d.markSwitch.setOnCheckedChangeListener { _, checked ->
            d.sumWrap.isEnabled = !checked
            d.sumInput.isEnabled = !checked
            if (checked) d.sumInput.setText("")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_purchase else R.string.edit_purchase)
            .setView(d.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.show()

        // Обработчик вешаем после show(): так диалог работает и в Robolectric-тестах.
        val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        if (positive != null) {
            positive.text = getString(R.string.save)
            positive.setOnClickListener {
                val mark = d.markSwitch.isChecked
                val price = parseNum(d.priceInput.text?.toString())
                if (price == null || price <= 0.0) {
                    d.priceWrap.error = getString(R.string.price_must_be_positive)
                    return@setOnClickListener
                }
                d.priceWrap.error = null
                val sell = d.kindGroup.checkedButtonId == R.id.kindSell
                var invested: Double? = null
                if (!mark) {
                    val sum = parseNum(d.sumInput.text?.toString())
                    if (sum == null || sum <= 0.0) {
                        d.sumWrap.error = getString(R.string.field_required)
                        return@setOnClickListener
                    }
                    d.sumWrap.error = null
                    invested = if (sell) -Math.abs(sum) else Math.abs(sum)
                }
                val e = existing?.copy() ?: Entry()
                e.date = date
                e.dateRaw = if (date != null) date!!.format(DMY) else dateBounds
                e.ambiguousDate = false
                e.buyPrice = price
                e.invested = invested
                a.entries.removeAll { it.id == e.id }
                a.entries.add(e)
                dialog.dismiss()
                saveAndBackup()
                toast(getString(R.string.saved))
            }
        }
    }

    private fun showEntryActions(row: Calc.Row) {
        val a = asset ?: return
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.edit_purchase), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    showEntryDialog(row.entry)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.delete) + "?")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            a.entries.removeAll { it.id == row.entry.id }
                            saveAndBackup()
                            toast(getString(R.string.deleted))
                        }
                        .show()
                }
            }
            .show()
    }

    private fun saveAndBackup() {
        val p = portfolio ?: return
        app.store.save(p)
        render()
        // Копия в Загрузках пишется и без SAF-папки; диск — только в фоне.
        if (app.backup.autoBackup()) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { app.backup.backup(p) } }
        }
    }

    private fun refreshIfStale() {
        if (System.currentTimeMillis() - updatedAt < 5 * 60_000L) return
        lifecycleScope.launch {
            runCatching {
                val fresh = app.priceFetcher(listOf(asset?.symbol.orEmpty()))
                prices.putAll(fresh)
                updatedAt = System.currentTimeMillis()
                render()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseNum(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim().replace(" ", "").replace('\u00A0', ' ').replace(',', '.')
        return cleaned.toDoubleOrNull()
    }

    private fun plain(v: Double): String {
        val format = java.text.DecimalFormat("0.##########", java.text.DecimalFormatSymbols(Locale.US))
        return format.format(v)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
