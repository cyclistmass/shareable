package com.mauro.wgbg

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.engine.CARD_TYPES
import wgbg.engine.GameState
import wgbg.engine.Track

/** Town-improvement bars in the order of the design: Guilds, Academy, Crown, each in its deck colour ([TRACK_COLORS]). */
internal val IMPROVEMENT_BARS get() = listOf(Track.TRADE to TRACK_COLORS[Track.TRADE], Track.SCIENCE to TRACK_COLORS[Track.SCIENCE], Track.POLITICS to TRACK_COLORS[Track.POLITICS])

/** Segments filled on a 4-segment improvement bar for an engine level 0..5. */
fun barSegments(level: Int) = level.coerceIn(0, 4)

/**
 * Board A's player list (top-left): colour, name, points and cards for everyone; in Age of
 * Chivalry three 4-segment bars show each player's town improvements. Other players' hidden
 * points (Landmark cards) are not shown.
 */
@Composable
fun PlayerStrip(s: GameState, me: Int, names: List<String>, version: Int, modifier: Modifier = Modifier,
                /** The turn whose roll the mini cards show (#47); null shows none. */
                roll: TurnReport? = null,
                /** False inside the one left side panel (#73), which draws the frame for both halves. */
                framed: Boolean = true,
                /** Each player's cameo id (0.3.5), or null for the plain colour dot. */
                cameos: List<String?> = emptyList()) {
    version // the state object is mutated in place; this changing value makes the strip redraw after each move
    // Taller than the height it's given (the room above the dice: a short screen, a large font, the Raiders warning, six
    // seats), the whole list shrinks to fit, so every row stays in sight; it never scrolls White away (0.3.4, Mauro).
    ShrinkToFit(modifier) {
    Column((if (framed) Modifier.brassEdgedWood() else Modifier).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp).testTag("player-strip")
        // Named for screen readers and for tools/ci_reach.sh, which checks the dice row never overlaps it.
        .semantics { contentDescription = "Players"; testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        // Age of Chivalry: how near the Raiders are (a ship marker on a 7-step track).
        s.chivalry?.let { chivalry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("raiders-track")) {
                Text("Raiders", color = Color(0xFFE57373), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp))
                for (i in 0 until wgbg.engine.ChivalryPiece.BARBARIAN_TRACK) Box(
                    Modifier.size(width = 11.dp, height = 8.dp)
                        .background(if (i < chivalry.barbarianPos) Color(0xFFC0392B) else Color(0x73000000), RoundedCornerShape(2.dp)),
                )
                // Raiders' strength vs every active knight, on the track's own line (Mauro): green while the knights would hold.
                val raiders = wgbg.engine.Chivalry.barbarianStrength(s)
                val knights = (0 until s.numPlayers).sumOf { wgbg.engine.Chivalry.activeKnightStrength(s, it) }
                Text("$raiders vs $knights", fontSize = 13.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).testTag("raiders-vs-knights"),
                    color = if (knights >= raiders) Color(0xFF8BD18B) else Color(0xFFFF6B5E))
            }
            // On your turn, 3 steps or fewer out: say so, and what's at stake (#92).
            raidersWarning(s, me)?.let { Text(it, color = Color(0xFFFFB4A8), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(max = 250.dp).padding(top = 1.dp, bottom = 2.dp).testTag("raiders-warning")) }
        }
        for (p in 0 until s.numPlayers) {
            val turn = !s.isOver && p == s.currentPlayer
            Row(
                Modifier.then(if (turn) Modifier.border(1.dp, Color(0xFFC9A04A), RoundedCornerShape(8.dp)) else Modifier).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The score sits in the player's colour dot, so the strip needs no score column (Mauro). The dot is a fixed
                // size, so its number is sized to the dot, not the font scale (at 1.3 it shrank to a speck); 10 and more
                // shrink a step rather than wrap (0.3.4).
                val fs = LocalDensity.current.fontScale
                val score = @Composable { max: Float, min: Float, ink: Color ->
                    Text("${if (p == me) s.victoryPoints(p) else s.publicVictoryPoints(p)}", fontSize = (max / fs).sp, fontWeight = FontWeight.Bold,
                        lineHeight = ((max + 2) / fs).sp, maxLines = 1, softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = (min / fs).sp, maxFontSize = (max / fs).sp), modifier = Modifier.padding(horizontal = 1.dp),
                        color = ink)
                }
                val cameo = cameos.getOrNull(p)?.takeIf { it in Cameos.PAINTED }
                // With a cameo (0.3.5, sheet B): the portrait in a ring of the player's colour, the score on a worn brass badge.
                if (cameo != null) Box(Modifier.size(30.dp).testTag("score-$p")) {
                    CameoDisc(cameo, PLAYER_COLORS[p], 30.dp)
                    Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 3.dp).size(17.dp).background(Wood.BRASS, CircleShape)
                        .border(1.dp, Wood.BRASS_EDGE, CircleShape), contentAlignment = Alignment.Center) { score(10f, 6f, Color(0xFF2B2118)) }
                }
                else Box(Modifier.size(26.dp).background(PLAYER_COLORS[p], CircleShape).border(1.dp, Color(0xFFF1D58A), CircleShape).testTag("score-$p"),
                    contentAlignment = Alignment.Center) {
                    score(14f, 9f, if (PLAYER_COLORS[p].luminance() > 0.35f) Color(0xFF2B2118) else Color.White)
                }
                Column(Modifier.width(74.dp)) {
                    // A long name (16 letters online) or a large font shrinks, then ends in "…", on one line (0.3.4).
                    Text(names[p], color = Color(0xFFEDE6D8), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp), modifier = Modifier.testTag("name-$p"))
                    val chivalry = s.chivalry
                    if (chivalry != null) for ((track, color) in IMPROVEMENT_BARS) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                        val filled = barSegments(chivalry.level(p, track))
                        for (i in 0 until 4) Box(Modifier.size(width = 14.dp, height = 4.dp).background(if (i < filled) color else Color(0x73000000), RoundedCornerShape(1.dp)))
                    }
                }
                // The card count, and under it (right of the upgrade bars) the road length and knights: a squiggle and a
                // shield, gold for whoever holds Longest Road / Largest Army (Mauro).
                Column(Modifier.width(64.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // A tiny card icon and the count; in Age of Chivalry "cards/progress", e.g. 8/3.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 10.dp, height = 14.dp).background(Color(0xFFEFE3C8), RoundedCornerShape(2.dp))
                        .border(1.dp, Color(0xFFC9A04A), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    // Red when a rolled 7 would make them discard half (Mauro).
                    Text(cardsLabel(s, p), color = if (overCardLimit(s, p)) Color(0xFFFF6B5E) else Color(0xFFEDE6D8), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 13.sp), modifier = Modifier.testTag("cards-$p"))
                }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatBadge(StatIcon.ROAD, s.roadLength[p], s.longestRoadOwner == p, "road-$p")
                        StatBadge(StatIcon.SHIELD, knightsShown(s, p), s.chivalry == null && s.largestArmyOwner == p, "army-$p")
                    }
                }
                // The last roll's cards, each overlapping the one before by half in one fixed slot, so the frame never
                // stretches or jumps however many came in (Mauro); past four, the rest are in the history.
                val minis = rollMinis(roll, p, me)
                Box(Modifier.size(width = MINI_SLOT, height = 30.dp).testTag("roll-minis-$p")) {
                    minis.take(MINI_MAX).forEachIndexed { i, m -> Box(Modifier.offset(x = MINI_STEP * i)) { MiniRollCard(m) } }
                }
            }
        }
    }
    }
}

/**
 * Lays out [content] at its natural height; when that is more than the height allowed, scales it down evenly (from the
 * top left) to fit, and takes up only the scaled size.
 */
@Composable
internal fun ShrinkToFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, c ->
        val child = measurables.first().measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val k = fitScale(child.height, if (c.hasBoundedHeight) c.maxHeight else Int.MAX_VALUE)
        layout((child.width * k).toInt().coerceIn(c.minWidth, c.maxWidth), (child.height * k).toInt().coerceIn(c.minHeight, c.maxHeight)) {
            child.placeWithLayer(0, 0) { scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
        }
    }
}

/** How much [ShrinkToFit] scales content [height] px tall into [max] px: 1 when it fits. */
internal fun fitScale(height: Int, max: Int): Float = if (height <= max || height <= 0) 1f else max.toFloat() / height


/** The knights number on a player's shield: Sentinels played (base game), active knight strength (Age of Chivalry). */
fun knightsShown(s: GameState, p: Int): Int = if (s.chivalry != null) wgbg.engine.Chivalry.activeKnightStrength(s, p) else s.knightsPlayed(p)

enum class StatIcon { ROAD, SHIELD }

/** A tiny drawn icon and a number; gold when the player holds that award. */
@Composable
fun StatBadge(icon: StatIcon, value: Int, held: Boolean, tag: String) {
    val ink = if (held) Color(0xFFF1D58A) else Color(0xFFCDBFA6)
    // Each badge has half of its 64 dp column, so two two-digit numbers at a large font shrink instead of wrapping (0.3.4).
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(STAT_BADGE_W).testTag(tag)) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val w = size.width; val h = size.height
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            when (icon) {
                StatIcon.ROAD -> drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.05f * w, 0.75f * h); cubicTo(0.3f * w, 0.1f * h, 0.45f * w, 1.0f * h, 0.7f * w, 0.35f * h); quadraticTo(0.82f * w, 0.1f * h, 0.95f * w, 0.25f * h) }, ink, style = stroke)
                StatIcon.SHIELD -> drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.12f * w, 0.1f * h); lineTo(0.88f * w, 0.1f * h); lineTo(0.88f * w, 0.5f * h)
                    quadraticTo(0.85f * w, 0.85f * h, 0.5f * w, 0.98f * h); quadraticTo(0.15f * w, 0.85f * h, 0.12f * w, 0.5f * h); close() }, ink, style = stroke)
            }
        }
        Text("$value", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 11.sp))
    }
}

/** Everything about one card, for the long-press view: its art, name, kind and what it's for. */
data class CardDetail(val art: Int, val title: String, val kind: String?, val lines: List<String>)

/** A resource or commodity card ([card] index): where it comes from and what it pays for, read from the build map. */
fun cardDetail(card: Int, chivalry: Boolean): CardDetail {
    val uses = buildCosts(chivalry).filter { card < it.cards.size && it.cards[card] > 0 }.map { "${it.name}: ${it.cards[card]}" }
    val improve = if (card >= CARD_TYPES) listOf("Improves the ${listOf("Academy", "Guilds", "Crown")[card - CARD_TYPES]} track of your towns") else emptyList()
    return CardDetail(ALL_CARD_ART[card], ALL_CARD_NAMES[card], if (card < CARD_TYPES) "Resource" else "Commodity",
        listOf("From: ${CARD_SOURCES[card]}") + (if (uses.isEmpty()) emptyList() else listOf("Pays for: " + uses.joinToString(", "))) + improve)
}

/** A progress card: its deck and its full meaning. */
fun progressDetail(c: Int): CardDetail = CardDetail(progressArt(c), PROGRESS_NAMES.getOrElse(c) { "Progress card" },
    "${DECK_NAMES[wgbg.engine.Progress.track(c)]} progress card", listOf(progressHint(c)))

/** The roll cards beside each player: up to [MINI_MAX], each overlapping the one before by half. */
private val MINI_STEP = 10.5.dp
private const val MINI_MAX = 4
private val MINI_SLOT = 21.dp + MINI_STEP * (MINI_MAX - 1)

/**
 * A progress card's art: its deck's commodity painting (Guilds Fabric, Crown Silver, Academy Parchment), not the
 * Ledger card, whose own title and seal would show through the named face.
 */
fun progressArt(card: Int): Int = deckArt(wgbg.engine.Progress.track(card))

/** A progress deck's picture ([track]), on its cards and its draw pile: the deck commodity's painting. */
fun deckArt(track: Int): Int = COMMODITY_PICTURE[wgbg.engine.Track.commodity(track) - CARD_TYPES]

/** Age of Chivalry deck colours by track: Guilds gold (trade), Crown blue (politics), Academy green (science). */
// Mauro: themed by commodity and its land, so the colour reminds you of the commodity (theme-spec.md, Commodity
// colours). 0.3.5: clear green, white and blue: Guilds / Fabric (sheep) white, Crown / Silver (mountains) blue, Academy /
// Parchment (forest) green. Wood and sheep are both green lands, so sheep take white; no orange.
internal val TRACK_COLORS = listOf(Color(0xFFF2EDE0), Color(0xFF3F7FC4), Color(0xFF3E8E48))

/** Name type size on a [w] dp card face: 8 sp, smaller only when the longest word would not fit on one line. */
fun cardNameSp(name: String, w: Int): Float {
    val longest = name.split(' ').maxOf { it.length }
    // Bold Alegreya Sans runs about 0.5 em a letter; the name has the face width less 14 dp of margins.
    return minOf(8f, (w - 14) / (longest * 0.5f))
}

/**
 * The drawn card face shared by a progress card in hand and the card guide (#4, #71): a cream panel edged in
 * [edge] (a deck's track colour, or brass for a Fortune card), the background [art] cropped behind it, the
 * card's [name] and, in small print, what it does ([small]).
 */
@Composable
fun ProgressCardFace(name: String, edge: Color, art: Int?, small: String? = null, w: Int = 56, h: Int = 78, modifier: Modifier = Modifier) {
    // Space renders are bare objects: they sit inset on a night-blue face instead of filling a cream one (as the hand's resource cards).
    val space = LocalArtChoice.current.space(ArtObject.CARDS)
    Box(modifier.size(width = w.dp, height = h.dp).then(if (space) Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF34405F), Color(0xFF151B2C))), RoundedCornerShape(5.dp))
            else Modifier.background(Color(0xFFEFE3C8), RoundedCornerShape(5.dp)))
        .clip(RoundedCornerShape(5.dp)).border(2.dp, edge, RoundedCornerShape(5.dp))) {
        val selectedArt = ThemeArt.namedCard(LocalArtChoice.current, name, art)
        if (selectedArt != null) Image(artPainter(selectedArt), null, if (space) Modifier.fillMaxWidth().fillMaxHeight(.72f).padding(4.dp) else Modifier.fillMaxSize(),
            contentScale = if (space) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(3.dp)
            .background(Color(0xE6EFE3C8), RoundedCornerShape(3.dp)).padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // A long single word (Trailblazers) gets smaller type rather than breaking mid-word (0.2.7 test).
            val nameSp = cardNameSp(name, w)
            Text(name, color = Color(0xFF2B2118), fontSize = nameSp.sp, fontWeight = FontWeight.Bold, lineHeight = (nameSp + 1f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // What it does, in small print on the card itself (Mauro); a tap still shows it large.
            if (small != null) Text(small, color = Color(0xFF4A3B2C), fontSize = 6.5.sp, lineHeight = 7.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/**
 * Your hand along the bottom edge (no frame): the illustrated cards you hold, each with its count; in Age of
 * Chivalry also your commodities and your progress cards (named, edged in their deck's colour) (#4).
 */
@Composable
fun HandRow(s: GameState, me: Int, version: Int, modifier: Modifier = Modifier, pace: Float = 1f,
            /** Fortune cards [me] may play now ([fortunePlayable]): a tap plays one (Fable review: cards, not worded buttons). */
            fortunePlayable: Set<Int> = emptySet(), onFortune: ((Int) -> Unit)? = null, fortuneArt: (Int) -> Int = { GuideArt.fortuneArt(it, false) }) {
    version // see PlayerStrip
    @Composable
    fun count(n: Int) = Text("$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(2.dp).background(Color(0xE62A1B10), CircleShape).border(1.dp, Color(0xFFC9A04A), CircleShape).padding(horizontal = 6.dp))
    // Tap a progress card to see what it does (Mauro: "add some hints on what a card does").
    var hint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay((5000L * pace).toLong()); hint = null } }
    // A long press on any card shows it big with everything about it (Mauro); a tap anywhere on it closes.
    var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CardDetail?>(null) }
    // It never stays up for good (#116: "staring at a full screen of a card description forever"): any tap on a card
    // in hand closes it too, and it closes by itself after a while.
    androidx.compose.runtime.LaunchedEffect(detail) { if (detail != null) { kotlinx.coroutines.delay((12000L * pace).toLong()); detail = null } }
    fun Modifier.longPressFor(d: CardDetail) = this.pointerInput(d) { detectTapGestures(onTap = { detail = null }, onLongPress = { detail = d }) }
    @Composable
    fun namedCard(name: String, edge: Color, n: Int, tag: String, w: Int, h: Int, small: String? = null, art: Int? = null, long: CardDetail? = null,
                  plain: Boolean = false, onTap: (() -> Unit)? = null) {
        // The tap handler outlives this call: it must run today's onTap, not the one from the card's first touch
        // (the #122 stale-tap bug, same pattern).
        val tapNow = androidx.compose.runtime.rememberUpdatedState(onTap)
        Box(contentAlignment = Alignment.TopEnd,
            modifier = Modifier.testTag(tag).then(if (onTap != null || long != null) Modifier.pointerInput(long) {
                detectTapGestures(onTap = { if (detail != null) detail = null else tapNow.value?.invoke() }, onLongPress = { long?.let { detail = it } })
            } else Modifier)) {
            // A full card that names itself (a Ledger Fortune card) as it is; a progress card on its drawn face, themed
            // by its deck's commodity (Mauro): Guilds on Fabric, Crown on Silver, Academy on Parchment.
            if (plain && art != null) Image(artPainter(art), name, Modifier.size(width = w.dp, height = h.dp))
            else ProgressCardFace(name, edge, art, small, w, h)
            if (n > 1) count(n)
        }
    }
    Column(modifier) {
    detail?.let { d ->
        Row(Modifier.padding(bottom = 6.dp).brassEdgedWood().padding(12.dp).widthIn(max = 520.dp).pointerInput(d) { detectTapGestures { detail = null } }.testTag("card-detail"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(artPainter(ThemeArt.namedCard(LocalArtChoice.current, d.title, d.art)!!), d.title, Modifier.size(width = 100.dp, height = 140.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.title, color = Color(0xFFF1D58A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                d.kind?.let { Text(it, color = Color(0xFFCDBFA6), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                for (line in d.lines) Text(line, color = Color(0xFFEDE6D8), fontSize = 13.sp)
                Text("Tap to close", color = Color(0xFFCDBFA6), fontSize = 11.sp)
            }
        }
    }
    hint?.let { Text(it, color = Color(0xFFEDE6D8), fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 4.dp).brassEdgedWood(2.dp).padding(horizontal = 10.dp, vertical = 6.dp).testTag("card-hint")) }
    val cards = handCardCount(s, me)
    androidx.compose.foundation.layout.BoxWithConstraints {
    // #127: cards overlap 10% (5 of 50 dp); on a small screen they fan closer so 2-3 or more still show, each keeping a
    // tappable slice with its count, and the row scrolls only past that (Mauro, 0.3.4: one card wide at 640x320).
    Row(Modifier.testTag("hand-row").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(handOverlap(maxWidth.value, cards).dp), verticalAlignment = Alignment.Bottom) {
        // Each card sits over the next, so the count in its top-right corner stays in view (#127).
        var z = 0
        for (r in 0 until CARD_TYPES) {
            val n = s.hand(me, r)
            if (n == 0) continue
            Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.zIndex(-(z++).toFloat()).longPressFor(cardDetail(r, s.chivalry != null))) {
                Image(artPainter(CARD_ART[r]), RESOURCE_NAMES[r], Modifier.size(width = 50.dp, height = 70.dp))
                count(n)
            }
        }
        // Fortune cards (base game) as cards in the hand, dimmed when they can't be played; a tap plays one, a long press
        // shows it large. They were worded buttons beside the move buttons before (Fable review 2.5).
        if (s.chivalry == null) for (c in 0 until wgbg.engine.DevCard.COUNT) {
            val n = s.devHand[me * wgbg.engine.DevCard.COUNT + c]
            if (n == 0) continue
            val now = c in fortunePlayable && onFortune != null
            Box(Modifier.zIndex(-(z++).toFloat()).dimmed(!now && c != wgbg.engine.DevCard.VICTORY_POINT)) {
                namedCard(FORTUNE[c], Wood.BRASS, n, "hand-fortune-$c", 50, 70, art = fortuneArt(c),
                    long = CardDetail(fortuneArt(c), FORTUNE[c], "Fortune card", listOf(FORTUNE_HINTS[c])),
                    plain = !LocalArtChoice.current.space(ArtObject.CARDS)) {
                    if (now) { hint = null; onFortune?.invoke(c) }
                    else hint = "${FORTUNE[c]}: " + when {
                        c == wgbg.engine.DevCard.VICTORY_POINT -> FORTUNE_HINTS[c]
                        s.currentPlayer != me -> "only on your turn."
                        else -> "not now (one Fortune card a turn, never one you bought this turn)."
                    }
                }
            }
        }
        val chivalry = s.chivalry ?: return@Row
        // Commodities: Parchment, Fabric, Silver, with their card art (#62).
        for (c in 0 until wgbg.engine.Commodity.COUNT) {
            val n = s.card(me, CARD_TYPES + c)
            if (n > 0) Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.zIndex(-(z++).toFloat()).testTag("hand-commodity-$c").longPressFor(cardDetail(CARD_TYPES + c, true))) {
                Image(artPainter(COMMODITY_ART[c]), ALL_CARD_NAMES[CARD_TYPES + c], Modifier.size(width = 50.dp, height = 70.dp))
                count(n)
            }
        }
        // Cards you can't play right now are dimmed and say why (#117: "don't let me play it"); the engine's own list.
        val playable = androidx.compose.runtime.remember(version, me) { playableProgress(s, me) }
        for (c in 0 until wgbg.engine.Progress.COUNT) {
            val n = chivalry.progressHand[me * wgbg.engine.Progress.COUNT + c]
            val now = c in playable
            if (n > 0) Box(Modifier.zIndex(-(z++).toFloat()).dimmed(!now)) {
                namedCard(PROGRESS_NAMES.getOrElse(c) { "Progress" }, TRACK_COLORS[wgbg.engine.Progress.track(c)], n, "hand-progress-$c", 50, 70, // as tall as the resource cards; hold one to read it large (Mauro, 2026-10-12)
                    small = null,
                    art = progressArt(c), long = progressDetail(c)) {
                    hint = "${PROGRESS_NAMES.getOrElse(c) { "Progress" }}: " + if (now) progressHint(c) else "can't play it now (${notPlayableWhy(s, me, c)})."
                }
            }
        }
    }
    }
    }
}

/** Hand cards are 50 dp wide; each keeps at least this much showing (with its count) when the row fans closer. */
internal const val HAND_CARD_W = 50f
internal const val HAND_MIN_SLICE = 24f

/** How many cards [HandRow] shows for [me]: one per kind held (resources, Fortune cards, commodities, progress cards). */
internal fun handCardCount(s: GameState, me: Int): Int {
    var n = (0 until CARD_TYPES).count { s.hand(me, it) > 0 }
    val chivalry = s.chivalry
    if (chivalry == null) n += (0 until wgbg.engine.DevCard.COUNT).count { s.devHand[me * wgbg.engine.DevCard.COUNT + it] > 0 }
    else {
        n += (0 until wgbg.engine.Commodity.COUNT).count { s.card(me, CARD_TYPES + it) > 0 }
        n += (0 until wgbg.engine.Progress.COUNT).count { chivalry.progressHand[me * wgbg.engine.Progress.COUNT + it] > 0 }
    }
    return n
}

/**
 * The spacing (dp, negative: overlap) between [cards] hand cards in [room] dp: the usual 5 dp overlap when they fit,
 * else as close as needed to fit, but never closer than [HAND_MIN_SLICE] showing per card (then the row scrolls).
 */
internal fun handOverlap(room: Float, cards: Int): Float = fanSpacing(room, cards, HAND_CARD_W, -5f, HAND_MIN_SLICE - HAND_CARD_W)

/**
 * The spacing (dp, negative: overlap) between [cards] cards [card] dp wide in [room] dp: [most] when they fit, else as
 * close as needed to fit, but never closer than [least] (then the row scrolls, or a chest shrinks its cards first).
 * Shared by the hand and the chests ([chestFit]), so cards fan the same way everywhere.
 */
internal fun fanSpacing(room: Float, cards: Int, card: Float, most: Float, least: Float): Float {
    if (cards <= 1 || !room.isFinite()) return most
    val fit = (room - card) / (cards - 1) - card
    return fit.coerceIn(least, most)
}

/**
 * A card that can't be played now, darkened but solid: at 45% alpha the board (a harbour, a road) showed through the
 * card. SrcAtop in its own layer darkens only the card's pixels, so its rounded corners stay clear.
 */
private fun Modifier.dimmed(dim: Boolean): Modifier = if (!dim) this else
    graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        drawRect(Color(0xA6140E08), blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
    }

/** Progress cards [me] may play right now: the engine's legal plays (none when it isn't their move). */
fun playableProgress(s: GameState, me: Int): Set<Int> =
    if (s.chivalry == null || s.currentPlayer != me) emptySet()
    else wgbg.engine.Rules.legalActions(s).filter { it.type == wgbg.engine.ChivalryActionType.PLAY_PROGRESS && offered(s, it) }.map { it.a }.toSet()

/** Why progress card [c] can't be played by [me] now, in a few words. */
fun notPlayableWhy(s: GameState, me: Int, c: Int): String = when {
    s.currentPlayer != me -> "only on your turn"
    s.phase != wgbg.engine.Phase.PLAY_TURN -> "finish what you're doing first"
    !s.hasRolled && c != wgbg.engine.Progress.ALCHEMIST -> "after you roll"
    s.hasRolled && c == wgbg.engine.Progress.ALCHEMIST -> "only before you roll"
    else -> progressBlocked(s, me, c)
}

/**
 * What card [c] is missing on [me]'s own turn after the roll, by the card's own rule (Mauro, 2026-10-13: "I have a
 * Grand Factor card and can't use it", with no reason given). Public facts only: points, card counts, the board.
 */
internal fun progressBlocked(s: GameState, me: Int, c: Int): String {
    val chivalry = s.chivalry ?: return "nothing it can do right now"
    val b = s.board
    val P = wgbg.engine.Progress
    val others = (0 until s.numPlayers).filter { it != me }
    val mine = s.victoryPoints(me)
    val cities = s.piecesOnBoard(me, wgbg.engine.Piece.CITY)
    fun knights(q: Int) = (0 until b.vertexCount).count { chivalry.knightOwner[it] == q }
    return when (c) {
        P.CRANE -> when {
            cities == 0 -> "you need a town to improve"
            (0 until wgbg.engine.Track.COUNT).all { chivalry.level(me, it) >= wgbg.engine.Track.MAX_LEVEL } -> "every improvement is at the top"
            else -> "not enough commodities for any improvement, even one less: " + (0 until wgbg.engine.Track.COUNT)
                .filter { chivalry.level(me, it) < wgbg.engine.Track.MAX_LEVEL }
                .joinToString(", ") { t -> "${DECK_NAMES[t]} needs ${chivalry.level(me, t)} ${ALL_CARD_NAMES[wgbg.engine.Track.commodity(t)]}" }
        }
        P.MASTER_MERCHANT -> if (others.none { s.victoryPoints(it) > mine }) "it only takes from a player with more points than you, and nobody has more"
            else "the players ahead of you have no cards"
        P.WEDDING -> if (others.none { s.victoryPoints(it) > mine }) "only players with more points than you give, and nobody has more"
            else "the players ahead of you have no cards"
        P.SABOTEUR -> if (others.none { s.victoryPoints(it) >= mine }) "it only hits players with at least your points, and nobody has that many"
            else "the players it would hit hold fewer than 2 cards"
        P.IRRIGATION, P.MINING -> {
            val r = if (c == P.IRRIGATION) wgbg.engine.Resource.WHEAT else wgbg.engine.Resource.ORE
            if (s.bank[r] == 0) "the bank is out of ${RESOURCE_NAMES[r]}" else "none of your buildings touch a ${TERRAIN_NAMES[r]}"
        }
        P.WARLORD -> if (knights(me) == 0) "you have no knights" else "your knights are all raised already"
        P.SMITH -> "no knight of yours can be promoted"
        P.ENGINEER -> if (chivalry.wallsLeft[me] == 0) "no Ramparts left" else "every town of yours has Ramparts"
        P.MEDICINE -> "it needs a homestead, a town piece left, 1 Grain and 2 Stone"
        P.ROAD_BUILDING -> "no road pieces or spots left"
        P.COMMERCIAL_HARBOR -> if (s.handSize(me) == 0) "you have no resources to give" else "nobody else has a commodity"
        P.BISHOP -> if (!chivalry.robberActive) "the bandit stays put until the Raiders first land" else "nowhere to move the bandit"
        P.DESERTER -> "nobody else has a knight on the board"
        P.DIPLOMAT -> "no open road end to remove"
        P.INTRIGUE -> "no enemy knight on your roads"
        P.SPY -> "nobody else holds a progress card"
        else -> "nothing it can do right now"
    }
}

/** A slim prompt across the top saying what to do now. */
@Composable
fun TurnPrompt(text: String, modifier: Modifier = Modifier) {
    // Two lines at most in the room it is given, smaller before it is cut.
    Text(text, color = Color(0xFFEDE6D8), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp),
        modifier = modifier.brassEdgedWood().padding(horizontal = 16.dp, vertical = 6.dp).testTag("turn-prompt")
            .semantics { testTagsAsResourceId = true })
}

/**
 * The top band for the turn prompt and the notices over the board: right of the side panel ([panel] wide, 8 dp in) and
 * left of the Menu button. With the box's TopCenter, the uneven padding centres them in the band and keeps them inside it.
 * Centred on the whole screen, a long prompt ran over the first player's name on a 640 dp screen (0.3.4).
 */
internal fun Modifier.rightOfPanel(panel: androidx.compose.ui.unit.Dp) = padding(start = promptStart(panel), end = PROMPT_END)
internal fun promptStart(panel: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (panel <= 0.dp) 376.dp else panel + 16.dp
internal val PROMPT_END = 112.dp

/** Ship steps left before the Raiders land (attack) for track position [pos]. */
/**
 * The Raiders warning on your own turn when they are 1-3 steps from landing (#92), or null: how near, both strengths,
 * and what happens if they land now.
 */
fun raidersWarning(s: GameState, me: Int): String? {
    val chivalry = s.chivalry ?: return null
    val left = raidersStepsLeft(chivalry.barbarianPos)
    if (left !in 1..3 || s.currentPlayer != me || s.isOver) return null
    val raiders = wgbg.engine.Chivalry.barbarianStrength(s)
    val knights = (0 until s.numPlayers).sumOf { wgbg.engine.Chivalry.activeKnightStrength(s, it) }
    val mine = wgbg.engine.Chivalry.activeKnightStrength(s, me)
    val near = if (left == 1) "1 step" else "$left steps"
    // Raiders first, like the "R vs K" on the track line above it.
    return "Raiders $near away!\n$raiders vs $knights: " + if (knights >= raiders) "knights hold (you $mine)"
        else "too weak, weakest lose a town (you $mine)"
}

fun raidersStepsLeft(pos: Int) = (wgbg.engine.ChivalryPiece.BARBARIAN_TRACK - pos).coerceAtLeast(0)

/** Hand size for the player list: "8", or "8/3" (cards / progress cards) in Age of Chivalry. */
/** True if [p] holds more cards than a rolled 7 allows (they would discard half): the engine's own limit and count. */
fun overCardLimit(s: GameState, p: Int): Boolean =
    if (s.chivalry != null) s.cardCount(p) > wgbg.engine.Chivalry.discardLimit(s, p) else s.handSize(p) > s.config.discardLimit

fun cardsLabel(s: GameState, p: Int): String = s.chivalry?.let { "${s.cardCount(p)}/${it.progressCount(p)}" } ?: "${s.cardCount(p)}"

/** One mini card in the player list: [card] (-1 = face down, a robbery others can't see) and [n] (+ got, - lost). */
data class RollMini(val card: Int, val n: Int)

/**
 * The mini cards for player [p] after [roll] (#47): production in, discards out (public: they go back to the
 * bank face up) and a robbery, whose card only the thief and the victim see; everyone else sees it face down.
 */
fun rollMinis(roll: TurnReport?, p: Int, me: Int): List<RollMini> {
    if (roll == null || !roll.rolled) return emptyList()
    val out = mutableListOf<RollMini>()
    roll.rollIn.getOrNull(p)?.forEachIndexed { r, n -> if (n > 0) out += RollMini(r, n) }
    roll.rollOut.getOrNull(p)?.forEachIndexed { r, n -> if (n > 0) out += RollMini(r, -n) }
    roll.steal?.let { st ->
        val seen = me == st.thief || me == st.victim || me < 0
        if (p == st.thief) out += RollMini(if (seen) st.card else -1, 1)
        if (p == st.victim) out += RollMini(if (seen) st.card else -1, -1)
    }
    return out
}

@Composable
private fun MiniRollCard(m: RollMini) {
    // One node for screen readers and the layout check: the card and the count on it ("Timber, +1").
    val said = "${ALL_CARD_NAMES.getOrElse(m.card) { "Card" }} ${if (m.n > 0) "+${m.n}" else "${m.n}"}"
    Box(Modifier.size(width = 21.dp, height = 30.dp).clearAndSetSemantics { contentDescription = said }, contentAlignment = Alignment.Center) {
        if (m.card in ALL_CARD_ART.indices) androidx.compose.foundation.Image(artPainter(ALL_CARD_ART[m.card]),
            ALL_CARD_NAMES[m.card], Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Color(0xFF5A4030), RoundedCornerShape(2.dp)).border(1.dp, Color(0xFF8A6A2A), RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center) { Text("?", color = Color(0xFFEFE3C8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        // A slim badge on the card's left foot (the next card covers its right half), so the count stays readable.
        Text(if (m.n > 0) "+${m.n}" else "${m.n}", color = Color.White, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(if (m.n > 0) Color(0xE6388E3C) else Color(0xE6C0392B), RoundedCornerShape(topEnd = 3.dp))
                .padding(horizontal = 2.dp))
    }
}

/**
 * Where the hand row starts: just right of the side panel ([panel] wide, 8 dp in from the edge), and never further in
 * than the 384 dp it always had on a big screen. Before the panel is measured ([panel] 0) it keeps 384 dp.
 */
internal fun handStart(panel: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (panel <= 0.dp) 384.dp else minOf(384.dp, panel + 16.dp)

/** The turn panel's least height: the dice row and one line of news, with the panel's padding. */
internal val TURN_HUD_MIN = (DIE_DP + 30).dp

/**
 * The turn panel's height: the room under the player list ([stripBottom]), so the dice sit right under it. It used to
 * be at least 110 dp, which on a 320 dp tall screen pushed the dice up over the last player's row (0.3.4).
 */
/** One road or knights badge: (64 dp column - 6 dp gap) / 2. */
internal val STAT_BADGE_W = 29.dp

/**
 * The player list's greatest height on a [screen] that tall: what leaves the turn panel its least height under it
 * (the list sits 8 dp down; the panel keeps a 10 dp gap). Past it the list shrinks to fit ([ShrinkToFit]; 0.3.4: six
 * seats in v3, the Raiders warning, a large font). Unmeasured ([screen] 0), no limit.
 */
internal fun stripMaxHeight(screen: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (screen <= 0.dp) androidx.compose.ui.unit.Dp.Infinity else (screen - 18.dp - TURN_HUD_MIN).coerceAtLeast(0.dp)

internal fun turnHudHeight(screen: androidx.compose.ui.unit.Dp, stripBottom: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (screen <= 0.dp) 210.dp else (screen - stripBottom - 10.dp).coerceAtLeast(TURN_HUD_MIN)
