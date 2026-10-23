package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.ActionType
import wgbg.engine.ChivalryActionType
import java.io.File

/**
 * Players only ever read our own names (AGENTS.md › Names, #85, 0.3.5 IP check): no card name, card text, move line,
 * rules doc or other string in the app, engine, bots and transport may use the original game's names. Code identifiers
 * keep the standard terms (Progress.SPY, Resource.ORE), which is how one slipped through once: the Informer's prompt
 * read "Spy: take one of …". Comments may keep them too; only quoted text counts.
 */
class OurNamesTest {
    private fun hits(texts: Collection<String>) = texts.filter { ORIGINAL.containsMatchIn(it) }

    @Test
    fun `no player-facing text uses the original game's names`() {
        val roots = listOf("src/main", "../engine/src/main", "../ai/src/main", "../net/src/main").map(::File)
        for (r in roots) assertTrue(r.isDirectory, "${r.absolutePath} is where the sources are")
        val found = mutableListOf<String>()
        for (f in roots.flatMap { it.walkTopDown() }.filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.startsWith("import ")) return@forEachIndexed
                val texts = if (f.extension == "xml") listOf(line) else STRING.findAll(line).map { it.value }.toList()
                for (t in texts) if (DISCLAIMER !in t) FORBIDDEN.find(t)?.let { found += "${f.name}:${i + 1}: \"${it.value}\" in $t" }
            }
        }
        assertTrue(found.isEmpty(), "original names in player-facing text:\n" + found.joinToString("\n"))
    }

    @Test
    fun `the source scan catches what it should and passes our own names`() {
        for (bad in listOf("\"Spy: take one\"", "\"Move the Robber\"", "\"the Barbarians attack\"", "\"3 Ore\"", "\"Year of Plenty\""))
            assertTrue(STRING.findAll(bad).any { FORBIDDEN.containsMatchIn(it.value) }, bad)
        for (ok in listOf("\"Informer: take one\"", "\"Move the Bandit\"", "\"the Raiders attack\"", "\"3 Stone\"", "\"Windfall\"", "\"before\"", "\"Spyglass\""))
            assertTrue(STRING.findAll(ok).none { FORBIDDEN.containsMatchIn(it.value) }, ok)
    }

    @Test
    fun `card names and card text`() {
        val cards = PROGRESS_NAMES + PROGRESS_HINTS + FORTUNE + FORTUNE_HINTS + ALL_CARD_NAMES + TERRAIN_NAMES + DECK_NAMES +
            CARD_SOURCES + (buildCosts(false) + buildCosts(true)).flatMap { listOfNotNull(it.name, it.note) } +
            ArtObject.entries.map { it.label } + DEFENDER
        assertEquals(emptyList<String>(), hits(cards))
    }

    @Test
    fun `every move's line in the game log`() {
        val lines = (ActionType.entries + ChivalryActionType.entries).map { describe(it) }
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
    fun `Mauro's names for the Raiders' reward and the Peddler's piece (0_3_5)`() {
        assertEquals("Defender of the Realm", DEFENDER)
        assertEquals(true, "Put the Trader on a tile" in progressHint(wgbg.engine.Progress.MERCHANT))
        assertEquals("Trader", ArtObject.MERCHANT.label)
        val docs = File("../docs/rules").listFiles()!!.joinToString("\n") { it.readText() }
        assertEquals(true, "Defender of the Realm" in docs && "the Trader" in docs)
    }

    @Test
    fun `the turn report names the Defender of the Realm when the knights beat the Raiders`() {
        val s = wgbg.engine.Rules.newGame(wgbg.engine.GameConfig(expansion = wgbg.engine.Expansion.CHIVALRY), 5)
        val tracker = TurnTracker(listOf("A", "B", "C", "D"))
        // Whatever the move, a Defender card that lands in a hand is named (the real one comes with a roll that lands the Raiders).
        tracker.apply(s, s.currentPlayer, wgbg.engine.Action.roll(3, 4)) { s.chivalry!!.defenderVp[2]++ }
        assertEquals(listOf("C won the Defender of the Realm: 1 point"), tracker.current!!.events.filter { "Defender" in it })
    }

    @Test
    fun `the check itself catches the names`() {
        for (t in listOf("Spy: take a card", "the Robber", "Longest Road", "a Settlement", "Cities & Knights", "2 Ore", "the merchant"))
            assertEquals(listOf(t), hits(listOf(t)), t)
        for (t in listOf("Informer", "the Bandit", "Grand Highway", "a Homestead", "Inventory", "Catanatron", "Stone", "a knight",
                "Defender of the Realm", "the Trader"))
            assertEquals(emptyList<String>(), hits(listOf(t)), t)
    }

    companion object {
        /** The original game's names: the game and its makers, its resources, pieces, awards, and card names. */
        val ORIGINAL = Regex(listOf(
            "catan(?!atron)", "settlers", "siedler", "seafarers", "kosmos", "teuber", "cities\\s*(&|and)\\s*knights",
            "brick", "lumber", "wool", "sheep", "\\bore\\b", "wheat", "settlement", "\\bcity\\b", "\\bcities\\b",
            "development card", "victory point", "longest road", "largest army", "robber", "barbarian", "metropol",
            "city wall", "aqueduct", "fortress", "trading house", "year of plenty", "monopoly", "road building",
            "alchemist", "\\bcrane", "engineer", "\\binventor\\b", "irrigation", "medicine", "\\bmining", "\\bprinter",
            "\\bsmith\\b", "commercial harbo", "merchant", "bishop", "constitution", "deserter",
            "diplomat", "intrigue", "saboteur", "\\bspy\\b", "warlord", "wedding",
        ).joinToString("|"), RegexOption.IGNORE_CASE)

        /** A quoted string literal, for the whole-source scan. */
        private val STRING = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        /** The required "Not affiliated with … Catan GmbH" notice may name it. */
        private const val DISCLAIMER = "Catan GmbH"
        /**
         * The whole-source scan's list, narrower than [ORIGINAL] since it reads every string in the code (keys and tags too):
         * original names with ours in brackets: resources, pieces, the robber, development and progress cards, the barbarians.
         */
        private val FORBIDDEN = Regex("\\b(" + listOf(
            "Catan", "Brick", "Wool", "Ore", "Lumber",                                  // Clay, Fleece, Stone, Timber
            "Settlements?", "Robber",                                                   // Homestead, Bandit
            "Development cards?", "Year of Plenty", "Road Building", "Monopoly",        // Fortune cards, Windfall, Trailblazers, Corner the Market
            "Barbarians?", "Metropolis(es)?", "City Walls?",                            // Raiders, Capital, Ramparts
            "Spy", "Diplomat", "Saboteur", "Bishop", "Deserter", "Wedding", "Warlord",  // Informer, Envoy, Wrecker, …
            "Alchemist", "Intrigue", "Merchant Fleet", "Master Merchant", "Commercial Harbor",
        ).joinToString("|") + ")\\b")
    }
}
