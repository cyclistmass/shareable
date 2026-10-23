package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ActionType
import wgbg.engine.BoardLayout
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Rules
import wgbg.net.MoveCodec

/**
 * Mauro's report (online room REEF-604, 0.3.4): an offer from Purple vanished as the 7 and the steal landed. The steal
 * took his last Stone, the offer asked for one, so the app's automatic No fired with only "No (5)" to say why.
 */
class OfferVanishTest {
    private val names = listOf("Mauro", "Bea", "Purple")
    private val config = GameConfig(expansion = Expansion.BASE, numPlayers = 3, vpToWin = 10, layout = BoardLayout.RANDOM,
        avoidAdjacentRedNumbers = true, discardLimit = 7, maxTurns = 1000, randomDiscard = false,
        generateTradeOffers = false, randomFirstPlayer = true)
    private val moves = javaClass.getResource("/reports/reef604.moves")!!.readText().lines().filter { it.isNotBlank() }
        .map { MoveCodec.decode(it)!! }

    @Test
    fun `the report's game replays, and the vanished offer was one he couldn't pay, now said in the panel`() {
        val s = Rules.newGame(config, -3358629982002711724L)
        var checked = false
        moves.forEachIndexed { i, a ->
            assertTrue(Rules.isLegal(s, a), "move $i illegal")
            if (a.type == ActionType.REJECT_TRADE && Rules.mover(s, a) == 0 && s.currentPlayer == 2 && i >= moves.size - 8) {
                assertTrue(Rules.cantPayOffer(s, 0), "move $i")
                assertEquals("1 Stone", shortfallText(s, 0))
                assertEquals("You can't pay Purple: you need 1 Stone · No in 5", status(s, 0, TradeMode.RESPOND, names, false, false, autoNo = 5))
                assertEquals("You can't pay Purple: you need 1 Stone", status(s, 0, TradeMode.RESPOND, names, false, false))
                checked = true
            }
            Rules.apply(s, a)
        }
        assertTrue(checked, "the No to Purple's offer is in the record")
    }
}
