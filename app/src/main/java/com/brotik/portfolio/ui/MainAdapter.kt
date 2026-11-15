package com.brotik.portfolio.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.brotik.portfolio.R
import com.brotik.portfolio.core.Asset
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.core.HistoryCalc
import com.brotik.portfolio.core.HistoryRange
import com.brotik.portfolio.core.HistorySeries
import com.brotik.portfolio.core.Portfolio
import com.brotik.portfolio.databinding.ItemAssetBinding
import com.brotik.portfolio.databinding.ItemDistributionBinding
import com.brotik.portfolio.databinding.ItemHeaderBinding
import com.brotik.portfolio.databinding.ItemLegendBinding

class MainAdapter(
    private val onAssetClick: (Asset) -> Unit,
    private val onAssetLongClick: (Asset) -> Unit = {},
    private val onHistoryRange: (HistoryRange) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_DISTRIBUTION = 1
        private const val TYPE_ASSET = 2
    }

    private var portfolio: Portfolio? = null
    private var prices: Map<String, Double> = emptyMap()
    private var totals: Calc.PortfolioTotals = Calc.PortfolioTotals(0.0, 0.0, 0.0, 0.0)
    private var updatedAt: Long = 0L
    private var history: HistorySeries? = null
    private var historyRange: HistoryRange = HistoryRange.H24
    private var historyLoading: Boolean = false

    private val assets: List<Asset>
        get() = portfolio?.assets.orEmpty()

    fun submit(
        p: Portfolio,
        prices: Map<String, Double>,
        updatedAt: Long,
        history: HistorySeries? = null,
        range: HistoryRange = this.historyRange,
        loading: Boolean = false,
    ) {
        this.portfolio = p
        this.prices = prices
        this.totals = Calc.portfolioTotals(p.assets, prices)
        this.updatedAt = updatedAt
        this.history = history
        this.historyRange = range
        this.historyLoading = loading
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (position) {
        0 -> TYPE_HEADER
        1 -> TYPE_DISTRIBUTION
        else -> TYPE_ASSET
    }

    override fun getItemCount(): Int = if (portfolio == null) 0 else 2 + assets.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
            TYPE_DISTRIBUTION -> DistributionVH(ItemDistributionBinding.inflate(inflater, parent, false))
            else -> AssetVH(ItemAssetBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> holder.bind()
            is DistributionVH -> holder.bind()
            is AssetVH -> holder.bind(assets[position - 2])
        }
    }

    private fun colorOf(symbol: String): Int =
        portfolio?.asset(symbol)?.colorInt ?: 0xFF8899AA.toInt()

    private fun badgeTextColor(color: Int): Int {
        val lum = Color.luminance(color)
        return if (lum > 0.45f) Color.BLACK else Color.WHITE
    }

    private inner class HeaderVH(private val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            val t = totals
            b.totalValue.text = Fmt.money(t.value)
            b.pnlValue.text = Fmt.moneySigned(t.pnl)
            b.pnlPct.text = Fmt.pct(t.pnlPct)
            val up = t.pnl >= 0
            val color = ContextCompat.getColor(b.root.context, if (up) R.color.up else R.color.down)
            b.pnlValue.setTextColor(color)
            b.pnlPct.setTextColor(color)
            b.investedValue.text = Fmt.money(t.invested)
            val active = assets.count { it.entries.isNotEmpty() }
            b.assetsCount.text = active.toString()
            b.priceUpdated.text = if (updatedAt > 0) {
                b.root.context.getString(R.string.updated_short, Fmt.timeOfDay(updatedAt))
            } else ""
            bindHistory()
        }

        private fun bindHistory() {
            val ctx = b.root.context
            val chipId = when (historyRange) {
                HistoryRange.H24 -> R.id.chipH24
                HistoryRange.D7 -> R.id.chipD7
                HistoryRange.D30 -> R.id.chipD30
                HistoryRange.D90 -> R.id.chipD90
                HistoryRange.YEAR -> R.id.chipYear
            }
            b.historyChips.setOnCheckedStateChangeListener(null)
            b.historyChips.check(chipId)
            b.historyChips.setOnCheckedStateChangeListener { _, ids ->
                val range = when (ids.firstOrNull()) {
                    R.id.chipD7 -> HistoryRange.D7
                    R.id.chipD30 -> HistoryRange.D30
                    R.id.chipD90 -> HistoryRange.D90
                    R.id.chipYear -> HistoryRange.YEAR
                    else -> HistoryRange.H24
                }
                if (range != historyRange) onHistoryRange(range)
            }

            val series = history
            if (series == null || series.values.size < 2) {
                b.historyChart.setData(emptyList())
                b.historyHint.text = if (historyLoading) {
                    ctx.getString(R.string.history_loading)
                } else {
                    ctx.getString(R.string.history_empty)
                }
                return
            }

            val first = series.values.first()
            val last = series.values.last()
            val up = last >= first
            val chartColor = ContextCompat.getColor(ctx, if (up) R.color.up else R.color.down)
            b.historyChart.setData(
                listOf(LineChartView.Series(series.values, chartColor, fill = true)),
                LineChartView.Labels(
                    yFormat = { Fmt.money(it) },
                    xLabels = HistoryCalc.axisLabels(series.times, historyRange),
                ),
            )
            val pct = if (first > 0.0) (last - first) / first * 100.0 else 0.0
            b.historyHint.text = ctx.getString(
                R.string.history_hint,
                periodLabel(ctx),
                Fmt.pct(pct),
            )
        }

        private fun periodLabel(ctx: android.content.Context): String = ctx.getString(
            when (historyRange) {
                HistoryRange.H24 -> R.string.history_24h
                HistoryRange.D7 -> R.string.history_7d
                HistoryRange.D30 -> R.string.history_30d
                HistoryRange.D90 -> R.string.history_90d
                HistoryRange.YEAR -> R.string.history_1y
            }
        )
    }

    private inner class DistributionVH(private val b: ItemDistributionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind() {
            val ctx = b.root.context
            val entries = assets.mapNotNull { a ->
                val t = Calc.totals(a.entries, prices[a.symbol])
                if (t.marketValue > 0.0) Triple(a, t, t.marketValue) else null
            }.sortedByDescending { it.third }

            if (entries.isEmpty()) {
                b.donut.visibility = View.GONE
                b.legend.removeAllViews()
                return
            }
            b.donut.visibility = View.VISIBLE
            val total = entries.sumOf { it.third }
            b.donut.setSlices(entries.map { DonutView.Slice(it.third, colorOf(it.first.symbol)) })

            b.legend.removeAllViews()
            entries.take(7).forEach { (asset, t, value) ->
                val item = ItemLegendBinding.inflate(LayoutInflater.from(ctx), b.legend, false)
                val color = colorOf(asset.symbol)
                item.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                item.legendName.text = asset.symbol
                item.legendPct.text = Fmt.pctPlain(value / total * 100.0)
                b.legend.addView(item.root)
            }
            if (entries.size > 7) {
                val rest = entries.drop(7).sumOf { it.third }
                val item = ItemLegendBinding.inflate(LayoutInflater.from(ctx), b.legend, false)
                item.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3A476A.toInt())
                item.legendName.text = "…"
                item.legendPct.text = Fmt.pctPlain(rest / total * 100.0)
                b.legend.addView(item.root)
            }
        }
    }

    private inner class AssetVH(private val b: ItemAssetBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(asset: Asset) {
            val ctx = b.root.context
            val color = colorOf(asset.symbol)
            val badge = b.coinBadge.background.mutate() as GradientDrawable
            badge.setColor(color)
            b.coinBadge.text = asset.symbol
            b.coinBadge.setTextColor(badgeTextColor(color))

            val t = Calc.totals(asset.entries, prices[asset.symbol])
            b.name.text = asset.title
            b.value.text = Fmt.money(t.marketValue)
            b.pnl.text = Fmt.pct(t.pnlPct)
            b.pnl.setTextColor(ContextCompat.getColor(ctx, if (t.pnl >= 0) R.color.up else R.color.down))

            val price = prices[asset.symbol] ?: asset.entries.lastOrNull { it.buyPrice > 0.0 }?.buyPrice
            val pricePart = if (price != null && t.amount > 0.0) Fmt.price(price) else "—"
            b.sub.text = if (t.amount > 0.0) {
                "${Fmt.amountShort(t.amount)} ${asset.symbol}"
            } else {
                "нет покупок · $pricePart"
            }

            val pts = Calc.points(asset.entries).map { it.value }
            if (pts.size >= 2) {
                b.spark.visibility = View.VISIBLE
                b.spark.setData(pts, if (t.pnl >= 0) ContextCompat.getColor(ctx, R.color.up)
                else ContextCompat.getColor(ctx, R.color.down))
            } else {
                b.spark.visibility = View.INVISIBLE
            }

            b.root.setOnClickListener { onAssetClick(asset) }
            b.root.setOnLongClickListener {
                onAssetLongClick(asset)
                true
            }
        }
    }
}
