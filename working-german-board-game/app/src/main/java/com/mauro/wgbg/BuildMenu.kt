package com.mauro.wgbg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import wgbg.engine.Action
import wgbg.engine.ActionKind
import wgbg.engine.ActionType
import wgbg.engine.ChivalryActionType

/**
 * One entry of the build menu down the right edge (Mauro: "a menu on the right side with the build/activate options,
 * with icons"): the move kind it stands for, its icon and spoken name, and whether it is a board placement (tap it,
 * then the board shows only those spots) or a direct move (buying a Fortune card).
 */
enum class BuildKind(val kind: ActionKind, val icon: MoveIcon, val label: String, val onBoard: Boolean = true, val chivalry: Boolean? = null) {
    ROAD(ActionType.BUILD_ROAD, MoveIcon.ROAD, "Road"),
    HOMESTEAD(ActionType.BUILD_SETTLEMENT, MoveIcon.HOMESTEAD, "Homestead"),
    TOWN(ActionType.BUILD_CITY, MoveIcon.TOWN, "Town"),
    FORTUNE(ActionType.BUY_DEVELOPMENT_CARD, MoveIcon.CARD, "Fortune card", onBoard = false, chivalry = false),
    KNIGHT(ChivalryActionType.BUILD_KNIGHT, MoveIcon.KNIGHT, "New knight", chivalry = true),
    ROUSE(ChivalryActionType.ACTIVATE_KNIGHT, MoveIcon.ROUSE, "Supply knight", chivalry = true),
    PROMOTE(ChivalryActionType.PROMOTE_KNIGHT, MoveIcon.PROMOTE, "Promote knight", chivalry = true),
    /** Picks the knights that can move (a charge too); tap one, then where it goes. */
    MOVE(ChivalryActionType.MOVE_KNIGHT, MoveIcon.MOVE_KNIGHT, "Move knight", chivalry = true),
    RAMPARTS(ChivalryActionType.BUILD_CITY_WALL, MoveIcon.RAMPARTS, "Ramparts", chivalry = true);

    companion object {
        /** The entries for a ruleset, in menu order. */
        fun forRuleset(chivalry: Boolean) = entries.filter { it.chivalry == null || it.chivalry == chivalry }
    }
}

/**
 * The entries the menu shows: only what can be done right now (Mauro: "only avail options should show"), plus the
 * picked entry so it can be switched off.
 */
fun shownKinds(chivalry: Boolean, legal: List<Action>, mode: BuildKind?): List<BuildKind> =
    BuildKind.forRuleset(chivalry).filter { k -> k == mode || legal.any { k.offers(it) } }

/** The legal moves the board should offer while [mode] is picked (all of them when nothing is). */
fun boardMoves(legal: List<Action>, mode: BuildKind?): List<Action> = if (mode == null) legal else legal.filter { mode.offers(it) }

/** True when [a] is one of this entry's moves: Move knight takes a charge (displacing a knight) too. */
fun BuildKind.offers(a: Action) = a.type == kind || this == BuildKind.MOVE && a.type in KNIGHT_MOVES

/**
 * The build menu: an icon per entry that can be done now, gold while it filters the board. A board entry
 * toggles [mode]; Fortune card buys at once.
 */
@Composable
fun BuildMenu(chivalry: Boolean, legal: List<Action>, mode: BuildKind?, onMode: (BuildKind?) -> Unit, onMove: (Action) -> Unit, modifier: Modifier = Modifier) {
    // Two columns (pieces, then knights and walls), so the menu ends well above Trade / Roll / End turn.
    val kinds = shownKinds(chivalry, legal, mode)
    Row(modifier.testTag("build-menu"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (col in kinds.chunked(4)) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in col) {
                val moves = legal.filter { k.offers(it) }
                IconPlateButton(k.icon, k.label, "build-${k.name.lowercase()}", lit = mode == k, enabled = moves.isNotEmpty(), width = 46.dp, height = 40.dp) {
                    if (!k.onBoard) moves.firstOrNull()?.let(onMove) else onMode(if (mode == k) null else k)
                }
            }
        }
    }
}
