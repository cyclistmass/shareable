package com.mauro.wgbg

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.engine.ChivalryCost
import wgbg.engine.Cost
import wgbg.engine.Progress
import wgbg.engine.Track

/** One thing you can build or buy and what it costs, by card index (resources 0..4, commodities 5..7). */
data class BuildCost(val name: String, val cards: IntArray, val note: String? = null)

/**
 * The build map (Mauro: "a build map for when we start making themes"): every piece and its cost, read from the
 * engine's own cost tables so a theme that changes costs (#79) shows here as it is played.
 */
fun buildCosts(chivalry: Boolean): List<BuildCost> = buildList {
    add(BuildCost("Road", Cost.ROAD))
    add(BuildCost("Homestead", Cost.SETTLEMENT, "1 point"))
    add(BuildCost("Town", Cost.CITY, "2 points; replaces a homestead"))
    if (!chivalry) add(BuildCost("Fortune card", Cost.DEV_CARD))
    if (chivalry) {
        add(BuildCost("Knight", ChivalryCost.KNIGHT, "raised at a corner on your roads, level 1"))
        add(BuildCost("Promote a knight", ChivalryCost.PROMOTE, "level 3 needs Crown improvement 3"))
        add(BuildCost("Supply a knight", ChivalryCost.ACTIVATE, "a supplied knight defends and can act"))
        add(BuildCost("Ramparts", ChivalryCost.WALL, "on a town: hold 2 more cards on a 7"))
    }
}

/** Our names for the five producing terrains, in engine resource order (not the original game's, #91). */
val TERRAIN_NAMES = listOf("Woodland", "Clay pit", "Meadow", "Grainfield", "Quarry")

/** Where each card comes from (card index order). */
val CARD_SOURCES = TERRAIN_NAMES + listOf("Towns on ${TERRAIN_NAMES[0]}", "Towns on ${TERRAIN_NAMES[2]}", "Towns on ${TERRAIN_NAMES[4]}")

/** The three progress decks in engine card order: (deck name, track, first card, last card). */
private val DECKS = listOf(Triple("Academy", Track.SCIENCE, 0..9), Triple("Guilds", Track.TRADE, 10..15), Triple("Crown", Track.POLITICS, 16 until Progress.COUNT))

private val GUIDE_INK = Color(0xFFEDE6D8)
private val GUIDE_GOLD = Color(0xFFF1D58A)

/**
 * The card guide (Mauro: "a map in the menu with the cards and what they are"): every card, where it comes from or
 * what it does, and the build costs. Shows the ruleset being played ([chivalry]) in the art of the chosen theme
 * ([pirate]), read through [GuideArt] so a future theme adds its art in one place.
 */
@Composable
fun CardGuide(chivalry: Boolean, pirate: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    @Composable fun heading(t: String) = Text(t, color = GUIDE_GOLD, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    @Composable fun card(i: Int, w: Int = 30) = Image(artPainter(ALL_CARD_ART[i]), ALL_CARD_NAMES[i], Modifier.size(width = w.dp, height = (w * 1.4f).dp))
    // A card face at about guide size (56x78 dp, #71): a progress card's cream panel, or (Fortune) a knight card.
    @Composable fun face(name: String, edge: Color, art: Int, small: String? = null, plain: Boolean = false) {
        if (plain) Image(artPainter(art), name, Modifier.size(width = 56.dp, height = 78.dp))
        else ProgressCardFace(name, edge, art, small, 56, 78)
    }
    Column(modifier.widthIn(max = 720.dp).fillMaxHeight().brassEdgedWood().verticalScroll(rememberScrollState()).padding(16.dp).testTag("card-guide"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Card guide", color = GUIDE_GOLD, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            WoodButton("Close", "guide-close", onClick = onClose)
        }
        heading("Cards")
        val kinds = if (chivalry) ALL_CARD_ART.size else CARD_ART.size
        // Wraps on a narrow screen: at 640 dp wide (CI's screen) the eighth card was squeezed to a column of letters.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until kinds) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(74.dp)) {
                card(i, 56)
                Text(ALL_CARD_NAMES[i], color = GUIDE_INK, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(CARD_SOURCES[i], color = GUIDE_INK.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 11.sp)
            }
        }
        heading("Build")
        for (b in buildCosts(chivalry)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("cost-${b.name}")) {
            Text(b.name, color = GUIDE_INK, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(190.dp)) {
                for (i in b.cards.indices) repeat(b.cards[i]) { card(i) }
            }
            b.note?.let { Text(it, color = GUIDE_INK.copy(alpha = 0.75f), fontSize = 11.sp) }
        }
        if (chivalry) {
            Text("Improve your towns: the next level costs as many commodities as its number (level 3 costs 3): " +
                "Guilds Fabric, Crown Silver, Academy Parchment. Level 3 gives the deck's power; level 4 first wins its Capital.",
                color = GUIDE_INK.copy(alpha = 0.85f), fontSize = 12.sp)
            heading("Progress cards")
            for ((deck, track, range) in DECKS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    card(Track.commodity(track), 26)
                    Text("$deck · ${ALL_CARD_NAMES[Track.commodity(track)]}", color = TRACK_COLORS[track], fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                for (c in range) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, modifier = Modifier.testTag("progress-row-$c")) {
                    face(PROGRESS_NAMES[c], TRACK_COLORS[track], progressArt(c), progressHint(c))
                    Column {
                        Text(PROGRESS_NAMES[c], color = GUIDE_INK, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(150.dp))
                        Text(progressHint(c), color = GUIDE_INK.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
            heading("Knights")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (lv in 1..3) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(74.dp).testTag("guide-knight-$lv")) {
                    Image(artPainter(GuideArt.knightCard(lv, pirate)), "Knight level $lv", Modifier.size(width = 56.dp, height = 78.dp))
                    Text("Strength $lv", color = GUIDE_INK, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            heading("Fortune cards")
            for (c in FORTUNE.indices) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, modifier = Modifier.testTag("fortune-row-$c")) {
                face(FORTUNE[c], Wood.BRASS, GuideArt.fortuneArt(c, pirate), FORTUNE_HINTS[c], plain = !LocalArtChoice.current.space(ArtObject.CARDS))
                Column {
                    Text(FORTUNE[c], color = GUIDE_INK, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(150.dp))
                    Text(FORTUNE_HINTS[c], color = GUIDE_INK.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
