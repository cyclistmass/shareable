package com.mauro.wgbg

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import wgbg.engine.Track
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The rendered buildings (#41) loaded for one theme: per piece 8 tilt steps of a wood body (tinted to the
 * player's colour when drawn) and an untinted trim. Age of Chivalry pieces load only when [chivalry].
 */
class BuildingSprites(private val res: Resources, pirate: Boolean, chivalry: Boolean, knightsPirate: Boolean = pirate, val choice: ArtChoice = ArtChoice(if (pirate) ArtSet.PIRATE else ArtSet.DEFAULT)) {
    class Piece(val body: List<ImageBitmap>, val trim: List<ImageBitmap>, val frame: Float = FRAME_UNITS, val shadow: List<ImageBitmap> = emptyList())
    private fun space(s: SpaceArt.Sprite) = Piece(s.legacyPiece().body.map { ImageBitmap.imageResource(res, it) }, s.legacyPiece().trim.map { ImageBitmap.imageResource(res, it) }, s.frameUnits, (0..7).map { ImageBitmap.imageResource(res, s.shadow[SpaceArt.legacyTilt(it)]) })

    private fun load(res: Resources, p: BuildingArt.Piece) =
        Piece(p.body.map { ImageBitmap.imageResource(res, it) }, p.trim.map { ImageBitmap.imageResource(res, it) })

    val homestead = if (choice.space(ArtObject.BUILDINGS)) space(SpaceArt.homestead) else load(res, BuildingArt.homestead(pirate))
    val town = if (choice.space(ArtObject.BUILDINGS)) space(SpaceArt.town) else load(res, BuildingArt.town(pirate))
    /** Roads by board edge direction: upright, rising to the right (+30) and falling to the right (-30). */
    val roadV = if (choice.space(ArtObject.BUILDINGS)) space(SpaceArt.road(0)) else load(res, BuildingArt.roadV(pirate))
    val roadP30 = if (choice.space(ArtObject.BUILDINGS)) space(SpaceArt.road(1)) else load(res, BuildingArt.roadP30(pirate))
    val roadM30 = if (choice.space(ArtObject.BUILDINGS)) space(SpaceArt.road(2)) else load(res, BuildingArt.roadM30(pirate))
    /** Ramparts by wall height, low to high (Age of Chivalry only); see [rampartsHeight]. */
    val ramparts: List<Piece> = if (chivalry) (if (choice.space(ArtObject.BUILDINGS)) (1..3).map { space(SpaceArt.ramparts(it)) } else BuildingArt.ramparts(pirate).map { load(res, it) }) else emptyList()
    private val capitals = if (chivalry) (if (choice.space(ArtObject.BUILDINGS)) listOf(0, 2, 1).map { space(SpaceArt.capital(it)) } else BuildingArt.capitals(pirate).map { load(res, it) }) else emptyList()
    /** The bell-buoy ports (PortArt.index order), 8 tilt steps each. */
    val ports: List<List<ImageBitmap>> = PortArt.TYPES.map { t -> t.map { ImageBitmap.imageResource(res, it) } }
    /** The Merchant pawn (#112), rendered like the buildings so it stands at their (and the Bandit's) angle. */
    val merchant = if (choice.space(ArtObject.MERCHANT)) space(SpaceArt.merchant) else load(res, MerchantArt.DEFAULT)
    /** The 3D knights, levels 1 to 3 (Age of Chivalry only). */
    val knights: List<Piece> = if (chivalry) (if (choice.space(ArtObject.KNIGHTS)) (1..3).map { space(SpaceArt.knight(it, true)) } else KnightArt.levels(knightsPirate).map { load(res, it) }) else emptyList()

    private val inactiveKnights = if (chivalry && choice.space(ArtObject.KNIGHTS)) (1..3).map { space(SpaceArt.knight(it, false)) } else knights
    fun knight(level: Int, active: Boolean) = (if (active) knights else inactiveKnights)[level - 1]

    /** The Capital for a metropolis on [track]: Guilds for trade, Crown for politics, Academy for science. */
    fun capital(track: Int): Piece? = capitals.getOrNull(capitalIndex(track))

    /** The road sprite for an edge running from lattice ([dx], [dy]) (y down). */
    fun roadFor(dx: Float, dy: Float): Piece = when (roadKind(dx, dy)) {
        0 -> roadV
        1 -> roadP30
        else -> roadM30
    }

    companion object {
        /** Hex units across one 256 px frame (193 px per hex unit). */
        const val FRAME_UNITS = 256f / 193f
        /** The road policy from the art report: two 0.66-long sprites centred this far either side of an edge's middle. */
        const val ROAD_OFFSET = 0.17f

        /**
         * Which Ramparts height (0 low, 1 middle, 2 high) to draw for an owner whose best city improvement is [bestLevel]
         * (0 to 5): the crenellations grow as the owner improves, at levels 0-1, 2-3 and 4-5.
         */
        fun rampartsHeight(bestLevel: Int): Int = (bestLevel / 2).coerceIn(0, 2)

        /** Index into BuildingArt.capitals (Guilds, Academy, Crown) for a metropolis [track], or -1. */
        fun capitalIndex(track: Int): Int = when (track) { Track.TRADE -> 0; Track.SCIENCE -> 1; Track.POLITICS -> 2; else -> -1 }

        /** 0 = upright edge, 1 = rising to the right (screen 150 degrees), 2 = falling to the right (30 degrees). */
        fun roadKind(dx: Float, dy: Float): Int {
            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() % 180f
            if (deg < 0f) deg += 180f
            return when {
                deg in 60f..120f -> 0
                deg > 120f -> 1
                else -> 2
            }
        }
    }
}

/**
 * Draws [piece] with its contact point at [at], [unitPx] screen px per hex unit, the board tilted [tilt] radians
 * (neighbouring tilt steps cross-fade, like the bandit), its body tinted [color], turned [degrees] about [at].
 */
internal fun DrawScope.drawPieceSprite(piece: BuildingSprites.Piece, at: Offset, unitPx: Float, tilt: Float, color: Color, degrees: Float = 0f,
                                       bodyFilter: ColorFilter? = null) {
    val side = piece.frame * unitPx
    if (side < 2f || !side.isFinite()) return
    val (a, b, f) = banditSteps(tilt)
    val dst = IntOffset((at.x - side / 2).toInt(), (at.y - side / 2).toInt())
    val size = IntSize(side.toInt(), side.toInt())
    val tint = bodyFilter ?: pieceTint(color)
    fun layer(i: Int, alpha: Float) {
        val body = piece.body[i]; val trim = piece.trim[i]
        drawImage(body, IntOffset.Zero, IntSize(body.width, body.height), dst, size, alpha = alpha, colorFilter = tint, filterQuality = FilterQuality.Medium)
        drawImage(trim, IntOffset.Zero, IntSize(trim.width, trim.height), dst, size, alpha = alpha, filterQuality = FilterQuality.Medium)
    }
    // Shadows stay in board space even when the owner turns a supply piece.
    piece.shadow.getOrNull(a)?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), dst, size, filterQuality = FilterQuality.Medium) }
    if (degrees == 0f) {
        layer(a, 1f); if (f > 0.02f) layer(b, f)
    } else rotate(degrees, at) { layer(a, 1f); if (f > 0.02f) layer(b, f) }
}

/**
 * A 4x5 colour matrix (row-major) that scales saturation by [s] around Rec. 601 luma: 1 leaves colour alone, more is
 * more vivid, less is greyer.
 */
internal fun saturationMatrix(s: Float): FloatArray {
    val w = floatArrayOf(0.299f, 0.587f, 0.114f)
    val m = FloatArray(20)
    for (r in 0 until 3) for (c in 0 until 3) m[r * 5 + c] = (1f - s) * w[c] + if (r == c) s else 0f
    m[18] = 1f
    return m
}

/** Pieces in a player's colour: the wood tinted, then [PIECE_SATURATION] more vivid and [PIECE_BRIGHTNESS] brighter. */
internal fun pieceMatrix(color: Color): FloatArray {
    val sat = saturationMatrix(PIECE_SATURATION)
    val p = floatArrayOf(color.red, color.green, color.blue)
    val m = FloatArray(20)
    for (r in 0 until 3) for (c in 0 until 3) m[r * 5 + c] = PIECE_BRIGHTNESS * sat[r * 5 + c] * p[c]
    m[18] = 1f
    return m
}

private val pieceTints = HashMap<Color, ColorFilter>()
/** The colour filter for a piece of [color] (cached: there are only a few player colours). */
internal fun pieceTint(color: Color): ColorFilter = pieceTints.getOrPut(color) { ColorFilter.colorMatrix(ColorMatrix(pieceMatrix(color))) }

/** Mauro, 2026-10-11: "all the wood pieces need to be much brighter to see on the board, 20% more sat". */
internal const val PIECE_SATURATION = 1.2f
internal const val PIECE_BRIGHTNESS = 1.15f
/** ...and "10% less on the tiles". */
/**
 * The whole board's art (tiles, sea, cliffs) at this saturation, so it reads less like over-saturated AI art (Mauro,
 * 2026-10-12, picked 85% from 100/90/80/70 sheets). Pieces, dice and the sea's boats, gulls, Raiders and whale stay
 * at full colour. Try 0.8 or 0.9 here.
 */
internal const val BOARD_SATURATION = 0.85f
internal const val TILE_SATURATION = 0.72f * BOARD_SATURATION // 0.9, then 20% less ("20% less sat on the tiles"), then the board's 85%
/** The board's art a touch warmer: red up, blue down by this share (Mauro, 2026-10-12: "a little more warmth - 3%"). */
internal const val BOARD_WARMTH = 0.03f
/** [saturationMatrix] at [sat], then warmed by [BOARD_WARMTH]. */
internal fun boardMatrix(sat: Float): FloatArray = saturationMatrix(sat).also { m ->
    for (c in 0 until 3) { m[c] *= 1f + BOARD_WARMTH; m[10 + c] *= 1f - BOARD_WARMTH }
}
internal val TILE_FILTER: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(boardMatrix(TILE_SATURATION))) }
/** The sea and cliffs at [BOARD_SATURATION], warmed like the tiles. */
internal val BOARD_FILTER: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(boardMatrix(BOARD_SATURATION))) }

/** Where an active knight's pennant goes, relative to the knight's contact point, for a frame [side] px across:
 * the pole's foot, the pole's top, and the pennant's tip. Upright at any tilt, just right of the knight. */
internal fun knightFlagPoints(side: Float): List<Offset> = listOf(
    Offset(0.24f * side, -0.04f * side), Offset(0.24f * side, -0.46f * side), Offset(0.46f * side, -0.385f * side))

/**
 * An active knight flies a pennant in its owner's colour on a worn-brass pole (Mauro: "add a flag and stop
 * highlighting the knights"); idle knights are the same colour, just without the flag.
 */
internal fun DrawScope.drawKnightFlag(at: Offset, side: Float, color: Color) {
    val (foot, top, tip) = knightFlagPoints(side).map { at + it }
    drawLine(Color(0xFF6B5326), foot, top, 0.035f * side, StrokeCap.Round)
    drawLine(Color(0xFFC9A04A), foot + Offset(-0.008f * side, 0f), top + Offset(-0.008f * side, 0f), 0.015f * side, StrokeCap.Round)
    val flag = Path().apply { moveTo(top.x, top.y); lineTo(tip.x, tip.y); lineTo(top.x, top.y + 0.15f * side); close() }
    drawPath(flag, color)
    drawPath(flag, Color(0xFF2A1B10), style = Stroke(0.012f * side))
    drawCircle(Color(0xFFE8C66A), 0.022f * side, top)
}

/** Degrees to turn a supply's pieces so they face their owner at [seatAngle] (radians; the viewer's seat is 90 degrees). */
internal fun faceOwnerDegrees(seatAngle: Float): Float = Math.toDegrees((seatAngle - PI.toFloat() / 2f).toDouble()).toFloat()
