package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wgbg.engine.ActionType
import wgbg.engine.CkActionType
import java.io.File

/**
 * Players only ever read our own names (AGENTS.md, #85, 0.3.5 IP check): no card name, card text, move line or rules
 * doc may use the original game's names. Code identifiers keep the standard terms (Progress.SPY), which is how one
 * slipped through once: the Informer's prompt read "Spy: take one of …".
 */
class OurNamesTest {
    private fun hits(texts: Collection<String>) = texts.filter { ORIGINAL.containsMatchIn(it) }

    @Test
    fun `card names and card text`() {
        val cards = PROGRESS_NAMES + PROGRESS_HINTS + FORTUNE + FORTUNE_HINTS + ALL_CARD_NAMES + TERRAIN_NAMES + DECK_NAMES +
            CARD_SOURCES + (buildCosts(false) + buildCosts(true)).flatMap { listOfNotNull(it.name, it.note) }
        assertEquals(emptyList<String>(), hits(cards))
    }

    @Test
    fun `every move's line in the game log`() {
        val lines = (ActionType.entries + CkActionType.entries).map { describe(it) }
        assertEquals(emptyList<String>(), hits(lines))
    }

    @Test
    fun `the rules and card docs`() {
        val docs = File("../docs/rules").listFiles { f -> f.extension == "md" }!!.toList()
        assertEquals(true, docs.size >= 3, "docs/rules has the base rules, Age of Chivalry and the cards")
        val lines = docs.flatMap { f -> f.readLines().mapIndexed { i, l -> "${f.name}:${i + 1}: $l" } }
        assertEquals(emptyList<String>(), hits(lines))
    }

    @Test
    fun `docs rules cards md quotes every card's text as the game shows it, with its copies`() {
        val doc = File("../docs/rules/cards.md").readText().replace("\r\n", "\n")
        val cards = FORTUNE.indices.map { Triple(FORTUNE[it], FORTUNE_HINTS[it], wgbg.engine.DevCard.DECK[it]) } +
            PROGRESS_NAMES.indices.map { Triple(PROGRESS_NAMES[it], PROGRESS_HINTS[it], wgbg.engine.Progress.DECK[it]) }
        for ((name, hint, copies) in cards) assertEquals(true, "### $name ($copies)\n> $hint\n" in doc || "#### $name ($copies)\n> $hint\n" in doc,
            "$name ($copies): $hint")
    }

    @Test
    fun `the check itself catches the names`() {
        for (t in listOf("Spy: take a card", "the Robber", "Longest Road", "a Settlement", "Cities & Knights", "2 Ore"))
            assertEquals(listOf(t), hits(listOf(t)), t)
        for (t in listOf("Informer", "the Bandit", "Grand Highway", "a Homestead", "Inventory", "Catanatron", "Stone", "a knight"))
            assertEquals(emptyList<String>(), hits(listOf(t)), t)
    }

    companion object {
        /** The original game's names: the game and its makers, its resources, pieces, awards, and card names. */
        val ORIGINAL = Regex(listOf(
            "catan(?!atron)", "settlers", "siedler", "seafarers", "kosmos", "teuber", "cities\\s*(&|and)\\s*knights",
            "brick", "lumber", "wool", "sheep", "\\bore\\b", "wheat", "settlement", "\\bcity\\b", "\\bcities\\b",
            "development card", "victory point", "longest road", "largest army", "robber", "barbarian", "metropol",
            "city wall", "aqueduct", "fortress", "trading house", "defender", "year of plenty", "monopoly", "road building",
            "alchemist", "\\bcrane", "engineer", "\\binventor\\b", "irrigation", "medicine", "\\bmining", "\\bprinter",
            "\\bsmith\\b", "commercial harbo", "master merchant", "merchant fleet", "bishop", "constitution", "deserter",
            "diplomat", "intrigue", "saboteur", "\\bspy\\b", "warlord", "wedding",
        ).joinToString("|"), RegexOption.IGNORE_CASE)
    }
}
