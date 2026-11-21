package com.brotik.portfolio.ui

import androidx.annotation.DrawableRes
import com.brotik.portfolio.R

/**
 * Официальные логотипы монет из `res/drawable-nodpi/ic_coin_<тикер>.png`.
 *
 * Набор конечный: если для тикера иконки нет (например HYPE), возвращаем null —
 * вызывающая сторона показывает старый текстовый бейдж с буквами.
 */
object CoinIcons {

    private val bySymbol: Map<String, Int> = mapOf(
        "BTC" to R.drawable.ic_coin_btc,
        "ETH" to R.drawable.ic_coin_eth,
        "SOL" to R.drawable.ic_coin_sol,
        "LTC" to R.drawable.ic_coin_ltc,
        "TON" to R.drawable.ic_coin_ton,
        "OP" to R.drawable.ic_coin_op,
        "ARB" to R.drawable.ic_coin_arb,
    )

    /** Ресурс иконки для тикера или null, если иконки нет. */
    @DrawableRes
    fun drawableFor(symbol: String): Int? = bySymbol[symbol.trim().uppercase()]
}
