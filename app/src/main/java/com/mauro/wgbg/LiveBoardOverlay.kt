package com.mauro.wgbg

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import wgbg.engine.Board
import wgbg.engine.GameState
import kotlin.math.cos
import kotlin.math.roundToInt

/** The 3D board's knights at this share of the flat board's size (Mauro: "scale down a little", then 30% smaller again). */
internal const val LIVE_KNIGHT_SHARE = 0.49f

/** Knight sprites (where no model is loaded) remain screen overlays for this prototype. */
internal fun DrawScope.drawLiveOverlay(s: GameState, p: LiveProjection, knights: List<ImageBitmap>, merchantModel: Boolean = false,
                                       top: Float = 0f, knightModel: (Int) -> Boolean = { false }) {
    val chivalry = s.chivalry
    if (chivalry != null) for (v in 0 until s.board.vertexCount) {
        val owner = chivalry.knightOwner[v]
        if (owner < 0 || knightModel(v)) continue   // drawn as a 3D model instead
        val at = p.project(liveVertex(s.board, v), top)
        val size = p.scale * KNIGHT_SCALE * LIVE_KNIGHT_SHARE
        val image = knights.getOrNull((chivalry.knightLevel[v].coerceIn(1, 3) - 1) * 2 + if (chivalry.isActive(v)) 1 else 0)
        drawCircle(PLAYER_COLORS[owner], size * .22f, at)
        if (image != null) drawImage(image, dstOffset = IntOffset((at.x - size / 2).roundToInt(), (at.y - size * .92f).roundToInt()),
            dstSize = IntSize(size.roundToInt(), size.roundToInt()))
    }
    if (chivalry != null && chivalry.merchantHex >= 0 && chivalry.merchantOwner >= 0 && !merchantModel)   // the Merchant (#112), on the tile, lower left
        drawMerchantToken(p.project(liveHex(s.board, chivalry.merchantHex) + MERCHANT_SPOT, top), p.scale, cos(p.tilt), PLAYER_COLORS[chivalry.merchantOwner])
    // Harbours: the flat board's shallows, piers and buoys, projected (drawLivePorts in MainActivity.kt, #104).
}

/** [top] is the board surface's height ([liveBoardTop]): the rings sit on the tiles the player sees, where taps land. */
internal fun DrawScope.drawLiveTargets(b: Board, targets: Targets, p: LiveProjection, color: Color, pulse: Float = 1f, top: Float = 0f,
                                       pieceOn: (Int) -> Boolean = { false }, knightOn: (Int) -> Boolean = { false }) {
    for (v in targets.vertices) {
        val at = p.project(liveVertex(b, v), top)
        // A knight you can promote or supply: its base glows (#118).
        if (knightOn(v)) drawKnightBaseGlow(at, p.scale * KNIGHT_SCALE * LIVE_KNIGHT_SHARE, cos(p.tilt), color, 0.6f + 0.4f * pulse)
        else drawCircle(color, p.scale * .13f, at, style = Stroke(p.scale * .035f))
    }
    for (e in targets.edges) {
        // Clear of the pieces at its ends, like the flat board (the glow never covers a homestead).
        val v0 = liveVertex(b, b.edgeA[e]); val v1 = liveVertex(b, b.edgeB[e])
        val (f0, f1) = edgeGlowSpan(pieceOn(b.edgeA[e]), pieceOn(b.edgeB[e]))
        drawLine(color, p.project(v0 + (v1 - v0) * f0, top), p.project(v0 + (v1 - v0) * f1, top), p.scale * .055f)
    }
    for (h in targets.hexes) {
        val at = p.project(liveHex(b, h), top); val r = p.scale * .33f
        drawOval(color, at - Offset(r, r * cos(p.tilt)), androidx.compose.ui.geometry.Size(r * 2f, r * cos(p.tilt) * 2f),
            style = Stroke(p.scale * .04f))
    }
}
