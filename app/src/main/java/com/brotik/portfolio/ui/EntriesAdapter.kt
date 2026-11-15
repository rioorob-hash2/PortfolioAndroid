package com.brotik.portfolio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.brotik.portfolio.R
import com.brotik.portfolio.core.Calc
import com.brotik.portfolio.core.Fmt
import com.brotik.portfolio.databinding.ItemEntryBinding

/** Список докупок по монете. */
class EntriesAdapter(
    private val symbol: String,
    private val onEntryClick: (Calc.Row) -> Unit,
) : RecyclerView.Adapter<EntriesAdapter.VH>() {

    private var rows: List<Calc.Row> = emptyList()

    fun submit(newRows: List<Calc.Row>) {
        rows = newRows.reversed()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])

    inner class VH(private val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Calc.Row) {
            val ctx = b.root.context
            val e = row.entry
            b.date.text = if (e.date != null) Fmt.dateFull(e.date) else (e.dateRaw.ifBlank { "—" })
            if (e.isMark) {
                b.investedSum.text = "пометка цены"
                b.investedSum.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
                b.detail.text = "по ${Fmt.price(e.buyPrice)}"
            } else {
                b.investedSum.text = Fmt.moneySigned(e.invested ?: 0.0)
                b.investedSum.setTextColor(ContextCompat.getColor(ctx, R.color.text))
                val sign = if ((e.invested ?: 0.0) < 0.0) "продажа " else ""
                b.detail.text = "${sign}${Fmt.amount(row.bought)} $symbol по ${Fmt.price(e.buyPrice)}"
            }
            val up = row.pnlAtBuy >= 0
            b.pnl.text = Fmt.moneySigned(row.pnlAtBuy)
            b.pnl.setTextColor(ContextCompat.getColor(ctx, if (up) R.color.up else R.color.down))
            b.root.setOnClickListener { onEntryClick(row) }
        }
    }
}
