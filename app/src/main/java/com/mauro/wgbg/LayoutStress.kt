package com.mauro.wgbg

import wgbg.engine.CARD_TYPES
import wgbg.engine.CkPiece
import wgbg.engine.Commodity
import wgbg.engine.DevCard
import wgbg.engine.GameState
import wgbg.engine.Phase
import wgbg.engine.Progress
import wgbg.engine.Track

/** Names as long as the name fields allow (16 letters), wide letters first: the player list's longest. */
internal val STRESS_NAMES = listOf("WWWWWWWWWWWWWWWW", "Bartholomew Wolf", "Maximiliana Rose", "Wilhelmina Moore", "Konstantinopolis", "Gwendolyn Wright")

/**
 * Debug builds only (tools/ci_layout.sh, by the FILAMENT_VIEW broadcast's `stress` extra): the player list at its fullest.
 * Everyone holds double-digit cards, road and knights; player 0 also holds every badge, one point short of winning
 * (12 in Age of Chivalry, a two-digit score; 9 in the base game, where 10 ends it on the next move), four progress
 * cards and top improvements, and in Age of Chivalry the Raiders are one step out (the warning line shows on 0's turn).
 * With [discard], player 0 owes a discard, as after a rolled 7, so the discard sheet opens over it. Cards come from
 * nowhere, so this is for looking at the screen only, never for play.
 */
internal fun layoutStress(s: GameState, discard: Boolean) {
    for (p in 0 until s.numPlayers) {
        for (r in 0 until CARD_TYPES) s.hands[p * CARD_TYPES + r] = if (p == 0) 4 else 2
        s.roadLength[p] = 10 + p
        if (s.ck == null) s.devPlayed[p * DevCard.COUNT + DevCard.KNIGHT] = 10 + p
    }
    s.longestRoadOwner = 0
    if (s.ck == null) s.largestArmyOwner = 0
    s.ck?.let { ck ->
        ck.barbarianPos = CkPiece.BARBARIAN_TRACK - 1
        for (c in 0 until Commodity.COUNT) ck.commodities[c] = 3
        for (t in 0 until Track.COUNT) ck.trackLevel[t] = 5
        for (c in 0 until minOf(4, Progress.COUNT)) ck.progressHand[c] = 1
    }
    // Hidden points make up the rest; one short of the target, so the next move doesn't end the game.
    s.devHand[DevCard.VICTORY_POINT] = 0
    s.devHand[DevCard.VICTORY_POINT] = (s.config.vpToWin - 1 - s.victoryPoints(0)).coerceAtLeast(0)
    if (discard) {
        s.discardOwed[0] = s.cardCount(0) / 2
        s.phase = Phase.DISCARD
        s.actingPlayer = 0
    }
}
