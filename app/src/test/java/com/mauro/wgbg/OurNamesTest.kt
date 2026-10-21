package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Players never see the original game's names (AGENTS.md › Names). 0.3.4 found "Spy: take one of Blue's progress cards"
 * on the Informer's sheet; this reads every string in the app, engine, bots and transport so no original name comes
 * back. Code identifiers (`Phase.SPY`, `Resource.ORE`) and comments may keep the standard terms; only quoted text counts.
 */
class OurNamesTest {
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
    fun `the check catches what it should and passes our own names`() {
        for (bad in listOf("\"Spy: take one\"", "\"Move the Robber\"", "\"the Barbarians attack\"", "\"3 Ore\"", "\"Year of Plenty\""))
            assertTrue(STRING.findAll(bad).any { FORBIDDEN.containsMatchIn(it.value) }, bad)
        for (ok in listOf("\"Informer: take one\"", "\"Move the Bandit\"", "\"the Raiders attack\"", "\"3 Stone\"", "\"Windfall\"", "\"before\"", "\"Spyglass\""))
            assertTrue(STRING.findAll(ok).none { FORBIDDEN.containsMatchIn(it.value) }, ok)
    }

    private companion object {
        val STRING = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        /** The required "Not affiliated with … Catan GmbH" notice may name it. */
        const val DISCLAIMER = "Catan GmbH"
        /** Original names with ours in brackets: resources, pieces, the robber, development and progress cards, the barbarians. */
        val FORBIDDEN = Regex("\\b(" + listOf(
            "Catan", "Brick", "Wool", "Ore", "Lumber",                                  // Clay, Fleece, Stone, Timber
            "Settlements?", "Robber",                                                   // Homestead, Bandit
            "Development cards?", "Year of Plenty", "Road Building", "Monopoly",        // Fortune cards, Windfall, Trailblazers, Corner the Market
            "Barbarians?", "Metropolis(es)?", "City Walls?",                            // Raiders, Capital, Ramparts
            "Spy", "Diplomat", "Saboteur", "Bishop", "Deserter", "Wedding", "Warlord",  // Informer, Envoy, Wrecker, …
            "Alchemist", "Intrigue", "Merchant Fleet", "Master Merchant", "Commercial Harbor",
        ).joinToString("|") + ")\\b")
    }
}
