package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Track

class BoardHudTest {
    @Test
    fun `improvement bars fill one segment per level and cap at four`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 4), (0..5).map(::barSegments))
    }

    @Test
    fun `bars are Guilds gold, Academy green, Crown blue`() {
        assertEquals(listOf(Track.TRADE, Track.SCIENCE, Track.POLITICS), IMPROVEMENT_BARS.map { it.first })
    }

    @Test
    fun `raiders countdown`() {
        assertEquals(wgbg.engine.ChivalryPiece.BARBARIAN_TRACK, raidersStepsLeft(0))
        assertEquals(0, raidersStepsLeft(wgbg.engine.ChivalryPiece.BARBARIAN_TRACK))
        assertEquals(0, raidersStepsLeft(99))
    }

    @Test
    fun `card count reads 8 in base and 8 slash progress in Age of Chivalry`() {
        val base = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(), 1)
        base.hands[0] += 3
        assertEquals("3", cardsLabel(base, 0))
        val chivalry = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 1)
        chivalry.hands[0] += 2
        chivalry.chivalry!!.progressHand[0] += 1
        assertEquals("2/1", cardsLabel(chivalry, 0))
    }

    @Test
    fun `the menu keeps other players' hidden points and card kinds secret while you play`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(numPlayers = 3), 4)
        s.devHand[1 * wgbg.engine.DevCard.COUNT + wgbg.engine.DevCard.VICTORY_POINT] = 1   // player 1 holds a Landmark
        s.hands[1 * wgbg.engine.CARD_TYPES + 2] = 3
        val asSeen = PlayerInfo.of(s, 1, me = 0)
        assertEquals(s.publicVictoryPoints(1), asSeen.vp)
        assertTrue(asSeen.hand.isEmpty())
        assertEquals(s.handSize(1), asSeen.cards)
        val own = PlayerInfo.of(s, 1, me = 1)
        assertEquals(s.publicVictoryPoints(1) + 1, own.vp)
        assertEquals(3, own.hand[2])
        assertEquals(s.victoryPoints(1), PlayerInfo.of(s, 1, me = -1).vp)   // watching bots shows all
    }

    @Test
    fun `mini cards show each roll's gains and discards, and a robbery only to the two players in it`() {
        val roll = TurnReport(player = 0, turn = 5, die1 = 3, die2 = 4,
            rollIn = listOf(intArrayOf(2, 0, 0, 0, 0), IntArray(5), IntArray(5)),
            rollOut = listOf(IntArray(5), intArrayOf(0, 0, 1, 3, 0), IntArray(5)),
            steal = Steal(thief = 0, victim = 2, card = 4))
        assertEquals(listOf(RollMini(0, 2), RollMini(4, 1)), rollMinis(roll, 0, me = 0))          // the thief sees the card
        assertEquals(listOf(RollMini(2, -1), RollMini(3, -3)), rollMinis(roll, 1, me = 0))       // discards are public
        assertEquals(listOf(RollMini(4, -1)), rollMinis(roll, 2, me = 2))                          // the victim sees it
        assertEquals(listOf(RollMini(-1, -1)), rollMinis(roll, 2, me = 1))                         // others see it face down
        assertEquals(listOf(RollMini(0, 2), RollMini(-1, 1)), rollMinis(roll, 0, me = 1))
        assertTrue(rollMinis(null, 0, 0).isEmpty())
        assertTrue(rollMinis(TurnReport(0, 1), 0, 0).isEmpty())                                    // not rolled yet
    }

    @Test
    fun `the turn tracker records production, discards and the robbery for the mini cards`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(numPlayers = 3), 21)
        val buf = wgbg.engine.ActionBuffer()
        val bots = List(3) { wgbg.ai.GreedyBot(it.toLong()) }
        val tracker = TurnTracker(listOf("A", "B", "C"))
        while (s.phase != wgbg.engine.Phase.PLAY_TURN) { wgbg.engine.Rules.legalActions(s, buf); val w = s.actingPlayer; val a = bots[w].choose(s, buf); tracker.apply(s, w, a) { wgbg.engine.Rules.apply(s, a) } }
        val victim = (s.currentPlayer + 1) % 3
        for (r in 0 until 5) s.hands[victim * wgbg.engine.CARD_TYPES + r] = 2       // 10 cards: must discard 5
        val seven = wgbg.engine.Action.roll(3, 4)
        tracker.apply(s, s.currentPlayer, seven) { wgbg.engine.Rules.apply(s, seven) }
        while (s.phase == wgbg.engine.Phase.DISCARD || s.phase == wgbg.engine.Phase.MOVE_ROBBER) {
            wgbg.engine.Rules.legalActions(s, buf); val w = s.actingPlayer; val a = bots[w].choose(s, buf); tracker.apply(s, w, a) { wgbg.engine.Rules.apply(s, a) }
        }
        val rep = tracker.current!!
        assertEquals(5, rep.rollOut[victim].sum())
        assertTrue(rollMinis(rep, victim, me = -1).any { it.n < 0 })
    }

    @Test
    fun `progress cards you can't play now are named with the reason (#117)`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 3)
        val rnd = kotlin.random.Random(3)
        while (!(s.phase == wgbg.engine.Phase.PLAY_TURN && !s.hasRolled && s.turn > 0))
            wgbg.engine.Rules.applyChecked(s, wgbg.engine.Rules.legalActions(s).random(rnd))
        val me = s.currentPlayer; val other = (me + 1) % s.config.numPlayers
        val chivalry = s.chivalry!!
        chivalry.progressHand[me * wgbg.engine.Progress.COUNT + wgbg.engine.Progress.ALCHEMIST] += 1
        chivalry.progressHand[me * wgbg.engine.Progress.COUNT + wgbg.engine.Progress.SABOTEUR] += 1
        val now = playableProgress(s, me)
        assertTrue(wgbg.engine.Progress.ALCHEMIST in now, "the Alchemist is for before the roll")
        assertTrue(wgbg.engine.Progress.SABOTEUR !in now, "the Wrecker waits for the roll (Mauro's report)")
        assertEquals("after you roll", notPlayableWhy(s, me, wgbg.engine.Progress.SABOTEUR))
        assertTrue(playableProgress(s, other).isEmpty())
        assertEquals("only on your turn", notPlayableWhy(s, other, wgbg.engine.Progress.SABOTEUR))
        assertTrue(playableProgress(wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(), 1), 0).isEmpty())   // base game: none
    }

    @Test
    fun `a knight's corner glows round its base, an empty corner gets the usual dot (#118)`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 2)
        s.chivalry!!.knightOwner[7] = 1
        assertTrue(knightAt(s, 7) && pieceAt(s, 7))
        assertTrue(!knightAt(s, 8))
        assertTrue(!knightAt(wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(), 2), 7))   // base game: no knights
        assertTrue(KNIGHT_GLOW_RX * KNIGHT_SCALE * FLAT_KNIGHT_GLOW_SHARE > BUILD_MARK_R, "the ring is wider than the dot it replaces")
    }

    @Test
    fun `the Raiders warning shows on your turn from 3 steps out, with both strengths (#92)`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 5)
        val chivalry = s.chivalry!!
        val me = s.currentPlayer
        chivalry.barbarianPos = wgbg.engine.ChivalryPiece.BARBARIAN_TRACK - 4
        assertEquals(null, raidersWarning(s, me))                                   // 4 out: quiet
        chivalry.barbarianPos = wgbg.engine.ChivalryPiece.BARBARIAN_TRACK - 3
        val w = raidersWarning(s, me)!!
        assertTrue(w.startsWith("Raiders 3 steps away!"), w)
        val raiders = wgbg.engine.Chivalry.barbarianStrength(s)
        assertTrue("\n$raiders vs " in w, w)   // Raiders first, like the track line
        assertEquals(null, raidersWarning(s, (me + 1) % s.numPlayers))              // not your turn
        chivalry.barbarianPos = wgbg.engine.ChivalryPiece.BARBARIAN_TRACK - 1
        assertTrue(raidersWarning(s, me)!!.startsWith("Raiders 1 step away!"))
        assertEquals(null, raidersWarning(wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(), 5), 0))   // base game
    }
}
