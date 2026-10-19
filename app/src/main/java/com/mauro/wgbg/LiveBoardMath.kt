package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import wgbg.engine.Board
import wgbg.engine.GameState
import wgbg.engine.Piece
import kotlin.math.*

/** glTF is Y-up: the game's downward lattice y is glTF z, in hex-side units. */
internal fun liveVertex(board: Board, v: Int): Offset = board.topology.vertexPos[v].let {
    Offset(it.x * sqrt(3f) / 2f, it.y / 2f)
}

/**
 * Straight out to sea from the middle of the coast edge [v1]-[v2], as a unit vector in lattice units: from the centre of
 * the edge's one land hex through the edge's middle. Harbours go along this, so one between two tiles sits square on its
 * edge; aiming from the board's centre put most of them off to one side (Mauro: "not in the middle ... looks offset").
 */
internal fun coastOutward(board: Board, v1: Int, v2: Int): Offset {
    val t = board.topology
    val hex = t.vertexHexes[v1].first { it in t.vertexHexes[v2] }
    val centre = t.hexVertices[hex].fold(Offset.Zero) { acc, v -> acc + liveVertex(board, v) } / 6f
    val n = (liveVertex(board, v1) + liveVertex(board, v2)) / 2f - centre
    return n / n.getDistance()
}

/** The sea's surface, below the tile tops (0): the tiles' cliffs show this much when the board tilts. */
internal const val LIVE_SEA = -0.24f

/**
 * A harbour on the 3D board, on screen: [a] and [b] are its coast corners on the tile tops; [mid] (the middle of the
 * edge) and [token] lie on the sea, so the shallows and the buoy sit on the water and the piers run down from the
 * coast to them. Drawn at tile-top height they floated above the sea, apart from the coast, once the board tilted
 * (Mauro: "the harbors are still not connected", #136). [out] is straight out to sea on screen, before the tilt squash.
 */
internal class LiveHarbour(val a: Offset, val b: Offset, val mid: Offset, val out: Offset, val token: Offset)

internal fun liveHarbour(board: Board, v1: Int, v2: Int, p: LiveProjection, tokenOut: Float): LiveHarbour {
    val c = cos(p.rotation); val s = sin(p.rotation)
    val a = liveVertex(board, v1); val b = liveVertex(board, v2)
    val n = coastOutward(board, v1, v2)
    val mid = (a + b) / 2f
    return LiveHarbour(p.project(a), p.project(b), p.project(mid, LIVE_SEA), Offset(c * n.x - s * n.y, s * n.x + c * n.y),
        p.project(mid + n * tokenOut, LIVE_SEA))
}

internal fun liveHex(board: Board, h: Int): Offset = board.topology.hexVertices[h]
    .map { liveVertex(board, it) }.reduce { a, b -> a + b } / 6f

/** Orthographic camera shared by Filament, target drawing and tap inversion, including free orbit. */
internal data class LiveProjection(val center: Offset, val scale: Float, val tilt: Float, val rotation: Float) {
    init {
        require(scale.isFinite() && scale > 0f)
        require(tilt.isFinite() && tilt in 0f..Camera.MAX_TILT)
        require(rotation.isFinite())
    }

    fun project(p: Offset, height: Float = 0f): Offset {
        val c = cos(rotation); val s = sin(rotation)
        return center + Offset((c * p.x - s * p.y) * scale,
            ((s * p.x + c * p.y) * cos(tilt) - height * sin(tilt)) * scale)
    }

    fun unproject(p: Offset): Offset {
        val q = (p - center) / scale
        val z = q.y / cos(tilt)
        val c = cos(rotation); val s = sin(rotation)
        return Offset(c * q.x + s * z, -s * q.x + c * z)
    }
}

internal fun woodTint(materialName: String): Boolean = materialName == "WoodBody"

/** Space towns and Capitals: their dome glass is FixedDetails, greyed on import (tools/import_space.py OWNER_GLASS). */
internal fun spaceGlassTint(model: String, materialName: String): Boolean =
    materialName == "FixedDetails" && (model == "space_town" || model.startsWith("space_capital_"))

/**
 * The knight's pedestal until the modelled base comes (#101, 0.2.9): the number-plate disc twice over, a player-colour
 * ring with a dark road-coloured disc on it, so a blue knight still reads on blue sea or a blue road (Mauro: "a ring of
 * the player color then has a dark roadlike circle for contrast"). The ring is 20% of the radius (Crest: at least 15%).
 */
internal const val KNIGHT_RING = "knight_ring"
internal const val KNIGHT_BAND = "knight_band"
internal const val KNIGHT_RING_SCALE = 1.15f   // radius 0.31 (the plate is 0.27 across its radius, 0.024 thick)
internal const val KNIGHT_BAND_SCALE = 0.92f   // radius 0.25: 0.80 of the ring
internal const val KNIGHT_BAND_HEIGHT = 0.008f // its top just above the ring's
internal const val KNIGHT_ON_BASE = 0.031f     // the knight stands on the dark disc
internal val KNIGHT_BAND_COLOR = androidx.compose.ui.graphics.Color(0xFF2B2622)

/** The GLB file a model name loads: the pedestal discs reuse the number plate. */
internal fun liveModelFile(model: String): String = if (model == KNIGHT_RING || model == KNIGHT_BAND) "number_plate" else model

/** The colour to paint [material] of [model] for [owner], or null to keep its own: wood in the player colour, the pedestal whole. */
internal fun liveTint(model: String, material: String, owner: Int): androidx.compose.ui.graphics.Color? = when {
    model == KNIGHT_BAND -> KNIGHT_BAND_COLOR
    model == KNIGHT_RING -> PLAYER_COLORS.getOrNull(owner)
    owner >= 0 && (woodTint(material) || spaceGlassTint(model, material)) -> PLAYER_COLORS.getOrNull(owner)
    else -> null
}

/** Runtime plate moved into the lower third, inside the low foreground of the realtime exports. */
internal val LIVE_PLATE = Offset(0.36f, 0.45f)

internal data class LivePlacement(val model: String, val position: Offset, val height: Float = 0f,
    val scale: Float = 1f, val heading: Float = 0f, val owner: Int = -1)

/**
 * Column-major glTF Y-axis transform; positive heading cancels the camera's screen orbit. A [lift] (radians) then tips
 * the model about its base toward a camera orbited by [rotation], so it is seen from that much steeper than the board.
 */
internal fun liveModelMatrix(p: LivePlacement, lift: Float = 0f, rotation: Float = 0f): FloatArray {
    val c = cos(p.heading) * p.scale; val s = sin(p.heading) * p.scale
    val h = floatArrayOf(c, 0f, -s, 0f, p.scale, 0f, s, 0f, c) // columns
    if (lift == 0f) return floatArrayOf(h[0], h[1], h[2], 0f, h[3], h[4], h[5], 0f, h[6], h[7], h[8], 0f,
        p.position.x, p.height, p.position.y, 1f)
    // Rodrigues about the camera's ground-level right axis (cos r, 0, -sin r): up turns toward the eye (sin r, 0, cos r).
    val ax = cos(rotation); val az = -sin(rotation)
    val ct = cos(lift); val st = sin(lift); val k = 1f - ct
    val r = floatArrayOf( // columns
        ct + ax * ax * k, az * st, ax * az * k,
        -az * st, ct, ax * st,
        ax * az * k, -ax * st, ct + az * az * k)
    val m = FloatArray(9)
    for (col in 0..2) for (row in 0..2) m[col * 3 + row] = (0..2).sumOf { (r[it * 3 + row] * h[col * 3 + it]).toDouble() }.toFloat()
    return floatArrayOf(m[0], m[1], m[2], 0f, m[3], m[4], m[5], 0f, m[6], m[7], m[8], 0f, p.position.x, p.height, p.position.y, 1f)
}

/**
 * A standing piece under a camera orbited by [rotation]: turned to face the eye (its front, +Z, toward (sin r, cos r),
 * the way the digits cancel the orbit) and then laid back [lift] away from it, so its front tips up at the camera and
 * its roof sits toward the top of the screen, like the flat board's sprites. Tipping toward the eye showed the back
 * wall upside down (#104). Without the turn, a piece leaned side-on or back-first after the Turn bar or a twist
 * (Mauro: "buildings should tilt towards viewer after rotate").
 */
internal fun liveStandingMatrix(p: LivePlacement, lift: Float, rotation: Float, facing: Boolean = true): FloatArray =
    liveModelMatrix(if (facing) p.copy(heading = p.heading + rotation) else p, -lift, rotation)

/** Pieces that stand up (buildings, the Bandit): tipped toward the camera like the flat board's sprites. */
internal fun liveStanding(model: String): Boolean =
    model == "bandit" || model == "space_bandit" || model.startsWith("space_ramparts_h") || model.endsWith("_merchant") || model.endsWith("_homestead") || model.endsWith("_town") || model.endsWith("_ramparts") || "_capital_" in model ||
        "_knight_" in model

/** Standing pieces are seen from this far above the table (Mauro picked 10°, 2026-10-12): side-on, fronts showing. */
internal const val LIVE_PIECE_VIEW = 0.17453292f

/**
 * How far standing pieces lie back from the camera at board [tilt] (0 = straight down): enough that they're seen from
 * [LIVE_PIECE_VIEW] above the table, until the board itself is tilted past that view; then none, true 3D.
 */
internal fun liveLift(tilt: Float): Float = (PI.toFloat() / 2f - LIVE_PIECE_VIEW - tilt).coerceAtLeast(0f)

/**
 * The knight model at [v] (Mauro: "those knights all of them need to be models"): `<theme>_knight_l<level>`, with
 * `_inactive` for a knight that isn't activated. Exported by Crest with the base contact at the origin, 1 = hex side.
 */
internal fun liveKnightModel(s: GameState, v: Int, pirate: Boolean): String? {
    val ck = s.ck ?: return null
    if (ck.knightOwner[v] < 0) return null
    return "${if (pirate) "pirate" else "default"}_knight_l${ck.knightLevel[v].coerceIn(1, 3)}" + if (ck.isActive(v)) "" else "_inactive"
}

/** The Merchant pawn's model (the only one so far; a theme adds its own `<theme>_merchant`). */
internal const val LIVE_MERCHANT = "default_merchant"

/** Knights as models at this size (hex sides), until Crest gives the final on-board scale. */
internal const val LIVE_KNIGHT_MODEL_SCALE = 1f

/** Roads stand proud of the tile edges in their channel (Mauro 2026-10-13: "add 3-4%"): lifted 3.5% of a hex. */
internal const val LIVE_ROAD_LIFT = 0.035f

/**
 * Reads only real game state. No fixture pieces are added to the playing board. Knights are models when [hasModel]
 * has them; otherwise [drawLiveOverlay] still draws their sprites.
 */
internal fun livePlacements(s: GameState, pirate: Boolean, lod: Int, knightsPirate: Boolean = pirate,
                            hasModel: (String) -> Boolean = { false }): List<LivePlacement> = buildList {
    require(lod in 0..1)
    val b = s.board
    val theme = if (pirate) "pirate" else "default"
    val terrain = listOf("timber", "clay", "fleece", "grain", "stone", "desert")
    add(LivePlacement("sea", Offset.Zero, LIVE_SEA))
    for (h in 0 until b.hexCount) {
        val at = liveHex(b, h)
        add(LivePlacement("tile_${terrain.getOrElse(b.hexResource[h]) { "desert" }}_lod$lod", at))
        if (s.numberAt(h) > 0) {
            add(LivePlacement("number_plate", at + LIVE_PLATE, 0.012f))
            if (h != s.robberHex) add(LivePlacement("digit_${s.numberAt(h)}", at + LIVE_PLATE, 0.039f))
        }
    }
    if (s.robberHex >= 0) add(LivePlacement("bandit", liveHex(b, s.robberHex) + LIVE_PLATE, 0.012f))
    // The Merchant pawn (#112), lower left on its tile, at the buildings' scale; the overlay draws it without the model.
    s.ck?.takeIf { it.merchantHex >= 0 && it.merchantOwner >= 0 && hasModel(LIVE_MERCHANT) }?.let { ck ->
        add(LivePlacement(LIVE_MERCHANT, liveHex(b, ck.merchantHex) + MERCHANT_SPOT, scale = BUILDING_SCALE, owner = ck.merchantOwner))
    }
    for (e in 0 until b.edgeCount) {
        val owner = s.edgeOwner[e]
        if (owner < 0) continue
        val a = liveVertex(b, b.edgeA[e]); val z = liveVertex(b, b.edgeB[e])
        val dir = (z - a) / (z - a).getDistance()
        for (side in listOf(-1f, 1f)) add(LivePlacement("${theme}_road_v", (a + z) / 2f + dir * side * ROAD_SPREAD, LIVE_ROAD_LIFT,
            scale = ROAD_SCALE, heading = atan2(dir.x, dir.y), owner = owner))
    }
    for (v in 0 until b.vertexCount) {
        val owner = s.vertexOwner[v]
        if (owner < 0) continue
        val at = liveVertex(b, v)
        val ck = s.ck
        val walled = ck?.wall?.get(v) == 1
        if (walled) add(LivePlacement("${theme}_ramparts", at, scale = BUILDING_SCALE, owner = owner))
        val metro = ck?.metroVertex?.indexOf(v) ?: -1
        val kind = when {
            metro >= 0 -> "capital_${listOf("guilds", "academy", "crown")[metro]}"
            s.vertexBuilding[v] == Piece.CITY -> "town"
            else -> "homestead"
        }
        add(LivePlacement("${theme}_$kind", at, if (walled) 0.10f * BUILDING_SCALE else 0f,
            BUILDING_SCALE * if (walled) 0.9f else 1f, owner = owner))
    }
    for (v in 0 until b.vertexCount) {
        val model = liveKnightModel(s, v, knightsPirate)?.takeIf(hasModel) ?: continue
        val owner = s.ck!!.knightOwner[v]
        add(LivePlacement(KNIGHT_RING, liveVertex(b, v), scale = KNIGHT_RING_SCALE, owner = owner))
        add(LivePlacement(KNIGHT_BAND, liveVertex(b, v), KNIGHT_BAND_HEIGHT, KNIGHT_BAND_SCALE))
        add(LivePlacement(model, liveVertex(b, v), KNIGHT_ON_BASE, LIVE_KNIGHT_MODEL_SCALE, owner = owner))
    }
}

/** The piece set row of [liveInventoryPlacements] at board point [p]: false = default, true = pirate, null = neither. */
internal fun liveInventoryThemeAt(p: Offset): Boolean? = when {
    p.x !in -8.2f..8.2f -> null
    abs(p.y - LIVE_SET_ROW_Y) < 0.95f -> false
    abs(p.y - (LIVE_SET_ROW_Y + LIVE_SET_ROW_GAP)) < 0.95f -> true
    else -> null
}

private const val LIVE_SET_ROW_Y = 1.3f
private const val LIVE_SET_ROW_GAP = 2f

/** Side-by-side imported models for maintainer review; never mixed into a game state. */
internal fun liveInventoryPlacements(): List<LivePlacement> = buildList {
    add(LivePlacement("sea", Offset.Zero, LIVE_SEA, 2f))
    val terrains = listOf("timber", "clay", "fleece", "grain", "stone", "desert")
    for (lod in 0..1) for ((i, terrain) in terrains.withIndex()) {
        val at = Offset(-6.5f + i * 2.6f, -4f + lod * 2.7f)
        add(LivePlacement("tile_${terrain}_lod$lod", at))
        add(LivePlacement("number_plate", at + LIVE_PLATE, .012f))
        add(LivePlacement("digit_${listOf(6, 8, 5, 9, 10, 11)[i]}", at + LIVE_PLATE, .039f))
    }
    val kinds = listOf("homestead", "town", "ramparts", "capital_guilds", "capital_academy", "capital_crown", "road_v", "road_p30", "road_m30")
    for ((row, theme) in listOf("default", "pirate").withIndex()) for ((i, kind) in kinds.withIndex())
        add(LivePlacement("${theme}_$kind", Offset(-7.2f + 1.8f * i, LIVE_SET_ROW_Y + LIVE_SET_ROW_GAP * row), scale = 1.8f, owner = i % 4))
    add(LivePlacement("bandit", Offset(-4f, 5.4f)))
    add(LivePlacement("number_plate", Offset(0f, 5.4f), .012f))
    add(LivePlacement("digit_6", Offset(0f, 5.4f), .039f))
}
