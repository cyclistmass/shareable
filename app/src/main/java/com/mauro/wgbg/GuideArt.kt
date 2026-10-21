package com.mauro.wgbg

import wgbg.engine.DevCard

/**
 * Per-theme art for the card guide and the Inventory screen's knight cards (#71): one small place for a future
 * theme (#79) to add its own cards, instead of scattering `if (pirate)` checks through the UI.
 */
object GuideArt {
    val KNIGHT_NORMAL = listOf(R.drawable.card_knight_normal_1, R.drawable.card_knight_normal_2, R.drawable.card_knight_normal_3)
    val KNIGHT_PIRATE = listOf(R.drawable.card_knight_pirate_1, R.drawable.card_knight_pirate_2, R.drawable.card_knight_pirate_3)

    /** The knight card for [level] (1..3) in the chosen theme. */
    fun knightCard(level: Int, pirate: Boolean): Int = (if (pirate) KNIGHT_PIRATE else KNIGHT_NORMAL)[(level - 1).coerceIn(0, 2)]

    /**
     * A base-game Fortune card ([card], a [DevCard] index) as a full card that names itself: the Ledger pages (#86,
     * Mauro's pick), or in the Pirate theme the pirate knight card for the Sentinel.
     */
    fun fortuneArt(card: Int, pirate: Boolean): Int = when (card) {
        DevCard.KNIGHT -> if (pirate) knightCard(1, true) else R.drawable.card_fortune_sentinel
        DevCard.YEAR_OF_PLENTY -> R.drawable.card_fortune_windfall
        DevCard.MONOPOLY -> R.drawable.card_fortune_corner
        DevCard.ROAD_BUILDING -> R.drawable.card_fortune_trailblazers
        DevCard.VICTORY_POINT -> R.drawable.card_fortune_landmark
        else -> R.drawable.card_fortune_windfall
    }
}
