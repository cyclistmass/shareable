package com.mauro.wgbg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wgbg.engine.Action
import wgbg.engine.ActionKind
import wgbg.engine.ActionType
import wgbg.engine.ChivalryActionType
import wgbg.engine.Track

/**
 * One plate of the build row down the right edge (Mauro: "a menu on the right side with the build/activate options,
 * with icons"): the move kind it stands for (and, for a town improvement, its [track]), its icon and spoken name, its
 * painted art ([art]: res/drawable-nodpi/plate_<art>_on.png raised, _off.png engraved; the drawn icon until those land),
 * whether it is a board placement (tap it, then the board shows only those spots) or a direct move, and whether a
 * direct move goes through the check / X first ([confirm]).
 *
 * Supply, Promote, Move knight and Chase bandit are no longer plates (0.3.5): they're the knight's tap menu, in
 * [TAP_TARGETS]. A new plate is a new row here.
 */
enum class BuildKind(val kind: ActionKind, val icon: MoveIcon, val label: String, val art: String, val onBoard: Boolean = true, val chivalry: Boolean? = null,
                     val track: Int = -1, val confirm: Boolean = true) {
    ROAD(ActionType.BUILD_ROAD, MoveIcon.ROAD, "Road", "road"),
    HOMESTEAD(ActionType.BUILD_SETTLEMENT, MoveIcon.HOMESTEAD, "Homestead", "homestead"),
    TOWN(ActionType.BUILD_CITY, MoveIcon.TOWN, "Town", "town"),
    FORTUNE(ActionType.BUY_DEVELOPMENT_CARD, MoveIcon.CARD, "Fortune card", "fortune", onBoard = false, chivalry = false, confirm = false),
    KNIGHT(ChivalryActionType.BUILD_KNIGHT, MoveIcon.KNIGHT, "New knight", "knight", chivalry = true),
    RAMPARTS(ChivalryActionType.BUILD_CITY_WALL, MoveIcon.RAMPARTS, "Ramparts", "ramparts", chivalry = true),
    GUILDS(ChivalryActionType.IMPROVE_CITY, MoveIcon.GUILDS, DECK_NAMES[Track.TRADE], "guilds", onBoard = false, chivalry = true, track = Track.TRADE),
    ACADEMY(ChivalryActionType.IMPROVE_CITY, MoveIcon.ACADEMY, DECK_NAMES[Track.SCIENCE], "academy", onBoard = false, chivalry = true, track = Track.SCIENCE),
    CROWN(ChivalryActionType.IMPROVE_CITY, MoveIcon.CROWN, DECK_NAMES[Track.POLITICS], "crown", onBoard = false, chivalry = true, track = Track.POLITICS);

    companion object {
        /** The entries for a ruleset, in menu order. */
        fun forRuleset(chivalry: Boolean) = entries.filter { it.chivalry == null || it.chivalry == chivalry }
    }
}

/** A plate's size on screen: half the pass-2 mockups' (Mauro, 2026-10-22: "half size"), then 15% up (0.3.6: "scale them
 * 15%"); one column still clears the Roll plate on a 443 dp phone (Pixel 10 Pro XL). */
val BUILD_PLATE = 34.5.dp
/** A plate steps this far clear of a camera hole: its 48 dp touch area reaches past the plate, and that clears it too. */
internal fun plateHoleMargin(plate: Dp = BUILD_PLATE) = 4.dp + (48.dp - plate) / 2
internal val PLATE_HOLE_MARGIN = plateHoleMargin()

/**
 * The plates the row shows: every one for the ruleset, raised when it can be done now and engraved when it can't
 * (0.3.5, replacing "only avail options should show": one fixed column, so a plate never jumps about).
 */
fun shownKinds(chivalry: Boolean): List<BuildKind> = BuildKind.forRuleset(chivalry)

/** How far down the screen the build menu starts (MainActivity places it under the Menu button). */
val BUILD_MENU_TOP = 60.dp

/** The build menu's height: a plate per kind 3 dp apart, and under each track's plate its level dots (1 + 7 dp). */
fun buildMenuHeight(chivalry: Boolean): Dp = shownKinds(chivalry).let { ks ->
    BUILD_PLATE * ks.size + 3.dp * (ks.size - 1) + 8.dp * ks.count { it.track >= 0 } }

/**
 * Whether the build menu down the right edge reaches a [buttonH] tall button in the bottom-right corner of a screen
 * [screenH] tall. On a 320 dp phone (CI's 640x320) the Age of Chivalry menu runs over the Roll plate (0.3.5-pre2).
 */
fun buildMenuReaches(chivalry: Boolean, screenH: Dp, buttonH: Dp): Boolean = BUILD_MENU_TOP + buildMenuHeight(chivalry) > screenH - buttonH

/**
 * The plate size that keeps the whole menu on a screen [screenH] tall: [BUILD_PLATE] where it fits, smaller (not under
 * 20 dp) where it doesn't. On a 320 dp phone the Age of Chivalry column ran off the bottom and its last plate, Crown,
 * was a sliver in the corner (CI layout-cutout, 0.3.6).
 */
fun buildPlateFor(chivalry: Boolean, screenH: Dp): Dp = shownKinds(chivalry).let { ks ->
    val room = screenH - BUILD_MENU_TOP - 8.dp - 3.dp * (ks.size - 1) - 8.dp * ks.count { it.track >= 0 }
    (room / ks.size).coerceIn(20.dp, BUILD_PLATE) }

/** The legal moves the board should offer while [mode] is picked (all of them when nothing is). */
fun boardMoves(legal: List<Action>, mode: BuildKind?): List<Action> = if (mode == null) legal else legal.filter { mode.offers(it) }

/**
 * What the turn prompt says while board plate [this] is picked (a player report: the Ramparts plate lit up and the
 * prompt didn't change, so it seemed to do nothing). Null for the plates that act at once.
 */
val BuildKind.boardHint: String?
    get() = when (this) {
        BuildKind.ROAD -> "Tap a glowing edge to lay your road"
        BuildKind.HOMESTEAD -> "Tap a glowing corner for your Homestead"
        BuildKind.TOWN -> "Tap a glowing Homestead to make it a Town"
        BuildKind.KNIGHT -> "Tap a glowing corner for your new knight"
        BuildKind.RAMPARTS -> "Tap a glowing Town to put Ramparts on it"
        else -> null
    }

/** The turn prompt while a knight picked to move waits for where it goes. */
const val KNIGHT_PICK_HINT = "Tap a glowing corner to move your knight there"

/** True when [a] is one of this plate's moves. */
fun BuildKind.offers(a: Action) = a.type == kind && (track < 0 || a.a == track)

/**
 * What tapping plate [k] does with its [moves] now: narrow the board ([Pick.Mode]), make the move at once ([Pick.Now]),
 * ask with the check / X ([Pick.Ask]), or, for an improvement that makes a town the Capital, say to tap that town
 * ([Pick.Hint]: its town menu has the line).
 */
sealed interface Pick {
    data class Mode(val kind: BuildKind?) : Pick
    data class Now(val move: Action) : Pick
    data class Ask(val moves: List<Action>) : Pick
    data class Hint(val text: String) : Pick
}

fun plateTap(k: BuildKind, moves: List<Action>, mode: BuildKind?): Pick? = when {
    moves.isEmpty() -> null
    k.onBoard -> Pick.Mode(if (mode == k) null else k)
    !k.confirm -> Pick.Now(moves.first())
    moves.size == 1 -> Pick.Ask(moves)
    else -> Pick.Hint("Tap the town that becomes your Capital")
}

/**
 * The build row: one plate per entry, gold while it filters the board. Under Guilds, Academy and Crown, your level on
 * that track ([level], 0 to 5) as dots over a bar in the track's colour (Mauro, 0.3.5).
 */
@Composable
fun BuildMenu(chivalry: Boolean, legal: List<Action>, mode: BuildKind?, onPick: (Pick) -> Unit, modifier: Modifier = Modifier,
              level: (Int) -> Int = { 0 },
              /** The camera cutout's rects (window px): a plate level with one steps left of it, the rest stay flush (0.3.6). */
              holes: List<androidx.compose.ui.geometry.Rect> = emptyList()) {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val pkg = androidx.compose.ui.platform.LocalContext.current.packageName
    val size = buildPlateFor(chivalry, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp)
    Column(modifier.testTag("build-menu"), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) {
        for (k in shownKinds(chivalry)) {
            val moves = legal.filter { k.offers(it) }
            val tap = { plateTap(k, moves, mode)?.let(onPick); Unit }
            // Painted plates when they're in the build; the drawn icon on a brass plate until then.
            val art = res.getIdentifier("plate_${k.art}_${if (moves.isNotEmpty()) "on" else "off"}", "drawable", pkg)
            if (k.track >= 0) Column(Modifier.dodgeHolesEnd(holes, plateHoleMargin(size)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Plate(k, art, mode, moves, tap, size)
                LevelDots(level(k.track), TRACK_COLORS[k.track], size, Modifier.testTag("build-${k.name.lowercase()}-level"))
            } else androidx.compose.foundation.layout.Box(Modifier.dodgeHolesEnd(holes, plateHoleMargin(size))) { Plate(k, art, mode, moves, tap, size) }
        }
    }
}

@Composable
private fun Plate(k: BuildKind, art: Int, mode: BuildKind?, moves: List<Action>, tap: () -> Unit, size: Dp = BUILD_PLATE) {
    if (art != 0) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art), k.label,
        Modifier.size(size).testTag("build-${k.name.lowercase()}")
            .then(if (mode == k) Modifier.border(2.dp, Wood.BRASS_LIGHT, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) else Modifier)
            .clickable(enabled = moves.isNotEmpty(), role = androidx.compose.ui.semantics.Role.Button, onClick = tap))
    else IconPlateButton(k.icon, k.label, "build-${k.name.lowercase()}", lit = mode == k, enabled = moves.isNotEmpty(),
        width = size, height = size, iconSize = size * 0.6f, pad = 2.dp, onClick = tap)
}

/** A track level ([level] of [wgbg.engine.Track.MAX_LEVEL]) as dots, filled up to it, over a full bar in [colour]. */
@Composable
fun LevelDots(level: Int, colour: androidx.compose.ui.graphics.Color, width: Dp = BUILD_PLATE, modifier: Modifier = Modifier) {
    val max = wgbg.engine.Track.MAX_LEVEL
    androidx.compose.foundation.Canvas(modifier.size(width = width, height = 7.dp)
        .semantics { contentDescription = "level ${level.coerceIn(0, max)} of $max" }) {
        val r = 1.5.dp.toPx(); val gap = size.width / max
        for (i in 0 until max) {
            val c = androidx.compose.ui.geometry.Offset(gap * (i + 0.5f), r + 0.5f)
            if (i < level) drawCircle(colour, r, c)
            else drawCircle(colour.copy(alpha = 0.45f), r - 0.4f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
        }
        drawRoundRect(colour, androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()),
            androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
    }
}
