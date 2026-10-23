package wgbg.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Commodity.CLOTH
import wgbg.engine.Commodity.COIN
import wgbg.engine.Commodity.PAPER
import wgbg.engine.Resource.BRICK
import wgbg.engine.Resource.ORE
import wgbg.engine.Resource.SHEEP
import wgbg.engine.Resource.WHEAT
import wgbg.engine.Resource.WOOD

/**
 * Age of Chivalry setup, production, event die, improvements, metropolises, walls, barbarians, trade, victory.
 *
 * Beginner board: top row (r = -2) is Ore 10, Sheep 2, Wood 9; bottom row (r = 2) is
 * Brick 5, Wheat 6, Sheep 11. The top corner of a top-row hex and the bottom corner of a
 * bottom-row hex touch only that hex.
 */
class ChivalryRulesTest {
    private fun top(s: GameState, q: Int) = T.corner(s, q, -2, 0)
    private fun bottom(s: GameState, q: Int) = T.corner(s, q, 2, 3)

    // ---------------------------------------------------------------- config & setup

    @Test
    fun `config defaults to 13 VP and 3-4 players`() {
        assertEquals(13, CK.config().vpToWin)
        assertEquals(10, GameConfig().vpToWin)
        assertThrows(IllegalArgumentException::class.java) { CK.config(players = 2) }
        assertNull(Rules.newGame(GameConfig(), 1).chivalry)
    }

    @Test
    fun `second setup placement is a city and pays one resource per adjacent hex`() {
        val s = Rules.newGame(CK.config(3), 9)
        val rng = Rng(2)
        val placed = mutableListOf<Pair<Int, Int>>()
        while (s.phase != Phase.PLAY_TURN) {
            val acts = CK.legal(s)
            if (s.phase == Phase.BUILD_INITIAL_SETTLEMENT) {
                val kind = if (s.setupStep < 3) ActionType.BUILD_SETTLEMENT else ActionType.BUILD_CITY
                assertTrue(acts.all { it.type == kind })
                val p = s.actingPlayer
                val a = acts[rng.nextInt(acts.size)]
                val before = s.handSize(p)
                Rules.applyChecked(s, a)
                val v = a.payload.toInt()
                val producing = s.board.topology.vertexHexes[v].count { s.board.hexResource[it] >= 0 }
                assertEquals(if (kind == ActionType.BUILD_CITY) producing else 0, s.handSize(p) - before)
                placed += p to v
            } else {
                Rules.applyChecked(s, acts[rng.nextInt(acts.size)])
            }
            CK.checkInvariants(s)
        }
        for (p in 0 until 3) {
            assertEquals(1, s.piecesOnBoard(p, Piece.SETTLEMENT))
            assertEquals(1, s.piecesOnBoard(p, Piece.CITY))
            assertEquals(3, s.victoryPoints(p))
            assertEquals(0, s.chivalry!!.commodityCount(p), "no commodities at setup")
        }
        assertEquals(s.board.desertHex, s.robberHex)
    }

    // ---------------------------------------------------------------- production

    @Test
    fun `cities on forest, pasture and mountains produce a resource and a commodity`() {
        val s = CK.playState()
        T.settle(s, 0, top(s, 2), city = true) // Wood 9
        T.settle(s, 0, top(s, 0), city = true) // Ore 10
        T.settle(s, 1, top(s, 1), city = true) // Sheep 2
        T.settle(s, 1, bottom(s, -2), city = true) // Brick 5
        T.settle(s, 2, bottom(s, -1)) // Wheat 6 (settlement)
        CK.roll(s, 4, 5) // 9
        assertEquals(1, s.card(0, WOOD))
        assertEquals(1, s.card(0, PAPER))
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 4, 6) // 10
        assertEquals(1, s.card(0, ORE))
        assertEquals(1, s.card(0, COIN))
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 1, 1) // 2
        assertEquals(1, s.card(1, SHEEP))
        assertEquals(1, s.card(1, CLOTH))
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 2, 3) // 5: hills give two resources, no commodity
        assertEquals(2, s.card(1, BRICK))
        assertEquals(0, s.chivalry!!.commodityCount(1) - 1)
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 3, 3) // 6: settlement gives one
        assertEquals(1, s.card(2, WHEAT))
        CK.checkInvariants(s)
    }

    @Test
    fun `commodity bank shortage pays nobody unless a single player is owed`() {
        val s = CK.playState()
        T.settle(s, 0, top(s, 2), city = true)
        T.settle(s, 1, T.corner(s, 2, -2, 2), city = true) // also on Wood 9
        val chivalry = s.chivalry!!
        CK.give(s, 2, PAPER, Commodity.BANK - 1) // one paper left
        CK.roll(s, 4, 5)
        assertEquals(0, s.card(0, PAPER))
        assertEquals(0, s.card(1, PAPER))
        assertTrue(s.card(0, WOOD) >= 1)
        assertEquals(1, chivalry.commodityBank[0])
        CK.checkInvariants(s)
    }

    @Test
    fun `robber blocks commodity production too`() {
        val s = CK.playState()
        T.settle(s, 0, top(s, 2), city = true)
        s.robberHex = CK.hexAt(s, 2, -2)
        s.chivalry!!.barbarianAttacks = 1
        CK.roll(s, 4, 5)
        assertEquals(0, s.cardCount(0))
    }

    // ---------------------------------------------------------------- sevens & walls

    @Test
    fun `seven before the first attack - discards count commodities, robber stays put`() {
        val s = CK.playState()
        T.give(s, 1, WOOD, 5)
        CK.give(s, 1, PAPER, 4)
        CK.roll(s, 3, 4)
        assertEquals(Phase.DISCARD, s.phase)
        assertEquals(1, s.actingPlayer)
        assertEquals(4, s.discardOwed[1])
        assertTrue(Action.discard(PAPER) in CK.legal(s))
        repeat(4) { Rules.applyChecked(s, Action.discard(PAPER)) }
        assertEquals(Phase.PLAY_TURN, s.phase, "no robber move before the first barbarian attack")
        assertEquals(s.board.desertHex, s.robberHex)
        CK.checkInvariants(s)
    }

    @Test
    fun `seven after the first attack moves the robber and steals commodities`() {
        val s = CK.playState()
        s.chivalry!!.barbarianAttacks = 1
        val v = top(s, 2)
        T.settle(s, 1, v)
        CK.give(s, 1, COIN, 1)
        CK.roll(s, 3, 4)
        assertEquals(Phase.MOVE_ROBBER, s.phase)
        val h = CK.hexAt(s, 2, -2)
        assertTrue(Action.moveRobber(h, 1) in CK.legal(s))
        Rules.applyChecked(s, Action.moveRobber(h, 1))
        assertEquals(1, s.card(0, COIN))
        assertEquals(Phase.PLAY_TURN, s.phase)
        CK.checkInvariants(s)
    }

    @Test
    fun `city walls cost 2 brick, max 3, raise the hand limit by 2 each`() {
        val s = CK.playState()
        val cities = intArrayOf(top(s, 0), top(s, 2), bottom(s, -2), bottom(s, 0))
        for (v in cities) T.settle(s, 0, v, city = true)
        CK.roll(s, 6, 6)
        assertTrue(CK.legalOf(s, ChivalryActionType.BUILD_CITY_WALL).isEmpty())
        T.give(s, 0, BRICK, 8)
        assertEquals(cities.toSet(), CK.legalOf(s, ChivalryActionType.BUILD_CITY_WALL).map { it.payload.toInt() }.toSet())
        for (i in 0 until 3) Rules.applyChecked(s, Action.buildCityWall(cities[i]))
        assertEquals(2, s.hand(0, BRICK))
        assertTrue(CK.legalOf(s, ChivalryActionType.BUILD_CITY_WALL).isEmpty(), "only 3 walls")
        assertEquals(13, Chivalry.discardLimit(s, 0))
        assertEquals(7, Chivalry.discardLimit(s, 1))
        CK.checkInvariants(s)
        // 13 cards: no discard with three walls.
        Rules.applyChecked(s, Action.END_TURN)
        T.give(s, 0, WOOD, 11)
        CK.roll(s, 3, 4)
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(13, s.cardCount(0))
    }

    @Test
    fun `walls cannot go on settlements`() {
        val s = CK.playState()
        T.settle(s, 0, top(s, 0))
        CK.roll(s, 6, 6)
        T.give(s, 0, BRICK, 2)
        assertTrue(CK.legalOf(s, ChivalryActionType.BUILD_CITY_WALL).isEmpty())
    }

    // ---------------------------------------------------------------- event die & progress draws

    @Test
    fun `ship faces advance the barbarians`() {
        val s = CK.playState()
        for (face in 0 until 3) {
            CK.roll(s, 6, 6, face)
            assertEquals(face + 1, s.chivalry!!.barbarianPos)
            Rules.applyChecked(s, Action.END_TURN)
        }
        assertEquals(0, s.chivalry!!.barbarianAttacks)
    }

    @Test
    fun `progress draws follow the improvement table - level L shows red 1 to L+1`() {
        for (level in 0..5) for (red in 1..6) for (track in 0 until Track.COUNT) {
            val s = CK.playState()
            T.settle(s, 1, top(s, 0), city = true)
            CK.level(s, 1, track, level)
            CK.roll(s, red, if (red == 6) 5 else 6, EventDie.SHIP_FACES + track)
            val chivalry = s.chivalry!!
            val drew = chivalry.progressCount(1) + chivalry.progressVp[1]
            assertEquals(if (level >= 1 && red <= level + 1) 1 else 0, drew, "level $level red $red track $track")
            assertEquals(Progress.PER_DECK - drew, chivalry.deckSize[track])
            if (drew == 1) {
                val c = (0 until Progress.COUNT).firstOrNull { chivalry.progressHand[Progress.COUNT + it] > 0 }
                if (c != null) assertEquals(track, Progress.track(c))
            }
        }
    }

    @Test
    fun `draws go in seat order from the roller and stop when the deck is empty`() {
        val s = CK.playState()
        val chivalry = s.chivalry!!
        for (p in 0 until 4) CK.level(s, p, Track.POLITICS, 1)
        T.settle(s, 0, top(s, 0), city = true)
        // Leave only two politics cards.
        while (chivalry.deckSize[Track.POLITICS] > 2) {
            val c = chivalry.deck[Track.POLITICS * Progress.PER_DECK + chivalry.deckHead[Track.POLITICS]]
            if (Progress.isVictoryPoint(c)) {
                val base = Track.POLITICS * Progress.PER_DECK + chivalry.deckHead[Track.POLITICS]
                val alt = (0 until chivalry.deckSize[Track.POLITICS]).map { chivalry.deck[base + it] }.firstOrNull { !Progress.isVictoryPoint(it) } ?: break
                CK.stackDeck(s, alt)
            }
            val top = chivalry.deck[Track.POLITICS * Progress.PER_DECK + chivalry.deckHead[Track.POLITICS]]
            CK.giveProgress(s, 3, top)
            if (chivalry.progressCount(3) > 3) {
                // keep player 3's hand legal by returning cards to the bottom later; just move them to player 2's tally
                chivalry.progressHand[3 * Progress.COUNT + top]--
                chivalry.progressHand[2 * Progress.COUNT + top]++
            }
        }
        val before = IntArray(4) { chivalry.progressCount(it) }
        chivalry.progressHand.fill(0, 2 * Progress.COUNT, 4 * Progress.COUNT)
        // Restore conservation: put the removed cards back is unnecessary for this check, so only compare deltas.
        Rules.apply(s, Action.roll(1, 2, EventDie.SHIP_FACES + Track.POLITICS))
        assertEquals(1, chivalry.progressCount(0) + chivalry.progressVp[0] - before[0])
        assertEquals(1, chivalry.progressCount(1) + chivalry.progressVp[1] - before[1])
        assertEquals(0, chivalry.deckSize[Track.POLITICS])
    }

    @Test
    fun `a fifth progress card must be discarded to the bottom of its deck`() {
        val s = CK.playState()
        val chivalry = s.chivalry!!
        for (c in intArrayOf(Progress.SPY, Progress.WARLORD, Progress.BISHOP, Progress.WEDDING)) CK.giveProgress(s, 1, c)
        CK.level(s, 1, Track.SCIENCE, 1)
        T.settle(s, 1, top(s, 0), city = true)
        CK.stackDeck(s, Progress.SMITH)
        CK.roll(s, 1, 2, EventDie.SHIP_FACES + Track.SCIENCE)
        assertEquals(Phase.PROGRESS_DISCARD, s.phase)
        assertEquals(1, s.actingPlayer)
        assertEquals(5, CK.legal(s).size)
        Rules.applyChecked(s, Action.discardProgress(Progress.SMITH))
        assertEquals(4, chivalry.progressCount(1))
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(0, s.actingPlayer)
        // It went to the bottom of the science deck.
        val t = Track.SCIENCE
        assertEquals(Progress.SMITH, chivalry.deck[t * Progress.PER_DECK + (chivalry.deckHead[t] + chivalry.deckSize[t] - 1) % Progress.PER_DECK])
        CK.checkInvariants(s)
    }

    @Test
    fun `printer and constitution are revealed at once for a VP`() {
        for (card in intArrayOf(Progress.PRINTER, Progress.CONSTITUTION)) {
            val s = CK.playState()
            val t = Progress.track(card)
            CK.level(s, 2, t, 1)
            T.settle(s, 2, top(s, 0), city = true)
            CK.stackDeck(s, card)
            CK.roll(s, 2, 3, EventDie.SHIP_FACES + t)
            assertEquals(1, s.chivalry!!.progressVp[2])
            assertEquals(0, s.chivalry!!.progressCount(2))
            assertEquals(3, s.victoryPoints(2))
            CK.checkInvariants(s)
        }
    }

    // ---------------------------------------------------------------- improvements

    @Test
    fun `improvements cost level-many commodities and need a city`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        CK.give(s, 0, COIN, 15)
        assertTrue(CK.legalOf(s, ChivalryActionType.IMPROVE_CITY).isEmpty(), "needs a city")
        T.settle(s, 0, top(s, 0), city = true)
        assertEquals(listOf(Action.improveCity(Track.POLITICS)), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        var spent = 0
        for (level in 1..3) {
            Rules.applyChecked(s, Action.improveCity(Track.POLITICS))
            spent += level
            assertEquals(level, s.chivalry!!.level(0, Track.POLITICS))
            assertEquals(15 - spent, s.card(0, COIN))
        }
        // Level 4 claims the metropolis, so it names the city.
        assertEquals(listOf(Action.improveCity(Track.POLITICS, top(s, 0))), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        CK.checkInvariants(s)
    }

    @Test
    fun `improvement tracks use cloth, coin and paper`() {
        val s = CK.playState()
        T.settle(s, 0, top(s, 0), city = true)
        CK.roll(s, 6, 6)
        CK.give(s, 0, CLOTH, 1)
        assertEquals(listOf(Action.improveCity(Track.TRADE)), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        CK.give(s, 0, PAPER, 1)
        assertEquals(
            setOf(Action.improveCity(Track.TRADE), Action.improveCity(Track.SCIENCE)),
            CK.legalOf(s, ChivalryActionType.IMPROVE_CITY).toSet(),
        )
    }

    @Test
    fun `metropolis - first to level 4, taken by level 5, safe at level 5`() {
        val s = CK.playState()
        val chivalry = s.chivalry!!
        val a0 = top(s, 0)
        val a1 = top(s, 2)
        T.settle(s, 0, a0, city = true)
        T.settle(s, 0, a1, city = true)
        T.settle(s, 1, bottom(s, -2), city = true)
        T.settle(s, 2, bottom(s, 0), city = true)
        CK.level(s, 0, Track.TRADE, 3)
        CK.level(s, 1, Track.TRADE, 3)
        CK.level(s, 2, Track.TRADE, 4 - 1)
        CK.roll(s, 6, 6)
        CK.give(s, 0, CLOTH, 4)
        assertEquals(
            setOf(Action.improveCity(Track.TRADE, a0), Action.improveCity(Track.TRADE, a1)),
            CK.legalOf(s, ChivalryActionType.IMPROVE_CITY).toSet(),
        )
        Rules.applyChecked(s, Action.improveCity(Track.TRADE, a1))
        assertEquals(0, chivalry.metroOwner[Track.TRADE])
        assertEquals(a1, chivalry.metroVertex[Track.TRADE])
        assertEquals(6, s.victoryPoints(0)) // 2 cities + metropolis
        CK.checkInvariants(s)

        // Player 1 reaches level 4 too: no metropolis.
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 6, 6)
        CK.give(s, 1, CLOTH, 9)
        assertEquals(listOf(Action.improveCity(Track.TRADE)), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        Rules.applyChecked(s, Action.improveCity(Track.TRADE))
        assertEquals(0, chivalry.metroOwner[Track.TRADE])
        // Level 5 takes it from a level-4 holder.
        val v1 = bottom(s, -2)
        assertEquals(listOf(Action.improveCity(Track.TRADE, v1)), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        Rules.applyChecked(s, Action.improveCity(Track.TRADE, v1))
        assertEquals(1, chivalry.metroOwner[Track.TRADE])
        assertEquals(v1, chivalry.metroVertex[Track.TRADE])
        assertEquals(4, s.victoryPoints(0))
        assertEquals(4, s.victoryPoints(1))
        CK.checkInvariants(s)

        // Player 2 at level 5 cannot take it from a level-5 holder.
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 6, 6)
        CK.give(s, 2, CLOTH, 9)
        Rules.applyChecked(s, Action.improveCity(Track.TRADE))
        assertEquals(listOf(Action.improveCity(Track.TRADE)), CK.legalOf(s, ChivalryActionType.IMPROVE_CITY))
        Rules.applyChecked(s, Action.improveCity(Track.TRADE))
        assertEquals(1, chivalry.metroOwner[Track.TRADE])
        assertTrue(CK.legalOf(s, ChivalryActionType.IMPROVE_CITY).isEmpty(), "max level 5")
        CK.checkInvariants(s)
    }

    @Test
    fun `claiming a metropolis needs a city without one`() {
        val s = CK.playState()
        val v = top(s, 0)
        T.settle(s, 0, v, city = true)
        CK.level(s, 0, Track.SCIENCE, 4, v)
        CK.level(s, 0, Track.TRADE, 3)
        CK.roll(s, 6, 6)
        CK.give(s, 0, CLOTH, 4)
        assertTrue(CK.legalOf(s, ChivalryActionType.IMPROVE_CITY).none { it.a == Track.TRADE })
    }

    @Test
    fun `trading house trades commodities 2 for 1`() {
        val s = CK.playState()
        T.settle(s, 0, T.corner(s, 0, 0, 0), city = true) // inland corner: no harbor, so 4:1 to start
        CK.roll(s, 6, 6)
        CK.give(s, 0, COIN, 3)
        assertTrue(CK.legalOf(s, ActionType.MARITIME_TRADE).isEmpty())
        CK.give(s, 0, COIN, 1)
        assertTrue(Action.maritimeTrade(COIN, PAPER) in CK.legal(s), "commodities for commodities")
        CK.level(s, 0, Track.TRADE, 3)
        assertEquals(2, Chivalry.tradeRate(s, 0, COIN))
        assertEquals(4, Chivalry.tradeRate(s, 0, WOOD), "only commodities")
        Rules.applyChecked(s, Action.maritimeTrade(COIN, ORE))
        assertEquals(2, s.card(0, COIN))
        assertEquals(1, s.card(0, ORE))
        CK.checkInvariants(s)
    }

    @Test
    fun `aqueduct grants a resource of choice when production gives nothing, but not on a 7`() {
        val s = CK.playState()
        T.settle(s, 1, top(s, 0), city = true)
        CK.level(s, 1, Track.SCIENCE, 3)
        CK.roll(s, 6, 6)
        assertEquals(Phase.AQUEDUCT, s.phase)
        assertEquals(1, s.actingPlayer)
        assertEquals(5, CK.legal(s).size)
        Rules.applyChecked(s, Action.chooseCard(WHEAT))
        assertEquals(1, s.hand(1, WHEAT))
        assertEquals(Phase.PLAY_TURN, s.phase)
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 3, 4)
        assertEquals(Phase.PLAY_TURN, s.phase, "no aqueduct on a 7")
        Rules.applyChecked(s, Action.END_TURN)
        CK.roll(s, 4, 6) // Ore 10: player 1 is paid, no aqueduct
        assertEquals(Phase.PLAY_TURN, s.phase)
        CK.checkInvariants(s)
    }

    // ---------------------------------------------------------------- barbarians

    private fun attackState(): GameState {
        val s = CK.playState()
        T.settle(s, 0, top(s, 0), city = true)
        T.settle(s, 1, top(s, 2), city = true)
        T.settle(s, 2, bottom(s, -2), city = true)
        s.chivalry!!.barbarianPos = ChivalryPiece.BARBARIAN_TRACK - 1
        return s
    }

    @Test
    fun `knights win - the top defender gets a Defender of the Realm VP, knights deactivate`() {
        val s = attackState()
        val chivalry = s.chivalry!!
        CK.knight(s, 0, T.corner(s, 0, -1, 1), level = 2, active = true)
        CK.knight(s, 1, T.corner(s, 0, -1, 3), level = 1, active = true)
        CK.knight(s, 3, T.corner(s, 0, -1, 5), level = 3, active = false) // inactive knights do not count
        assertEquals(3, Chivalry.barbarianStrength(s))
        CK.roll(s, 6, 6, 0)
        assertEquals(1, chivalry.barbarianAttacks)
        assertEquals(0, chivalry.barbarianPos)
        assertEquals(1, chivalry.defenderVp[0])
        assertEquals(3, s.victoryPoints(0), "city 2 + defender 1")
        assertTrue((0 until s.board.vertexCount).none { chivalry.isActive(it) })
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertTrue(chivalry.robberActive)
        CK.checkInvariants(s)
    }

    @Test
    fun `knights win with a tie - tied defenders each draw a progress card of their choice`() {
        val s = attackState()
        val chivalry = s.chivalry!!
        CK.knight(s, 1, T.corner(s, 0, -1, 1), level = 2, active = true)
        CK.knight(s, 2, T.corner(s, 0, -1, 3), level = 2, active = true)
        CK.roll(s, 6, 6, 0)
        assertEquals(Phase.DEFENDER_DRAW, s.phase)
        assertEquals(1, s.actingPlayer)
        assertEquals(3, CK.legal(s).size)
        CK.stackDeck(s, Progress.SPY)
        Rules.applyChecked(s, Action.chooseProgressDeck(Track.POLITICS))
        assertEquals(1, chivalry.progressHand[1 * Progress.COUNT + Progress.SPY])
        assertEquals(2, s.actingPlayer)
        Rules.applyChecked(s, Action.chooseProgressDeck(Track.TRADE))
        assertEquals(1, chivalry.progressCount(2) + chivalry.progressVp[2])
        assertEquals(Phase.PLAY_TURN, s.phase)
        assertEquals(0, chivalry.defenderVp.sum())
        CK.checkInvariants(s)
    }

    @Test
    fun `barbarians win - weakest players with cities lose one, walls fall, metropolis and cityless are safe`() {
        val s = attackState()
        val chivalry = s.chivalry!!
        val c1 = top(s, 2)
        chivalry.wall[c1] = 1
        chivalry.wallsLeft[1]--
        // Player 2's only city is a metropolis: immune and not counted as weakest.
        CK.level(s, 2, Track.SCIENCE, 4)
        CK.knight(s, 0, T.corner(s, 0, -1, 1), level = 1, active = true)
        // Strength 3 vs defence 1. Player 3 has no city.
        CK.roll(s, 6, 6, 0)
        assertEquals(Phase.BARBARIAN_LOSS, s.phase)
        assertEquals(1, s.actingPlayer)
        assertEquals(listOf(Action.chooseVertex(c1)), CK.legal(s))
        Rules.applyChecked(s, Action.chooseVertex(c1))
        assertEquals(Piece.SETTLEMENT, s.vertexBuilding[c1])
        assertEquals(0, chivalry.wall[c1])
        assertEquals(3, chivalry.wallsLeft[1])
        assertEquals(1, s.victoryPoints(1))
        assertEquals(Piece.CITY, s.vertexBuilding[top(s, 0)], "player 0 contributed more")
        // Production follows the attack; player 2 (Academy 4) gets nothing, so the aqueduct asks for a pick.
        while (s.phase == Phase.AQUEDUCT) Rules.applyChecked(s, Action.chooseCard(WHEAT))
        assertEquals(Phase.PLAY_TURN, s.phase)
        CK.checkInvariants(s)
    }

    @Test
    fun `barbarians win - all tied weakest players lose a city`() {
        val s = attackState()
        CK.roll(s, 6, 6, 0)
        val losers = mutableListOf<Int>()
        while (s.phase == Phase.BARBARIAN_LOSS) {
            losers += s.actingPlayer
            Rules.applyChecked(s, CK.legal(s)[0])
        }
        assertEquals(listOf(0, 1, 2), losers)
        for (p in 0 until 3) assertEquals(0, s.piecesOnBoard(p, Piece.CITY))
        CK.checkInvariants(s)
    }

    // ---------------------------------------------------------------- trade

    @Test
    fun `domestic trade with commodities`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        CK.give(s, 0, PAPER, 2)
        T.give(s, 2, WOOD, 1)
        val give = IntArray(CHIVALRY_CARD_TYPES).also { it[PAPER] = 2 }
        val want = IntArray(CHIVALRY_CARD_TYPES).also { it[WOOD] = 1 }
        val offer = Action.offerTradeCk(give, want)
        assertTrue(Rules.isLegal(s, offer))
        assertFalse(Rules.isLegal(s, Action.offerTrade(intArrayOf(1, 0, 0, 0, 0), intArrayOf(0, 1, 0, 0, 0))), "base offers are off")
        Rules.applyChecked(s, offer)
        assertEquals(listOf(Action.REJECT_TRADE), CK.legal(s))
        Rules.applyChecked(s, Action.REJECT_TRADE)
        Rules.applyChecked(s, Action.ACCEPT_TRADE)
        Rules.applyChecked(s, Action.REJECT_TRADE)
        assertEquals(Phase.DECIDE_ACCEPTEES, s.phase)
        Rules.applyChecked(s, Action.confirmTrade(2))
        assertEquals(2, s.card(2, PAPER))
        assertEquals(1, s.card(0, WOOD))
        assertEquals(0, s.chivalry!!.offer.sum())
        CK.checkInvariants(s)
    }

    @Test
    fun `maritime trade of commodities at 4, 3 and generic ports`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        CK.give(s, 0, CLOTH, 3)
        assertTrue(CK.legalOf(s, ActionType.MARITIME_TRADE).isEmpty())
        val generic = s.board.ports.first { it.type == Port.GENERIC }
        T.settle(s, 0, generic.v1)
        assertEquals(3, Chivalry.tradeRate(s, 0, CLOTH))
        assertEquals(7, CK.legalOf(s, ActionType.MARITIME_TRADE).size)
        Rules.applyChecked(s, Action.maritimeTrade(CLOTH, WOOD))
        assertEquals(0, s.card(0, CLOTH))
        CK.checkInvariants(s)
    }

    // ---------------------------------------------------------------- victory & determinism

    @Test
    fun `13 VP wins on your own turn`() {
        val s = CK.playState()
        val chivalry = s.chivalry!!
        val cities = intArrayOf(top(s, 0), top(s, 2), bottom(s, -2), bottom(s, 0))
        for (v in cities) T.settle(s, 0, v, city = true)
        chivalry.defenderVp[0] = 2
        chivalry.defenderLeft -= 2
        chivalry.progressVp[0] = 2 // 8 + 2 + 2 = 12
        // (VP cards would come out of the decks; keep conservation by removing them.)
        CK.giveProgress(s, 3, Progress.PRINTER); chivalry.progressHand[3 * Progress.COUNT + Progress.PRINTER]--
        CK.giveProgress(s, 3, Progress.CONSTITUTION); chivalry.progressHand[3 * Progress.COUNT + Progress.CONSTITUTION]--
        assertEquals(12, s.victoryPoints(0))
        CK.roll(s, 6, 6)
        assertEquals(Phase.PLAY_TURN, s.phase)
        val v = T.corner(s, 0, -1, 3)
        T.path(s, 0, T.corner(s, 0, -1, 1), T.corner(s, 0, -1, 2), v)
        T.giveAll(s, 0, Cost.SETTLEMENT)
        Rules.applyChecked(s, Action.buildSettlement(v))
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(0, s.winner)
        CK.checkInvariants(s)
    }

    @Test
    fun `Age of Chivalry games are deterministic and replayable`() {
        fun play(): Pair<List<Action>, Long> {
            val s = Rules.newGame(CK.config(3, BoardLayout.RANDOM, maxTurns = 200), 31)
            val bot = Rng(8)
            val buf = ActionBuffer()
            val acts = mutableListOf<Action>()
            while (!s.isOver) {
                Rules.legalActions(s, buf)
                val a = buf[bot.nextInt(buf.size)]
                Rules.apply(s, a)
                acts += a
            }
            return acts to s.stateHash()
        }
        val (a1, h1) = play()
        val (a2, h2) = play()
        assertEquals(a1, a2)
        assertEquals(h1, h2)
        assertEquals(h1, Game.replay(CK.config(3, BoardLayout.RANDOM, maxTurns = 200), 31, a1).state.stateHash())
        // Age of Chivalry state is part of the hash.
        val s = CK.playState()
        val h = s.stateHash()
        s.chivalry!!.barbarianPos = 3
        assertNotEquals(h, s.stateHash())
        assertEquals(s.stateHash(), s.copy().stateHash())
    }

    @Test
    fun `no development cards in Age of Chivalry`() {
        val s = CK.playState()
        CK.roll(s, 6, 6)
        T.giveAll(s, 0, Cost.DEV_CARD, 3)
        assertTrue(CK.legal(s).none { it.type == ActionType.BUY_DEVELOPMENT_CARD })
    }
}
