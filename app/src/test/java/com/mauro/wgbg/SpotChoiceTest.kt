package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wgbg.engine.Action
import wgbg.engine.ChivalryCost
import wgbg.engine.Cost
import wgbg.engine.Expansion
import wgbg.engine.GameConfig
import wgbg.engine.Phase
import wgbg.engine.Rules

/** #124: a spot that takes more than one thing lists each with its icon, name and cost. */
class SpotChoiceTest {
    private val names = listOf("You", "Blue", "Orange")

    private fun midTurn() = Rules.newGame(GameConfig(expansion = Expansion.CHIVALRY, numPlayers = 3), 7).also { s ->
        while (s.phase != Phase.PLAY_TURN) Rules.apply(s, Rules.legalActions(s).first())
    }

    @Test
    fun `a town, Ramparts and a knight at one corner each get a row with its own name, icon and cost`() {
        val s = midTurn()
        val town = Action.buildCity(3)
        val wall = Action.buildCityWall(3)
        val knight = Action.buildKnight(3)
        val rows = spotChoices(s, listOf(town, wall, knight), names)
        assertEquals(listOf("Town", "Ramparts", "New knight"), rows.map { it.name })
        assertEquals(listOf(MoveIcon.TOWN, MoveIcon.RAMPARTS, MoveIcon.KNIGHT), rows.map { it.icon })
        assertArrayEquals(Cost.CITY, rows[0].cost)
        assertArrayEquals(ChivalryCost.WALL, rows[1].cost)
        assertArrayEquals(ChivalryCost.KNIGHT, rows[2].cost)
    }

    @Test
    fun `free builds say free, not a cost`() {
        val s = midTurn().also { it.freeRoads = 2 }
        assertNull(spotChoices(s, listOf(Action.buildRoad(4)), names)[0].cost)
        val setup = Rules.newGame(GameConfig(numPlayers = 3), 7)
        assertNull(spotChoices(setup, listOf(Action.buildSettlement(4)), names)[0].cost)
    }
}
