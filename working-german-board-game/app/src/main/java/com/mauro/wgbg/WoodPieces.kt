package com.mauro.wgbg

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot

/**
 * Player pieces drawn as stained wooden blocks, like the physical game's.
 * Faces are filled with a wood-grain texture (ambientCG Wood049, CC0) stained in
 * each player's color, then shaded per face for a light source at the upper left.
 */
class WoodPieces(res: Resources, colors: List<Color>) {
    private val stains: List<Brush> = run {
        val grain = BitmapFactory.decodeResource(res, R.drawable.wood_grain, BitmapFactory.Options().apply { inScaled = false })
        try { colors.map { stain(grain, it) } } finally { grain.recycle() }
    }
    private val polygon = Path() // Synchronous draw calls only; never retain in a scene.
    private val edgeStroke = Stroke(width = 1.4f, join = StrokeJoin.Round)
    private val bases = colors
    /** Micro-brick paving for road tops (Codex art): long brick edges run across the road. */
    private val bricks: android.graphics.BitmapShader = android.graphics.BitmapShader(
        BitmapFactory.decodeResource(res, R.drawable.road_bricks), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.MIRROR)
    private val brickBrush = ShaderBrush(bricks)
    private val brickMatrix = android.graphics.Matrix()

    /** The stained-wood fill for color [i], for drawing other wooden things (tokens). */
    fun brush(i: Int): Brush = stains[i]

    /** [degrees] turns the house about [at] (a supply piece facing its owner); its shadow stays on the board's one sun (#129). */
    fun DrawScope.settlement(at: Offset, player: Int, scale: Float, degrees: Float = 0f) {
        val u = 0.1955f * scale // 15% bigger than the first pass
        shadow(at, 2.6f * u, u)
        rotate(degrees, at) { house(at + Offset(-0.15f * u, 0.5f * u), 1.3f * u, 1.0f * u, 0.75f * u, u, player) }
    }

    fun DrawScope.city(at: Offset, player: Int, scale: Float, degrees: Float = 0f) {
        val u = 0.1955f * scale // 15% bigger than the first pass
        shadow(at, 4.0f * u, 1.3f * u)
        // Low hall at the back right, tall tower in front on the left.
        rotate(degrees, at) {
            house(at + Offset(0.55f * u, 0.45f * u), 1.4f * u, 0.95f * u, 0.7f * u, u, player)
            house(at + Offset(-0.6f * u, 0.75f * u), 0.95f * u, 1.9f * u, 0.6f * u, u, player)
        }
    }

    fun DrawScope.road(a: Offset, b: Offset, player: Int, scale: Float) {
        val a2 = a + (b - a) * 0.17f
        val b2 = b + (a - b) * 0.17f
        val len = hypot(b2.x - a2.x, b2.y - a2.y)
        val n = Offset(-(b2.y - a2.y) / len, (b2.x - a2.x) / len)
        val half = 0.095f * scale // wide road plank, sitting in the trough between tiles
        val lift = Offset(0f, -0.045f * scale)
        val lower = if (n.y >= 0f) n else -n // the long side facing the viewer
        val top = listOf(a2 + n * half + lift, b2 + n * half + lift, b2 - n * half + lift, a2 - n * half + lift)
        val side = listOf(a2 + lower * half + lift, b2 + lower * half + lift, b2 + lower * half, a2 + lower * half)
        drawPath(poly(side.map { it + Offset(0.03f * scale, 0.05f * scale) }), Color.Black.copy(alpha = 0.22f))
        face(side, player, dark = 0.38f)
        // Top: brick paving turned to run along the road (the texture's 128 px height = road width),
        // tinted by multiplying with the player's colour.
        val topPath = poly(top)
        brickMatrix.reset()
        val k = 2 * half / 128f
        brickMatrix.setScale(k, k)
        brickMatrix.postTranslate(0f, -half)
        brickMatrix.postRotate(Math.toDegrees(kotlin.math.atan2((b2 - a2).y, (b2 - a2).x).toDouble()).toFloat())
        brickMatrix.postTranslate((a2 + lift).x, (a2 + lift).y)
        bricks.setLocalMatrix(brickMatrix)
        drawPath(topPath, brickBrush)
        drawPath(topPath, bases[player], blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
        drawPath(topPath, Color.White.copy(alpha = 0.06f))
        edge(top, player)
    }

    /** A gabled block: front gable facing the viewer, right wall and roof slope receding to the upper right. */
    private fun DrawScope.house(base: Offset, w: Float, h: Float, roof: Float, u: Float, player: Int) {
        val depth = Offset(0.6f * u, -0.42f * u)
        val bl = base + Offset(-w / 2, 0f)
        val br = base + Offset(w / 2, 0f)
        val tl = bl + Offset(0f, -h)
        val tr = br + Offset(0f, -h)
        val peak = base + Offset(0f, -h - roof)
        val front = listOf(bl, br, tr, peak, tl)
        val wall = listOf(br, br + depth, tr + depth, tr)
        val slope = listOf(tr, tr + depth, peak + depth, peak)
        face(wall, player, dark = 0.4f)
        face(slope, player, light = 0.12f)
        face(front, player)
        edge(front, player)
        edge(wall, player)
        edge(slope, player)
        // Worn, lit edges catching the light.
        drawLine(Color.White.copy(alpha = 0.35f), tl, peak, strokeWidth = 0.07f * u, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.25f), bl, tl, strokeWidth = 0.07f * u, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.3f), peak, peak + depth, strokeWidth = 0.07f * u, cap = StrokeCap.Round)
    }

    private fun DrawScope.face(pts: List<Offset>, player: Int, dark: Float = 0f, light: Float = 0f) {
        val p = poly(pts)
        drawPath(p, stains[player])
        if (dark > 0f) drawPath(p, Color.Black.copy(alpha = dark))
        if (light > 0f) drawPath(p, Color.White.copy(alpha = light))
    }

    private fun DrawScope.edge(pts: List<Offset>, player: Int) {
        val ink = lerpBlack(bases[player], 0.65f).copy(alpha = 0.7f)
        drawPath(poly(pts), ink, style = edgeStroke)
    }

    private fun DrawScope.shadow(at: Offset, w: Float, h: Float) {
        drawOval(Color.Black.copy(alpha = 0.28f), at + Offset(-0.35f * w, 0.1f * h), Size(w, h))
    }

    private fun poly(pts: List<Offset>) = polygon.apply {
        reset()
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }

    private fun lerpBlack(c: Color, f: Float) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f), c.alpha)

    companion object {
        /** Stains the grayscale grain with [color]: dark grain lines stay visible through the paint. */
        private fun stain(grain: Bitmap, color: Color): Brush {
            val out = grain.copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(out.width * out.height)
            out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
            val argb = color.toArgb()
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            for (i in px.indices) {
                val lum = (px[i] and 0xff) / 255f
                val k = 0.62f + 0.48f * lum // grain modulates the stain; light grain slightly brightens
                fun ch(c: Int) = (c * k).toInt().coerceIn(0, 255)
                px[i] = (0xff shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
            return ShaderBrush(ImageShader(out.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        }
    }
}
