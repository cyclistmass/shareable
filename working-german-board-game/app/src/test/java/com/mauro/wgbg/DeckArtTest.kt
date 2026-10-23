package com.mauro.wgbg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wgbg.engine.Commodity
import wgbg.engine.DevCard
import wgbg.engine.Progress
import wgbg.engine.Resource
import wgbg.engine.Track

/**
 * Every progress deck shows its own commodity, colour, back and Capital in every theme, and every progress and Fortune
 * card lands on the right face. Space once put the coin back on Guilds (paid in cloth) and the cloth back on Crown,
 * and the 3D board stood Crown's Capital on the Academy's town. A new theme (Edo is next) must add its rows here.
 */
class DeckArtTest {
    private fun name(id: Int) = R.drawable::class.java.fields.first { it.getInt(null) == id }.name

    /** One row per deck, from the rules: the commodity it's paid in and the word its art files use. */
    private data class Deck(val track: Int, val name: String, val commodity: Int, val word: String, val colour: Long,
                            val gate: Int, val spaceBack: String, val spaceCards: List<String>)

    private val decks = listOf(
        Deck(Track.TRADE, "Guilds", Commodity.CLOTH, "guilds", 0xFFF2EDE0, 3, "nano_alliance_back",
            listOf("dock_accord", "cargo_audit", "exchange_envoy", "jump_manifest", "supply_signal", "rare_cargo")),
        Deck(Track.POLITICS, "Crown", Commodity.COIN, "crown", 0xFF3F7FC4, 4, "platinum_exchange_back",
            listOf("drone_override", "free_orbits", "crew_transfer", "lane_redirect", "quiet_intercept", "cargo_embargo",
                "signal_listener", "ranger_rally", "orbital_banquet")),
        Deck(Track.SCIENCE, "Academy", Commodity.PAPER, "academy", 0xFF3E8E48, 5, "data_network_back",
            listOf("orbit_calibrator", "gravity_lift", "shieldwright", "signal_swap", "spore_bloom", "habitat_upgrade",
                "core_cutters", "open_archive", "lane_survey", "field_promotion")),
    )

    /** What each Space deck back pictures (art catalog "art" field): the commodity card it's drawn from. */
    private val spaceBackShows = mapOf("data_network_back" to "data_slate", "nano_alliance_back" to "nano_cloth",
        "platinum_exchange_back" to "platinum_coin")
    private val spaceCommodity = mapOf(Commodity.PAPER to "data_slate", Commodity.CLOTH to "nano_cloth", Commodity.COIN to "platinum_coin")

    /** Every theme the app offers needs its expectations below; a new one fails here until it has them. */
    private val themes = listOf(ArtSet.DEFAULT, ArtSet.PIRATE, ArtSet.SPACE)

    @Test
    fun `every theme is covered`() = assertEquals(ArtSet.entries.toSet(), themes.toSet(), "add the new theme's deck rows")

    @Test
    fun `each deck is named, coloured and paid as the rules say`() {
        assertEquals(Track.COUNT, decks.size)
        for (d in decks) {
            assertEquals(d.name, DECK_NAMES[d.track])
            assertEquals(d.commodity, Track.commodity(d.track), d.name)
            assertEquals(d.colour, TRACK_COLORS[d.track].value.toLong() ushr 32, d.name)
        }
    }

    @Test
    fun `all 25 progress cards sit in their deck and show its face in every theme`() {
        val seen = mutableSetOf<Int>()
        for (d in decks) {
            val cards = (0 until Progress.COUNT).filter { Progress.track(it) == d.track }
            assertEquals(d.spaceCards.size, cards.size, d.name)
            seen += cards
            for ((i, c) in cards.withIndex()) for (theme in themes) {
                val art = ThemeArt.progress(ArtChoice(theme), c)
                if (ArtChoice(theme).space(ArtObject.CARDS)) assertEquals("space_card_${d.spaceCards[i]}", name(art), "${PROGRESS_NAMES[c]} in $theme")
                else assertEquals(deckArt(d.track), art, "${PROGRESS_NAMES[c]} in $theme shows ${d.name}'s commodity painting")
            }
        }
        assertEquals((0 until Progress.COUNT).toSet(), seen)
    }

    @Test
    fun `each deck's draw pile shows its own commodity in every theme`() {
        for (d in decks) for (theme in themes) {
            val choice = ArtChoice(theme)
            val art = ThemeArt.namedCard(choice, d.name, deckArt(d.track))!!
            if (choice.space(ArtObject.CARDS)) {
                assertEquals("space_card_${d.spaceBack}", name(art), "${d.name} back in $theme")
                assertEquals(spaceCommodity.getValue(d.commodity), spaceBackShows.getValue(d.spaceBack), "${d.name}'s back pictures its commodity")
                assertEquals("space_card_${spaceCommodity.getValue(d.commodity)}", name(ThemeArt.resource(choice, d.commodity)))
            } else {
                assertEquals(deckArt(d.track), art, "${d.name} pile in $theme")
                assertTrue(art !in ALL_CARD_ART, "${d.name} pile would show a Ledger card's own title")
            }
        }
    }

    @Test
    fun `each deck's Capital is its own on the 2D board, the 3D board and in Space`() {
        for (d in decks) {
            val i = BuildingSprites.capitalIndex(d.track)
            for (pirate in listOf(false, true)) {
                val piece = BuildingArt.capitals(pirate)[i]
                assertTrue("capital_${d.word}" in name(piece.body[0]), "${d.name} 2D Capital (pirate=$pirate): ${name(piece.body[0])}")
            }
            assertEquals("capital_${d.word}", liveCapital(d.track), "${d.name} 3D Capital")
            assertTrue(d.word in SpaceArt.capital(d.track).prefix, "${d.name} Space Capital: ${SpaceArt.capital(d.track).prefix}")
            assertTrue(d.word in SpaceArt.capital(BuildingSprites.CAPITAL_ORDER[i]).prefix, "${d.name} Space Capital in the 2D list")
        }
    }

    @Test
    fun `each deck's gate on the event die shows that deck in every theme`() {
        for (d in decks) {
            // Default and Pirate art number the gates 4 Guilds, 5 Academy, 6 Crown; Space 4 Academy, 5 Crown, 6 Guilds.
            val classic = mapOf("guilds" to 4, "academy" to 5, "crown" to 6).getValue(d.word)
            val space = mapOf("academy" to 4, "crown" to 5, "guilds" to 6).getValue(d.word)
            for (theme in themes) {
                val choice = ArtChoice(theme)
                val art = name(ThemeArt.event(choice, d.gate, 0))
                val want = if (choice.space(ArtObject.DICE)) "face_$space" else "_$classic"
                assertTrue(art.contains(want), "${d.name} gate in $theme: $art")
            }
        }
    }

    @Test
    fun `the five Fortune cards and their back show the right art`() {
        assertEquals(listOf("Sentinel", "Windfall", "Corner the Market", "Trailblazers", "Landmark"), FORTUNE)
        val space = listOf("space_marshal", "full_hold", "trade_beacon", "ferry_lanes", "new_horizon")
        val classic = listOf(R.drawable.card_fortune_sentinel, R.drawable.card_fortune_windfall, R.drawable.card_fortune_corner,
            R.drawable.card_fortune_trailblazers, R.drawable.card_fortune_landmark)
        for (c in 0 until DevCard.COUNT) for (theme in themes) {
            val choice = ArtChoice(theme)
            val art = ThemeArt.fortune(choice, c)
            if (choice.space(ArtObject.CARDS)) assertEquals("space_card_${space[c]}", name(art), "${FORTUNE[c]} in $theme")
            else if (c == DevCard.KNIGHT && theme == ArtSet.PIRATE) assertTrue("knight" in name(art), "${FORTUNE[c]} in $theme: ${name(art)}")
            else assertEquals(classic[c], art, "${FORTUNE[c]} in $theme")
        }
        assertEquals("space_card_card_back", name(SpaceArt.cardBack))
    }
}
