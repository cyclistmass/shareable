package com.mauro.wgbg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.engine.Action
import wgbg.engine.ActionKind
import wgbg.engine.ChivalryActionType
import wgbg.engine.GameState
import wgbg.engine.Piece
import wgbg.engine.Track

// ------------------------------------------------------------------ tap menus on board objects (0.3.5)
//
// Mauro, 2026-10-22: tap a knight (or one of your towns) and its moves come up in a menu right above it; pick a line,
// then a check or an X. "Make sure the code is configurable vs hard coded behavior for each object": every object that
// takes a tap is one row of [TAP_TARGETS], so a new line (or a new kind of object) is a table change, not a new code path.

/**
 * One line of an object's menu: its icon and name, and which legal moves it stands for at corner v. [pick] lines don't
 * make a move themselves: they pick the object for the board's next tap (Move knight: then tap where it goes). [art] is a
 * painted icon (a drawable) in place of the drawn [icon].
 */
class TapLine(val tag: String, val icon: MoveIcon, val label: String, val pick: Boolean = false, val art: Int = 0, val at: (Action, Int) -> Boolean)

/** The kinds of board object that answer a tap with a menu. */
enum class TapKind { KNIGHT, TOWN }

/**
 * A kind of object that takes a tap: [owns] tells whether corner v holds one of [me]'s, [lines] are its menu in order,
 * [highlight] whether it glows while one of its lines can be done (the knight's lit base), [confirm] whether a picked
 * line goes through the check / X.
 */
class TapTarget(val kind: TapKind, val owns: (GameState, Int, Int) -> Boolean, val lines: List<TapLine>, val highlight: Boolean, val confirm: Boolean = true)

private fun onCorner(type: ActionKind) = { a: Action, v: Int -> a.type == type && chivalryVertex(a) == v }

/** A town improvement on track [t]: a plain one, or the one that makes this town the Capital. */
private fun improves(t: Int) = { a: Action, v: Int -> a.type == ChivalryActionType.IMPROVE_CITY && a.a == t && (a.b == 0 || a.b - 1 == v) }

val TAP_TARGETS: List<TapTarget> = listOf(
    TapTarget(TapKind.KNIGHT, { s, me, v -> s.chivalry?.knightOwner?.getOrNull(v) == me }, listOf(
        TapLine("supply", MoveIcon.ROUSE, "Supply", art = R.drawable.icon_supply, at = onCorner(ChivalryActionType.ACTIVATE_KNIGHT)),
        TapLine("promote", MoveIcon.PROMOTE, "Promote", art = R.drawable.icon_promote, at = onCorner(ChivalryActionType.PROMOTE_KNIGHT)),
        // The spec's open question (move and chase from the menu): in, since the build row no longer has Move knight.
        TapLine("move", MoveIcon.MOVE_KNIGHT, "Move", pick = true) { a, v -> a.type in KNIGHT_MOVES && a.a == v },
        TapLine("chase", MoveIcon.CHASE, "Chase bandit", at = onCorner(ChivalryActionType.CHASE_ROBBER)),
    ), highlight = true),
    TapTarget(TapKind.TOWN, { s, me, v -> s.vertexOwner[v] == me && s.vertexBuilding[v] == Piece.CITY }, listOf(
        TapLine("guilds", MoveIcon.GUILDS, DECK_NAMES[Track.TRADE], at = improves(Track.TRADE)),
        TapLine("academy", MoveIcon.ACADEMY, DECK_NAMES[Track.SCIENCE], at = improves(Track.SCIENCE)),
        TapLine("crown", MoveIcon.CROWN, DECK_NAMES[Track.POLITICS], at = improves(Track.POLITICS)),
        TapLine("ramparts", MoveIcon.RAMPARTS, "Ramparts", at = onCorner(ChivalryActionType.BUILD_CITY_WALL)),
    ), highlight = false),
)

/** An object that was tapped: which kind, and its corner. */
data class TapHit(val target: TapTarget, val vertex: Int)

/** The moves [line] offers on corner [v] now. */
fun lineMoves(line: TapLine, legal: List<Action>, v: Int): List<Action> = legal.filter { line.at(it, v) }

/**
 * The object of [me]'s a tap at lattice point [p] lands on, nearest first, with at least one line that can be done now;
 * null when there is none (the tap then goes to the board as before).
 */
fun tapTargetAt(s: GameState, legal: List<Action>, p: Offset, me: Int, targets: List<TapTarget> = TAP_TARGETS): TapHit? {
    if (me < 0) return null
    fun d(v: Int) = vertexLattice(s.board, v).let { kotlin.math.hypot(it.x - p.x, it.y - p.y) }
    var best: TapHit? = null; var bestD = TAP_RADIUS
    for (t in targets) for (v in 0 until s.board.topology.vertexCount) {
        if (!t.owns(s, me, v) || d(v) >= bestD || t.lines.none { lineMoves(it, legal, v).isNotEmpty() }) continue
        best = TapHit(t, v); bestD = d(v)
    }
    return best
}

/** The corners that glow because an object there has something to do (the table's [TapTarget.highlight] rows). */
fun tapHighlights(s: GameState, legal: List<Action>, me: Int, targets: List<TapTarget> = TAP_TARGETS): Set<Int> = if (me < 0) emptySet() else
    targets.filter { it.highlight }.flatMap { t ->
        (0 until s.board.topology.vertexCount).filter { v -> t.owns(s, me, v) && t.lines.any { lineMoves(it, legal, v).isNotEmpty() } }
    }.toSet()

/**
 * The menu right above a tapped object: a line per entry, engraved when it can't be done now. A picked line turns into
 * an enamelled check (green) and X (red) in its place ([chosen]); [onLine] gets a line tapped, [onConfirm] the check.
 */
@Composable
fun ObjectMenu(hit: TapHit, legal: List<Action>, chosen: TapLine?, onLine: (TapLine) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.background(Color(0xF02A1B10), shape).border(2.dp, Wood.BRASS, shape).padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("tap-menu-${hit.target.kind.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (chosen != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            EnamelButton(true, "tap-confirm", Modifier.confirmA11y("Confirm ${chosen.label}"), onConfirm)
            EnamelButton(false, "tap-cancel", Modifier.confirmA11y("Cancel"), onCancel)
        } else for (line in hit.target.lines) {
            val can = lineMoves(line, legal, hit.vertex).isNotEmpty()
            val ink = if (can) Color(0xFFF1D58A) else Color(0x66C9B48A)   // engraved: sunk into the wood, dim
            Row(
                Modifier.width(150.dp).clickable(enabled = can, role = Role.Button) { onLine(line) }
                    .semantics(mergeDescendants = true) { contentDescription = line.label }
                    .padding(vertical = 4.dp, horizontal = 4.dp).testTag("tap-${line.tag}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (line.art != 0) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(line.art), null, Modifier.size(22.dp),
                    alpha = if (can) 1f else 0.4f)
                else MoveGlyph(line.icon, ink, Modifier.size(22.dp))
                Text(line.label, style = Wood.sans(16.sp, FontWeight.Bold, ink))
            }
        }
    }
}

/** The enamelled check (green) or X (red) on an aged brass rim (Mauro: "a check or an x, in enamelled brass"). */
@Composable
fun EnamelButton(check: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val art = when {
        check -> if (down) R.drawable.btn_check_pressed else R.drawable.btn_check
        else -> if (down) R.drawable.btn_x_pressed else R.drawable.btn_x
    }
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art), null,
        modifier.size(48.dp).testTag(tag).clickable(press, null, role = Role.Button, onClick = onClick))
}
