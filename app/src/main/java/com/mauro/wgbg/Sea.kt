package com.mauro.wgbg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import wgbg.engine.Board
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The framed round sea around the island (issue #1), in board lattice units (hex side = 1,
 * origin at the board's center), so it pans, zooms and tilts with the board.
 *
 * The water is animated like a classic hand-painted RTS map: two slow, translucent layers of
 * tonal patches drift over the deep-blue gradient; small hand-painted wavelets (a light crescent
 * over a darker shadow stroke) loop through fade-in, curl and fade-out, each on its own phase,
 * and reappear nearby; a few double-size crests roll through the deeper water; and little
 * clusters of foam curl in and break along the coastline, which is found from the hex geometry.
 * Everything is a pure function of time and deterministic placement, so it loops seamlessly.
 * Clouds and gulls stay over the water; clouds can be poked (poof after a few pokes) or
 * dragged, and tapping the water splashes.
 */
class Sea(board: Board, seed: Int = 1) {
    internal val liveCoastEdges = liveCoast(board)
    private val rnd = Random(seed)
    private val topo = board.topology
    private fun lx(v: Int) = topo.vertexPos[v].x * SQRT3 / 2f
    private fun ly(v: Int) = topo.vertexPos[v].y / 2f
    private val land: List<Offset> = topo.hexes.indices.map { h ->
        val vs = topo.hexVertices[h]
        Offset(vs.map { lx(it) }.average().toFloat(), vs.map { ly(it) }.average().toFloat())
    }
    /** Farthest land corner from the center. */
    val landR = topo.vertexPos.maxOf { hypot(it.x * SQRT3 / 2f, it.y / 2f) }

    /** Distance from (x, y) to the island's coast (each land hex approximated by its inscribed circle). */
    internal fun coastDist(x: Float, y: Float): Float {
        var m = Float.MAX_VALUE
        for (h in land) { val d = hypot(x - h.x, y - h.y); if (d < m) m = d }
        return m - HEX_IN
    }

    // ------------------------------------------------------------------ wavelets

    /**
     * A small hand-painted wavelet. It loops every [period] seconds from [phase]; each loop it
     * reappears a little way from its home ([x], [y]) with a slightly different angle and bend.
     * Its crest lies along ([px], [py]); it bows and drifts toward ([nx], [ny]).
     */
    internal class Wavelet(val x: Float, val y: Float, val angle: Float, val len: Float, val period: Float, val phase: Float,
                           val color: Color, val alpha: Float, val big: Boolean, val id: Int)
    internal val wavelets: List<Wavelet>

    /** One breaking spot just off a coastline edge: a cluster of crescents curling in toward the land. */
    internal class Break(val x: Float, val y: Float, val nx: Float, val ny: Float, val period: Float, val phase: Float, val count: Int, val id: Int)
    internal val breaks: List<Break>

    init {
        // Coastline edges: an edge whose two corners share exactly one hex has water on its other side.
        class Coast(val ax: Float, val ay: Float, val bx: Float, val by: Float, val nx: Float, val ny: Float)
        val coast = topo.edgeVertices.mapNotNull { (v1, v2) ->
            val shared = topo.vertexHexes[v1].filter { it in topo.vertexHexes[v2] }
            if (shared.size != 1) return@mapNotNull null
            val c = land[shared[0]]
            val mx = (lx(v1) + lx(v2)) / 2; val my = (ly(v1) + ly(v2)) / 2
            val d = hypot(mx - c.x, my - c.y)
            Coast(lx(v1), ly(v1), lx(v2), ly(v2), (mx - c.x) / d, (my - c.y) / d)
        }
        // Harbor mouths: the water just off each port's edge gets a little extra activity.
        val harbors = board.ports.map { p ->
            val mx = (lx(p.v1) + lx(p.v2)) / 2; val my = (ly(p.v1) + ly(p.v2)) / 2
            val n = coastOutward(board, p.v1, p.v2)
            Offset(mx + n.x * 0.45f, my + n.y * 0.45f)
        }

        fun color(): Color = rnd.nextFloat().let { if (it < 0.55f) LIGHT_BLUE else if (it < 0.9f) CYAN else PALE }
        val list = ArrayList<Wavelet>()
        // Room for the farthest a wavelet can hop and drift, so it never touches the coast or the rim.
        val roam = HOP * SQRT3 / 1.2f + DRIFT
        /** Adds a wavelet at (x, y) if it fits on the water there; returns whether it did. */
        fun add(x: Float, y: Float, big: Boolean): Boolean {
            val cd = coastDist(x, y)
            val len = (0.075f + rnd.nextFloat() * 0.085f + (if (rnd.nextFloat() < 0.08f) 0.05f else 0f)) * if (big) 2f else 1f
            if (cd < roam + len * 0.4f || !inOpenSea(x, y, roam + len * 0.75f)) return false
            // Near the coast crests lie parallel to it; out at sea they take varied angles around a gentle swell.
            val nearest = land.minBy { hypot(x - it.x, y - it.y) }
            val shoreAngle = atan2(y - nearest.y, x - nearest.x) + PI.toFloat() / 2
            val seaAngle = SWELL + (rnd.nextFloat() - 0.5f) * 2.4f
            val w = exp(-cd / 0.5f)
            val angle = if (rnd.nextFloat() < w) shoreAngle + (rnd.nextFloat() - 0.5f) * 0.5f else seaAngle
            list += Wavelet(x, y, angle, len, period = 3.5f + rnd.nextFloat() * 3.5f, phase = rnd.nextFloat(),
                color = if (big) (if (rnd.nextFloat() < 0.6f) PALE else LIGHT_BLUE) else color(),
                alpha = if (big) 0.78f else 0.55f + 0.25f * w, big = big, id = list.size)
            return true
        }
        fun waterPoint(): Pair<Float, Float> {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val d = sqrt(rnd.nextFloat())
            return cos(a) * d * OPEN_RX to sin(a) * d * OPEN_RY
        }
        // Small wavelets: denser near the shore, calmer in open water.
        var small = 0
        while (small < SMALL_WAVELETS) {
            val (x, y) = waterPoint()
            if (rnd.nextFloat() < 0.3f + 0.7f * exp(-coastDist(x, y) / 0.6f) && add(x, y, big = false)) small++
        }
        for (h in harbors) repeat(3) { add(h.x + (rnd.nextFloat() - 0.5f) * 0.5f, h.y + (rnd.nextFloat() - 0.5f) * 0.5f, big = false) }
        // Double-size crests out in the deeper water.
        var big = 0
        while (big < BIG_WAVELETS) { val (x, y) = waterPoint(); if (coastDist(x, y) > 1f && add(x, y, big = true)) big++ }
        wavelets = list.shuffled(Random(seed * 97))

        // Breaking spots: about two thirds of the coastline edges, away from the corners.
        breaks = coast.filter { rnd.nextFloat() < 0.7f }.mapIndexed { i, c ->
            val t = 0.3f + rnd.nextFloat() * 0.4f
            Break(c.ax + (c.bx - c.ax) * t, c.ay + (c.by - c.ay) * t, c.nx, c.ny,
                period = 5f + rnd.nextFloat() * 4f, phase = rnd.nextFloat(), count = 2 + rnd.nextInt(2), id = i)
        }
    }

    /** Where wavelet [w] is in its loop at [t] (0..1) and which loop it is on. */
    private fun loop(w: Wavelet, t: Float): Float { val u = t / w.period + w.phase; return u - floor(u) }
    private fun loopIndex(w: Wavelet, t: Float) = floor(t / w.period + w.phase).toInt()
    /** Opacity of [w] at time [t]: zero at the loop's ends, so it can reappear elsewhere unseen. */
    internal fun waveletAlpha(w: Wavelet, t: Float): Float { val s = sin(PI.toFloat() * loop(w, t)); return w.alpha * s * sqrt(s) }
    /** Centre of [w] at time [t]: home, plus a per-loop hop, plus a slow drift across its crest. */
    internal fun waveletX(w: Wavelet, t: Float) = w.x + hop(w.id, loopIndex(w, t), 0) * HOP + cos(w.angle + PI.toFloat() / 2) * DRIFT * loop(w, t)
    internal fun waveletY(w: Wavelet, t: Float) = w.y + hop(w.id, loopIndex(w, t), 1) * HOP + sin(w.angle + PI.toFloat() / 2) * DRIFT * loop(w, t)

    private class Puff(val x: Float, val y: Float, val r: Float, val life: Float, var age: Float = 0f, val dx: Float = 0f, val dy: Float = 0f)
    class Cloud(var x: Float, var y: Float, val drift: Float, val shape: Int, var poof: Float = -1f) {
        /** Pokes taken so far; the cloud poofs on the [POKES_TO_POOF]th. */
        var hits = 0
        /** Seconds left of the wobble from the last poke. */
        var wobble = 0f
        /** Velocity from a tap, nudge or flick, lattice units per second; it dies away on its own. */
        var vx = 0f
        var vy = 0f
        /** While dragged: where the finger wants the cloud, and the grab offset from the finger. */
        var dragging = false
        var targetX = 0f
        var targetY = 0f
        var grabDx = 0f
        var grabDy = 0f
    }
    private class Ring(val x: Float, val y: Float, var age: Float = 0f, val delay: Float = 0f)

    private val drops = ArrayList<Puff>()
    private val rings = ArrayList<Ring>()
    val clouds = ArrayList<Cloud>()
    /** Scenery amounts, 0..1 (from the menu's sliders): clouds, birds, harbour boats and waves. */
    var clouds01 = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (field == 0f) clouds.clear()
            // Slider changes take effect on this frame, including zero while dragging.
            while (clouds.count { it.poof < 0 } > cloudTarget) {
                clouds.remove(clouds.last { it.poof < 0 })
            }
            while (clouds.count { it.poof < 0 } < cloudTarget) clouds += newCloud()
        }
    var birds01 = 0.5f
    var boats01 = 0.5f
    var waves01 = 0.5f
    /**
     * Density, 0..1 (Settings' upright slider beside the four): one multiplier on every water object's count,
     * from none (0) through as set (0.5) to double (1), capped at each pool's size.
     */
    var density01 = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            clouds01 = clouds01 // re-sync the clouds to the new target now
        }
    /** How many of a [maximum]-sized pool show for slider [value], after the Density multiplier. */
    internal fun amount(value: Float, maximum: Int): Int =
        (sceneryCount(value, maximum) * 2f * density01).roundToInt().coerceIn(0, maximum)
    /** How fast passive things move (waves, glints, drift, gulls); 0.5 is normal. */
    var motion = 0.5f
    /** Clouds wanted on the sea: four at 50%, sixteen at 100%. */
    val cloudTarget get() = amount(clouds01, 16)
    /** Pale articulated seabirds (Birds slider sets how many show). */
    internal val birds = List(16) { i -> Seabird(seed * 31 + i, landR + 0.5f + 0.21f * i) }
    internal val birdCount get() = amount(birds01, birds.size)
    /** Boat positions (x, y pairs) handed to the birds each step, so some can circle a boat. */
    private val boatXY = FloatArray(2 * MAX_BOATS)
    internal val waveCount get() = amount(waves01, wavelets.size)
    internal val whitecapScale get() = 0.65f + 0.7f * waves01.coerceIn(0f, 1f)
    internal val whitecapAlpha get() = 0.4f + 0.6f * waves01.coerceIn(0f, 1f)
    private val birdPath = Path()
    private val glints = List(320) { val (x, y) = seaPoint(0.2f); floatArrayOf(x, y, rnd.nextFloat() * 6f, 0.6f + rnd.nextFloat()) }
    internal var raidersTrack = 0f
    internal var raidersTarget = 0
    private var raidersBattles: Int? = null
    private var raidersRunIn = 0f

    internal fun observeRaiders(track: Int, battles: Int) {
        if (raidersBattles != null && battles > raidersBattles!!) raidersRunIn = 8f
        raidersBattles = battles
        raidersTarget = track
    }
    internal var time = 0f
        private set

    // Tonal patches: two small tileable noise textures, built once (pixels here, the bitmap lazily on first draw).
    internal val patchPixels = arrayOf(patchTexture(PATCH_A_LOW, PATCH_A_HIGH, seed * 31 + 1), patchTexture(PATCH_B_LOW, PATCH_B_HIGH, seed * 31 + 2))
    private var patchBrushes: List<Pair<BitmapShader, ShaderBrush>>? = null

    /** Painted, seamlessly tiling ocean (ocean_tile, made with Mauro's ChatGPT); null in tests, where the gradient stands in. */
    var oceanBitmap: Bitmap? = null
    /** Painted sprites (Codex art via Pip); null in tests, where the vector fallbacks draw. */
    var gullFlap: ImageBitmap? = null
    var gullGlide: ImageBitmap? = null
    var gullShadow: ImageBitmap? = null
    var boatSprites: List<ImageBitmap>? = null
    var boatWake: ImageBitmap? = null
    /** The 3D sloops and Raiders' ship (#63); null in tests and until loaded, when the painted sprites draw. */
    var sprites3d: SeaSprites3d? = null
    /** The critters on this game's sea (the whale, when it rolled one): see [SeaCritter.forGame]. */
    internal var critters: List<SeaCritter> = emptyList()
    /** The open water in view at fit zoom (lattice, y down), set on the first draw; critters start once it is known. */
    internal var viewArea: Rect? = null
    /** The tilt step the water was last drawn at (the sky has no squash of its own to go by). */
    private var tiltStep3d = 0
    /** The board's squash the water was last drawn at, for critters drawn with the sky. */
    private var squash3d = 1f
    private var oceanBrushes: List<Pair<BitmapShader, ShaderBrush>>? = null
    private val oceanMatrix = Matrix()
    private val patchMatrix = Matrix()
    private val crescent = Path()

    init { repeat(4) { clouds += newCloud() } } // 4 = the default cloud amount (0.5 squared of 16)

    /** A tiny sailboat cruising the open sea toward a target, its sail in a player's colour. */
    internal class Boat(var x: Float, var y: Float, var tx: Float, var ty: Float, var heading: Float, val speed: Float, val color: Int, val phase: Float) {
        /** Where the boat has been (x, y pairs, newest first): its wake follows the real path. */
        val trail = FloatArray(2 * TRAIL)
        var trailN = 0
        var trailT = 0f
        /** Seconds before the boat may come about again (stops it flip-flopping at a lane end). */
        var turnCool = 0f
        var laneDir = if (cos(heading) >= 0f) 1f else -1f
        var passSide = if (y >= 0f) 1f else -1f
        var laneStartX = -laneDir * OPEN_RX
    }
    /** Sail colours for the cruising boats (the players' colours). */
    var boatColors: List<Color> = PLAYER_COLORS
    internal val boats: List<Boat> = List(MAX_BOATS) { i ->
        val (x, y) = seaPoint(0.7f); val (tx, ty) = seaPoint(0.7f)
        Boat(x, y.coerceIn(-LANE_Y, LANE_Y), tx, y.coerceIn(-LANE_Y, LANE_Y), if (rnd.nextBoolean()) 0f else PI.toFloat(), 0.10f + rnd.nextFloat() * 0.08f, i % 4, rnd.nextFloat() * 6f)
    }
    /** Boats shown at the Boats slider's setting (none at 0, all at 1). */
    val boatCount get() = amount(boats01, MAX_BOATS)

    /** A random point on the open sea at least [margin] from the coast and the screen edge. */
    private fun seaPoint(margin: Float): Pair<Float, Float> {
        while (true) {
            val x = (rnd.nextFloat() * 2 - 1) * OPEN_RX
            val y = (rnd.nextFloat() * 2 - 1) * OPEN_RY
            if (hypot(x, y) > landR + margin && inOpenSea(x, y, margin)) return x to y
        }
    }

    /** True if (x, y) lies at least [margin] inside the open-sea ellipse (the screen at fit zoom). */
    fun inOpenSea(x: Float, y: Float, margin: Float = 0f): Boolean {
        val rx = OPEN_RX - margin; val ry = OPEN_RY - margin
        return rx > 0 && ry > 0 && (x / rx) * (x / rx) + (y / ry) * (y / ry) <= 1f
    }
    private fun newCloud(): Cloud { val (x, y) = seaPoint(0.35f); return Cloud(x, y, 0.012f + rnd.nextFloat() * 0.02f, rnd.nextInt(3)) }

    /** Advances the animation by [dt] seconds. */
    fun update(dt: Float) {
        if (!(dt > 0f)) return // zero, negative or NaN steps: nothing moves (and nothing divides by them)
        // Passive animation (waves, glints, drift, gulls) runs faster or slower with the Motion setting;
        // touch physics on clouds uses real time and is unaffected.
        val pdt = dt * (0.25f + 1.5f * motion)
        time += pdt
        raidersRunIn = (raidersRunIn - pdt).coerceAtLeast(0f)
        val orbitTarget = if (raidersRunIn > 0f) wgbg.engine.ChivalryPiece.BARBARIAN_TRACK.toFloat() else raidersTarget.toFloat()
        raidersTrack += (orbitTarget - raidersTrack) * (1f - exp(-pdt / 3f))
        viewArea?.let { v -> for (c in critters) c.update(pdt, v) }
        for (p in drops) p.age += dt
        drops.removeAll { it.age > it.life }
        for (r in rings) r.age += dt
        rings.removeAll { it.age - it.delay > 1.3f }
        for (i in 0 until boatCount) { boatXY[2 * i] = boats[i].x; boatXY[2 * i + 1] = boats[i].y }
        for (b in birds) b.update(pdt, boatXY, boatCount)
        for (b in boats) sail(b, pdt)
        for (c in clouds) {
            if (c.poof >= 0) { c.poof += dt; continue }
            c.wobble = (c.wobble - dt).coerceAtLeast(0f)
            if (c.dragging) {
                // Follows the finger smoothly (frame-rate independent), keeping where it was grabbed.
                val k = CloudFeel.followFraction(dt)
                // No bounds while held: the cloud goes exactly where the finger takes it.
                c.x += (c.targetX - c.x) * k; c.y += (c.targetY - c.y) * k
                continue
            }
            // The slow drift around the island pauses while the cloud is moving, so throws go straight.
            if (c.vx == 0f && c.vy == 0f) {
                val a = atan2(c.y, c.x) + c.drift * pdt
                val d = hypot(c.x, c.y)
                c.x = cos(a) * d; c.y = sin(a) * d
            }
            // Momentum from a tap, nudge or flick: carries on, slows smoothly to rest, bounces softly off the bounds.
            if (c.vx != 0f || c.vy != 0f) {
                c.x += c.vx * dt; c.y += c.vy * dt
                val damp = CloudFeel.damp(dt)
                c.vx *= damp; c.vy *= damp
                if (hypot(c.vx, c.vy) < CloudFeel.REST) { c.vx = 0f; c.vy = 0f }
            }
            keepOnSea(c)
        }
        // A poofed cloud is replaced a few seconds later somewhere else on the sea; the count follows
        // the density setting (extra clouds drift off by fading, missing ones drift in).
        clouds.removeAll { it.poof > 3.5f }
        val live = clouds.count { it.poof < 0 }
        if (live > cloudTarget) clouds.lastOrNull { it.poof < 0 && !it.dragging }?.let { it.poof = 0f }
        else if (clouds.count { it.poof < 0 } < cloudTarget && clouds.size < cloudTarget + 2) clouds += newCloud()
    }

    /** Steers [b] toward its target, turning gently and going around the island; picks a new target on arrival. */
    /**
     * Side-view sloops can only look right sailing across the screen, so each boat cruises east-west
     * lanes: it holds a heading within ~25 degrees of horizontal, slows into a gentle turn at the end of
     * its lane, and passes the island above or below rather than steering into it.
     */
    private fun sail(b: Boat, dt: Float) {
        val dir = b.laneDir
        b.turnCool = (b.turnCool - dt).coerceAtLeast(0f)
        // End of the lane (near the screen edge): come about once, onto a new lane well inside the sea.
        if (b.turnCool == 0f && abs(b.x - b.laneStartX) >= OPEN_RX * 1.4f && abs(b.x) >= landR + 3.1f &&
            !inOpenSea(b.x + dir * 1.2f, b.y, 0.4f)) {
            b.laneDir = -dir
            b.tx = b.laneDir * OPEN_RX * 0.95f
            b.laneStartX = b.x
            if (abs(b.y) > 0.5f) b.passSide = if (b.y > 0f) 1f else -1f
            b.ty = b.passSide * LANE_Y
            b.turnCool = 20f
        }
        // Never drift toward the narrow top/bottom of the sea: lanes stay within LANE_Y.
        if (kotlin.math.abs(b.ty) > LANE_Y) b.ty = kotlin.math.sign(b.ty) * LANE_Y
        var ty = b.ty
        // Keep clear of the island: lanes crossing it bend early to pass above or below.
        if (kotlin.math.abs(b.x) < landR + 3f && kotlin.math.abs(ty) < landR + 0.8f) ty = b.passSide * (landR + 0.35f)
        val near = hypot(b.x, b.y) < landR + 1.2f
        val maxClimb = if (near) 0.9f else 0.45f
        val climb = atan2((ty - b.y) * 0.6f, 1f).coerceIn(-maxClimb, maxClimb)
        val want = if (b.laneDir > 0) climb else PI.toFloat() - climb
        var turn = want - b.heading
        while (turn > PI) turn -= 2 * PI.toFloat()
        while (turn < -PI) turn += 2 * PI.toFloat()
        b.heading += turn.coerceIn(-BOAT_TURN_RATE * dt, BOAT_TURN_RATE * dt)
        b.x += cos(b.heading) * b.speed * dt; b.y += sin(b.heading) * b.speed * dt
        // Never onto the land: a boat that got close is eased back out.
        val d = hypot(b.x, b.y)
        if (d < landR + 0.3f) { b.x *= (landR + 0.3f) / d; b.y *= (landR + 0.3f) / d }
        val edge = sqrt((b.x / (OPEN_RX - 0.4f)).pow(2) + (b.y / (OPEN_RY - 0.4f)).pow(2))
        if (edge > 1f) { b.x /= edge; b.y /= edge }
        // Record the wake trail every 0.2 s.
        b.trailT += dt
        if (b.trailT >= 0.2f) {
            b.trailT = 0f
            System.arraycopy(b.trail, 0, b.trail, 2, b.trail.size - 2)
            b.trail[0] = b.x; b.trail[1] = b.y
            if (b.trailN < TRAIL) b.trailN++
        }
    }

    /** True if [p] (lattice) is on open water. */
    fun isWater(p: Offset) = hypot(p.x, p.y) > landR && inOpenSea(p.x, p.y)

    /**
     * The cloud under lattice point [p], if any. Clouds drift over the island too (Mauro, 2026-10-10), but a
     * touch on the island always goes to the board (building spots), so only touches over the water grab one.
     */
    fun cloudAt(p: Offset): Cloud? =
        if (!isWater(p)) null else clouds.lastOrNull { it.poof < 0 && hypot(p.x - it.x, p.y - it.y) < 0.38f }

    /**
     * Pokes a cloud. It shrugs off the first few pokes (it wobbles, shrinks a little and sheds a
     * few wisps) and poofs on the [POKES_TO_POOF]th.
     */
    fun poof(c: Cloud, from: Offset? = null) {
        if (c.poof >= 0) return
        c.hits++
        if (c.hits < POKES_TO_POOF) {
            c.wobble = WOBBLE
            // Each poke also pushes the cloud gently away from the finger.
            val (vx, vy) = CloudFeel.impulseAway(c.x, c.y, from?.x ?: c.x, from?.y ?: c.y, rnd.nextFloat() * 2 * PI.toFloat())
            push(c, vx, vy)
            repeat(3) {
                val a = -PI.toFloat() / 2 + (rnd.nextFloat() - 0.5f) * 2.4f
                drops += Puff(c.x, c.y, 0.045f, 0.45f, dx = cos(a) * 0.3f, dy = sin(a) * 0.3f)
            }
            return
        }
        c.poof = 0f
        repeat(9) { i ->
            val a = i / 9f * 2 * PI.toFloat()
            drops += Puff(c.x, c.y, 0.07f, 0.55f, dx = cos(a) * 0.45f, dy = sin(a) * 0.45f)
        }
    }

    /** Sets a cloud moving at ([vx], [vy]) lattice units/s (a flick); capped at [CloudFeel.MAX_SPEED]. */
    fun fling(c: Cloud, vx: Float, vy: Float) {
        if (c.poof >= 0 || !vx.isFinite() || !vy.isFinite()) return
        c.dragging = false
        val (cx, cy) = CloudFeel.cap(vx, vy)
        c.vx = cx; c.vy = cy
    }

    /** Adds ([vx], [vy]) to a cloud's velocity (taps and nudges stack), capped. */
    fun push(c: Cloud, vx: Float, vy: Float) {
        if (c.poof >= 0 || !vx.isFinite() || !vy.isFinite()) return
        val (cx, cy) = CloudFeel.cap(c.vx + vx, c.vy + vy)
        c.vx = cx; c.vy = cy
    }

    /** Picks a cloud up at [at]: it stops and remembers where on it the finger is. */
    fun grab(c: Cloud, at: Offset) {
        c.vx = 0f; c.vy = 0f
        c.dragging = true
        c.grabDx = c.x - at.x; c.grabDy = c.y - at.y
        c.targetX = c.x; c.targetY = c.y
    }

    /** Drags a grabbed cloud toward the finger at [to]; it follows smoothly and stays over the water. */
    fun move(c: Cloud, to: Offset) {
        if (!c.dragging) grab(c, to)
        c.targetX = to.x + c.grabDx; c.targetY = to.y + c.grabDy
    }

    /** Lets go of a dragged cloud (follow it with [fling] or [push] to give it momentum). */
    fun release(c: Cloud) { c.dragging = false }

    /** Keeps [c] on the table: it may drift over the island, and bounces softly off the open-sea edge. */
    private fun keepOnSea(c: Cloud) {
        val rx = OPEN_RX - CLOUD_EDGE; val ry = OPEN_RY - CLOUD_EDGE
        val q = sqrt((c.x / rx) * (c.x / rx) + (c.y / ry) * (c.y / ry))
        if (q > 1f) {
            var nx = c.x / (rx * rx); var ny = c.y / (ry * ry)
            val n = hypot(nx, ny); nx /= n; ny /= n
            val vr = c.vx * nx + c.vy * ny
            if (vr > 0) { c.vx -= (1 + BOUNCE) * vr * nx; c.vy -= (1 + BOUNCE) * vr * ny }
            c.x /= q; c.y /= q
        }
    }

    fun splash(p: Offset) {
        repeat(3) { rings += Ring(p.x, p.y, delay = it * 0.14f) }
        repeat(7) {
            val a = -PI.toFloat() / 2 + (rnd.nextFloat() - 0.5f) * 2.2f
            val v = 0.3f + rnd.nextFloat() * 0.3f
            drops += Puff(p.x, p.y, 0.04f + rnd.nextFloat() * 0.04f, 0.75f, dx = cos(a) * v, dy = sin(a) * v)
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Which part of the water [drawWater] paints: the slow painted base can be cached; the rest moves every frame. */
    enum class WaterPart { BASE, LIVE, ALL }

    /**
     * Water, waves, surf and splashes: drawn before the tiles. [at] maps lattice to screen; [s] is px per side.
     * BASE is the painted sea (gradient, drifting ocean texture, shallows, surf line), which only drifts a few
     * px a second, so the board caches it; LIVE is what moves every frame (glints, wavelets, boats, splashes).
     */
    fun drawWater(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, squash: Float, woodBrush: Brush, part: WaterPart = WaterPart.ALL) = with(ds) {
        tiltStep3d = SeaSprites3d.tiltStep(kotlin.math.acos(squash.coerceIn(0f, 1f)))
        squash3d = squash
        if (part != WaterPart.LIVE) {
            val c = at(0f, 0f)
            // The ocean fills the whole screen (no frame); deeper toward the edges.
            val far = maxOf(hypot(c.x, c.y), hypot(size.width - c.x, c.y), hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y))
                .coerceAtLeast(1f)
            val f = { r: Float -> (r * s / far).coerceIn(0.01f, 0.99f) }
            val all = Rect(Offset.Zero, size)
            drawRect(Brush.radialGradient(
                0.0f to DEEP_SHORE, f(SEA_R * 0.72f) to DEEP_SHORE, f(SEA_R) to DEEP_MID, 1f to DEEP_RIM,
                center = c, radius = far,
            ), all.topLeft, all.size)
            val ocean = oceanBitmap
            if (ocean != null) drawOcean(this, ocean, c, s, squash, all, far) else drawPatches(this, c, s, squash, all)
            // Shallows hugging the coast: stacked translucent hex halos read as a soft gradient.
            for ((grow, alpha) in listOf(1.22f to 0.18f, 1.14f to 0.22f, 1.07f to 0.28f)) {
                drawPath(coastPath(at, grow), SHALLOW.copy(alpha = alpha))
            }
            // Surf line on the coast (tiles cover its inner half).
            drawPath(coastPath(at, 1.06f), Color.White.copy(alpha = 0.8f), style = Stroke(0.05f * s))
            if (part == WaterPart.BASE) return@with
        }
        // Critters below the board and under the water, under the waves and glints.
        drawCritters(this, "under", at, s); drawCritters(this, "underwater", at, s)
        // Sun glints twinkling.
        // Everything keeps moving off screen (positions stay tracked); only what's on screen is drawn.
        val glintCount = amount(waves01, glints.size)
        for (i in 0 until glintCount) {
            val g = glints[i]
            val a = (sin(time * g[3] + g[2]) * 0.5f + 0.5f)
            if (a > 0.55f) { val q = at(g[0], g[1]); if (onScreen(q, 0.05f * s)) drawCircle(Color.White.copy(alpha = (a - 0.55f) * 1.4f), 0.018f * s, q) }
        }
        // Waves slider: how many wavelets and whitecaps show (none at 0; the water's colour and texture stay).
        for (i in 0 until waveCount) { val w = wavelets[i]; if (onScreen(at(w.x, w.y), 0.6f * s)) drawWavelet(this, at, w) }
        for (i in 0 until amount(waves01, breaks.size)) { val k = breaks[i]; if (onScreen(at(k.x, k.y), 0.8f * s)) drawBreak(this, at, s, k) }
        // Critters on the water (the whale), under the boats: each one's frame for the clip it plays, bow along its course.
        drawCritters(this, "surface", at, s)
        // Tiny sailboats (Boats slider): a wooden hull with a little wake, the sail in a player's colour.
        val sprites = boatSprites
        for (i in 0 until boatCount) {
            val bt = boats[i]
            if (!onScreen(at(bt.x, bt.y), 1.2f * s)) continue
            val s3d = sprites3d
            val sb = s * depthScale(at, bt.x, bt.y, s)   // nearer rows a little bigger, as the tiles are
            if (s3d != null) drawBoat3d(this, at, sb, squash, bt, s3d)
            else if (sprites != null) drawBoatSprite(this, at, sb, bt, sprites) else drawBoat(this, at, sb, bt)
        }
        for (r in rings) {
            val t = ((r.age - r.delay) / 1.2f).coerceIn(0f, 1f)
            if (r.age < r.delay) continue
            val rr = (0.06f + t * 0.6f) * s
            val cc = at(r.x, r.y)
            drawOval(Color.White.copy(alpha = 0.9f * (1 - t)), cc - Offset(rr, rr * 0.62f * squash), Size(2 * rr, 2 * rr * 0.62f * squash), style = Stroke(0.03f * s))
        }
    }

    /**
     * The painted ocean: the tile repeated across the screen, with a second copy turned and drifting the
     * other way at low alpha so the surface shimmers, and the edges sinking to deep water.
     */
    private fun drawOcean(ds: DrawScope, bmp: Bitmap, c: Offset, s: Float, squash: Float, area: Rect, far: Float) {
        val brushes = oceanBrushes ?: List(3) {
            val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sh to ShaderBrush(sh)
        }.also { oceanBrushes = it }
        val tile = OCEAN_TILE * s / bmp.width                  // one tile spans OCEAN_TILE hex sides
        // Three copies at non-multiple scales and different angles, so the tile's repeat never lines up into a grid.
        for ((i, pair) in brushes.withIndex()) {
            val (shader, brush) = pair
            val k = OCEAN_LAYERS[i]
            val drift = time * k[3]
            oceanMatrix.reset()
            oceanMatrix.setScale(tile * k[0], tile * k[0] * squash)
            oceanMatrix.postRotate(k[1])
            oceanMatrix.postTranslate(c.x + drift * s, c.y + drift * s * 0.45f * squash)
            shader.setLocalMatrix(oceanMatrix)
            ds.drawRect(brush, area.topLeft, area.size, alpha = k[2], colorFilter = BOARD_FILTER)
        }
        // Broad soft mottling on top breaks up what is left of the repeat.
        drawPatches(ds, c, s, squash, area, strength = 0.4f)
        // Deeper toward the edges of the screen; the island's waters stay bright.
        ds.drawRect(Brush.radialGradient(
            0f to Color.Transparent, (SEA_R * s / far).coerceIn(0.05f, 0.95f) to Color(0x22000A1E), 1f to Color(0x99000A1E),
            center = c, radius = far,
        ), area.topLeft, area.size)
    }

    /** A painted sloop seen side-on, flipped to face the way it sails, its wake trailing behind. */
    private fun drawBoatSprite(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, b: Boat, sprites: List<ImageBitmap>) = with(ds) {
        val p = at(b.x, b.y)
        val size = 0.95f * s
        val bob = sin(time * 2.2f + b.phase) * 0.02f * s
        val right = cos(b.heading) >= 0f
        val img = sprites[b.color % sprites.size]
        // Wake: two thin foam lines spreading from the stern along the path the boat actually took,
        // fading with age, plus a little churn right at the stern.
        if (b.trailN > 1) for (side in -1..1 step 2) {
            var px = 0f; var py = 0f
            for (i in 0 until b.trailN) {
                val x = b.trail[2 * i]; val y = b.trail[2 * i + 1]
                val nx = if (i + 1 < b.trailN) b.trail[2 * i + 2] else x; val ny = if (i + 1 < b.trailN) b.trail[2 * i + 3] else y
                val dx = x - nx; val dy = y - ny; val l = hypot(dx, dy).coerceAtLeast(1e-4f)
                val spread = 0.03f + i * 0.022f
                val q = at(x - dy / l * spread * side, y + dx / l * spread * side + 0.19f)  // at the waterline
                if (i > 0) drawLine(Color.White.copy(alpha = 0.6f * (1f - i / TRAIL.toFloat())), Offset(px, py), q, (0.022f - i * 0.0012f).coerceAtLeast(0.006f) * s, StrokeCap.Round)
                px = q.x; py = q.y
            }
        }
        drawCircle(Color.White.copy(alpha = 0.3f), 0.05f * s, at(b.x - cos(b.heading) * 0.2f, b.y + 0.19f))
        // Rolls gently on the swell and pitches a touch with its climb, pivoting at the waterline.
        val roll = sin(time * 1.3f + b.phase * 2f) * 2.5f + sin(b.heading) * 2f * (if (right) 1f else -1f)
        val water = Offset(p.x, p.y + size * 0.22f)
        withTransform({ rotate(roll, water); if (!right) scale(-1f, 1f, p) }) {
            drawImage(img, dstOffset = IntOffset((p.x - size / 2).toInt(), (p.y - size * 0.7f + bob).toInt()), dstSize = IntSize(size.toInt(), size.toInt()),
                filterQuality = FilterQuality.Medium)
        }
    }

    /** A 3D sloop (#63): bow-first along its course, its sail in its player's colour, bobbing on the swell. */
    private fun drawBoat3d(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, squash: Float, b: Boat, s3d: SeaSprites3d) = with(ds) {
        val bob = sin(time * 2.2f + b.phase) * 0.02f * s
        val p = at(b.x, b.y) + Offset(0f, bob)
        val tilt = kotlin.math.acos(squash.coerceIn(0f, 1f))
        drawSeaProp(s3d, p, SeaSprites3d.SLOOP_FRAME * s, SeaSprites3d.headingIndex(b.heading, squash), SeaSprites3d.tiltStep(tilt),
            SeaArt.sloop, SeaArt.sloopTrim, SeaArt.sloopWake, tint = boatColors[b.color % boatColors.size])
    }

    private fun drawBoat(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, b: Boat) = with(ds) {
        val p = at(b.x, b.y)
        val u = 0.07f * s                                  // the boat is about a third of a hex side long
        val dx = cos(b.heading); val dy = sin(b.heading)
        val bob = sin(time * 2.2f + b.phase) * 0.06f * u
        fun pt(f: Float, side: Float) = Offset(p.x + (dx * f - dy * side) * u, p.y + (dy * f + dx * side) * u + bob)
        // Wake.
        drawLine(Color.White.copy(alpha = 0.35f), pt(-2.2f, 0f), pt(-5.5f, 0.9f), 0.25f * u, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.35f), pt(-2.2f, 0f), pt(-5.5f, -0.9f), 0.25f * u, StrokeCap.Round)
        // Hull (pointed bow).
        val hull = Path().apply { moveTo(pt(2.6f, 0f).x, pt(2.6f, 0f).y); lineTo(pt(-2f, 0.8f).x, pt(-2f, 0.8f).y); lineTo(pt(-2f, -0.8f).x, pt(-2f, -0.8f).y); close() }
        drawPath(hull, Color(0xFF6B3E1F))
        // Sail: a coloured triangle standing up from the mast (screen-up), with a dark outline for light colours.
        val mast = pt(0.2f, 0f)
        val sail = Path().apply { moveTo(mast.x, mast.y - 3.4f * u); lineTo(mast.x, mast.y - 0.3f * u); lineTo(mast.x + 2.0f * u * (if (dx >= 0) 1 else -1), mast.y - 0.4f * u); close() }
        drawPath(sail, boatColors[b.color % boatColors.size])
        drawPath(sail, Color(0x66000000), style = Stroke(0.12f * u))
        drawLine(Color(0xFF3B2412), mast, mast + Offset(0f, -3.6f * u), 0.18f * u)
    }

    /** Two translucent layers of soft tonal patches drifting slowly in different directions. */
    private fun drawPatches(ds: DrawScope, c: Offset, s: Float, squash: Float, area: Rect, strength: Float = 1f) {
        val brushes = patchBrushes ?: patchPixels.map { px ->
            val bmp = Bitmap.createBitmap(px, PATCH_TEX, PATCH_TEX, Bitmap.Config.ARGB_8888)
            val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sh to ShaderBrush(sh)
        }.also { patchBrushes = it }
        for ((i, pair) in brushes.withIndex()) {
            val (shader, brush) = pair
            // Layer A: broad blotches; layer B: longer flowing bands, turned and drifting the other way.
            val tile = if (i == 0) PATCH_TILE_A else PATCH_TILE_B
            val k = tile * s / PATCH_TEX
            val drift = time * if (i == 0) 0.018f else -0.011f
            patchMatrix.reset()
            patchMatrix.setScale(k * if (i == 0) 1f else 1.9f, k * squash)
            patchMatrix.postRotate(if (i == 0) 0f else 24f)
            patchMatrix.postTranslate(c.x + drift * s * 1.3f, c.y + drift * s * squash * 0.6f + i * 37f)
            shader.setLocalMatrix(patchMatrix)
            ds.drawRect(brush, area.topLeft, area.size, alpha = strength * if (i == 0) 0.55f + 0.1f * sin(time * 0.21f) else 0.45f + 0.1f * sin(time * 0.17f + 2f))
        }
    }

    /** A tapered crescent from [x0],[y0] to [x1],[y1], bowing [bow] toward ([nx],[ny]), [thick] at its middle. */
    private fun crescentPath(at: (Float, Float) -> Offset, x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float, bow: Float, thick: Float): Path {
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val a = at(x0, y0); val b = at(x1, y1)
        val k1 = at(mx + nx * 2 * bow, my + ny * 2 * bow)
        val k2 = at(mx + nx * (2 * bow - 2 * thick), my + ny * (2 * bow - 2 * thick))
        return crescent.apply { reset(); moveTo(a.x, a.y); quadraticTo(k1.x, k1.y, b.x, b.y); quadraticTo(k2.x, k2.y, a.x, a.y); close() }
    }

    private fun drawWavelet(ds: DrawScope, at: (Float, Float) -> Offset, w: Wavelet) {
        val a = waveletAlpha(w, time) * whitecapAlpha
        if (a < 0.01f) return
        val u = loop(w, time); val n = loopIndex(w, time)
        // Each loop it takes a slightly different angle and length; within a loop it lengthens and bends.
        val ang = w.angle + hop(w.id, n, 2) * 0.35f
        val px = cos(ang); val py = sin(ang)
        val nx = -py; val ny = px
        val swell = sin(PI.toFloat() * u)
        val half = w.len * whitecapScale / 2 * (0.6f + 0.4f * swell) * (1 + 0.25f * hop(w.id, n, 3))
        val bow = w.len * (0.24f + 0.1f * sin(2 * PI.toFloat() * u + w.id))
        val thick = whitecapScale * maxOf(0.016f, w.len * if (w.big) 0.12f else 0.15f)
        val cx = waveletX(w, time); val cy = waveletY(w, time)
        // A slight hook: one end trails, so it reads as a brush stroke rather than a symmetric arc.
        val hook = w.len * 0.15f * hop(w.id, n, 4)
        val x0 = cx - px * half - nx * hook; val y0 = cy - py * half - ny * hook
        val x1 = cx + px * half; val y1 = cy + py * half
        // Darker neighbouring stroke just behind for depth, then the light crest.
        val sh = thick * 1.4f
        ds.drawPath(crescentPath(at, x0 - nx * sh, y0 - ny * sh + 0.012f, x1 - nx * sh, y1 - ny * sh + 0.012f, nx, ny, bow, thick * 1.1f), SHADOW.copy(alpha = a * 0.75f))
        ds.drawPath(crescentPath(at, x0, y0, x1, y1, nx, ny, bow, thick), w.color.copy(alpha = a))
    }

    /** A cluster of little crescents curling in toward the coast, then breaking into flecks that fade. */
    private fun drawBreak(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, b: Break) {
        val u = (time / b.period + b.phase).let { it - floor(it) }
        if (u > 0.62f) return
        val n = floor(time / b.period + b.phase).toInt()
        val tx = -b.ny; val ty = b.nx                    // along the coast
        val approach = (u / 0.38f).coerceAtMost(1f)       // rolling in
        val breakP = ((u - 0.3f) / 0.32f).coerceIn(0f, 1f) // turning to flecks
        val off = 0.32f - 0.16f * smooth(approach)
        for (i in 0 until b.count) {
            val along = (i - (b.count - 1) / 2f) * 0.13f + hop(b.id, n * 7 + i, 0) * 0.04f
            val len = (0.14f + 0.06f * hop(b.id, n * 7 + i, 1).let { abs(it) }) * (0.6f + 0.4f * smooth(approach))
            val stagger = if (i % 2 == 1) 0.03f else 0f
            val cx = b.x + tx * along + b.nx * (off + stagger)
            val cy = b.y + ty * along + b.ny * (off + stagger)
            // The crest bows toward the land (it's curling over onto the shore) and whitens as it steepens.
            val a = smooth(u / 0.12f) * (1 - smooth(breakP * 1.4f))
            if (a > 0.01f) {
                val col = lerpColor(FOAM_CYAN, FOAM_WHITE, smooth(approach))
                ds.drawPath(crescentPath(at, cx - tx * len / 2, cy - ty * len / 2, cx + tx * len / 2, cy + ty * len / 2,
                    -b.nx, -b.ny, len * (0.14f + 0.12f * approach), len * 0.28f), col.copy(alpha = 0.9f * a))
            }
            // Flecks: short bright dashes left where the crest broke, drifting in and fading.
            if (breakP > 0f) {
                val fa = sin(PI.toFloat() * breakP) * 0.8f
                for (j in 0 until 3) {
                    val q = hop(b.id, n * 31 + i * 3 + j, 2)
                    val fx = cx + tx * len * 0.5f * q - b.nx * 0.05f * breakP
                    val fy = cy + ty * len * 0.5f * q - b.ny * 0.05f * breakP
                    val fl = 0.012f + 0.012f * abs(hop(b.id, n * 31 + i * 3 + j, 3))
                    ds.drawLine(FOAM_WHITE.copy(alpha = fa * (0.6f + 0.4f * abs(q))), at(fx - tx * fl, fy - ty * fl), at(fx + tx * fl, fy + ty * fl),
                        0.024f * s, StrokeCap.Round)
                }
            }
        }
    }

    /** Clouds, gulls and splash droplets: drawn after the board. */
    fun drawSky(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, includeBirds: Boolean = true) = with(ds) {
        for (p in drops) {
            val t = p.age / p.life
            // Droplets arc up and fall back (cloud puffs just spread out).
            val lift = if (p.dy < 0) -sin(PI.toFloat() * t) * 0.15f else 0f
            drawCircle(Color(0xFFE8F7FF).copy(alpha = 1 - t), p.r * s, at(p.x + p.dx * t, p.y + p.dy * t * 0.4f + lift))
        }
        drawCritters(this, "air", at, s)
        for (bi in 0 until if (includeBirds) birdCount else 0) {
            val b = birds[bi]
            if (!onScreen(at(b.x, b.y), 1f * s)) continue // shadow included
            // A faint shadow on the water below, then the bird. Higher up it looks a little bigger, and its shadow
            // falls further from it (away from the sun, upper left) and fainter.
            val lift = (0.8f + 0.45f * b.altitude) * depthScale(at, b.x, b.y, s)
            val drop = 0.45f + 1.1f * b.altitude
            val shade = 0.72f - 0.32f * b.altitude
            val flap = gullFlap; val glide = gullGlide; val s3d = sprites3d
            if (s3d != null) {
                // 3D gull (#63): a 16-frame wingbeat (wings level while gliding), turned to its heading, shadow below.
                val size = SeaSprites3d.GULL_FRAME * s * b.scale * lift
                val p = at(b.x, b.y)
                val deg = Math.toDegrees(b.heading.toDouble()).toFloat()
                gullShadow?.let { sh ->
                    val q = at(b.x + 0.3f * drop, b.y + 0.45f * drop); val sz = 0.6f * s * b.scale
                    withTransform({ rotate(deg, q) }) { drawImage(sh, dstOffset = IntOffset((q.x - sz / 2).toInt(), (q.y - sz / 2).toInt()), dstSize = IntSize(sz.toInt(), sz.toInt()), alpha = shade) }
                }
                withTransform({ rotate(deg, p) }) { drawSeaProp(s3d, p, size, b.flapFrame(SeaArt.HEADINGS).coerceAtLeast(0), tiltStep3d, SeaArt.gull) }
            } else if (flap != null && glide != null) {
                // Painted gull: about a quarter of a hex wide, turned to its heading, shadow on the water below.
                val size = 0.6f * s * b.scale * lift   // the bird fills about 45% of its frame: ~1/4 hex wide
                val p = at(b.x, b.y)
                val deg = Math.toDegrees(b.heading.toDouble()).toFloat()
                gullShadow?.let { sh ->
                    val q = at(b.x + 0.3f * drop, b.y + 0.45f * drop)
                    withTransform({ rotate(deg, q) }) { drawImage(sh, dstOffset = IntOffset((q.x - size / 2).toInt(), (q.y - size / 2).toInt()), dstSize = IntSize(size.toInt(), size.toInt()), alpha = shade) }
                }
                val frame = b.flapFrame(flap.width / flap.height)
                withTransform({ rotate(deg, p) }) {
                    if (frame >= 0) drawImage(flap, srcOffset = IntOffset(frame * flap.height, 0), srcSize = IntSize(flap.height, flap.height),
                        dstOffset = IntOffset((p.x - size / 2).toInt(), (p.y - size / 2).toInt()), dstSize = IntSize(size.toInt(), size.toInt()), filterQuality = FilterQuality.Medium)
                    else drawImage(glide, dstOffset = IntOffset((p.x - size / 2).toInt(), (p.y - size / 2).toInt()), dstSize = IntSize(size.toInt(), size.toInt()), filterQuality = FilterQuality.Medium)
                }
            } else {
                drawCircle(Color(0x22001020).copy(alpha = 0.13f * shade / 0.6f), 0.12f * s, at(b.x + 0.25f * drop, b.y + 0.35f * drop))
                b.draw(this, at(b.x, b.y), 0.28f * s * lift, birdPath)
            }
        }
        for (c in clouds) {
            // Over the island a cloud thins out a little so the board under it stays readable.
            val fade = (if (c.poof >= 0) (1 - c.poof / 0.25f).coerceAtLeast(0f) else 1f) * (if (hypot(c.x, c.y) < landR) OVER_LAND_ALPHA else 1f)
            if (fade <= 0f) continue
            val p = at(c.x, c.y)
            if (!onScreen(p, 1.2f * s)) continue
            // Each poke leaves it a bit smaller; the latest one makes it jiggle.
            val jig = c.wobble / WOBBLE
            val u = 0.16f * s * (1 - 0.08f * c.hits) * (1 + 0.12f * jig * sin(c.wobble * 40f))
            drawOval(Color(0xFF002040).copy(alpha = 0.22f * fade), p + Offset(-1.1f * u, 1.0f * u), Size(2.8f * u, 0.6f * u))
            // The puffs come from a sprite painted once per shape and zoom bucket, then scaled and faded.
            val (img, box) = cloudSprite(c.shape, u)
            drawImage(img, dstOffset = IntOffset((p.x + box.left * u).toInt(), (p.y + box.top * u).toInt()),
                dstSize = IntSize((box.width * u).toInt(), (box.height * u).toInt()), alpha = fade, filterQuality = FilterQuality.Medium)
        }
        drawCritters(this, "sky", at, s)
    }

    /**
     * How much bigger the board draws things at lattice ([x], [y]) than [s] (its scale at the middle row): the tilted
     * board's perspective makes nearer rows bigger, and this reads it straight off the projection [at].
     */
    private fun depthScale(at: (Float, Float) -> Offset, x: Float, y: Float, s: Float): Float =
        ((at(x + 0.5f, y).x - at(x - 0.5f, y).x) / s).takeIf { it.isFinite() && it > 0f } ?: 1f

    /** The critters in [layer] (critter spec, Layers): each one's frame for the clip it plays, turned to its course. */
    private fun drawCritters(ds: DrawScope, layer: String, at: (Float, Float) -> Offset, s: Float) = with(ds) {
        for (c in critters) {
            val m = c.module
            val pose = c.pose() ?: continue
            if (m.spec.layerAt(pose.z) != layer) continue   // its own layer, or (auto) the band its height is in
            val fr = m.frameFor(pose) ?: continue
            val q = c.at.let { at(it.x, it.y) }
            val side = m.frames.span * s * depthScale(at, c.at.x, c.at.y, s)
            if (onScreen(q, side)) drawCritterFrame(m, q, side, fr, m.headingIndex(c.course, squash3d), m.tiltIndex(tiltStep3d))
        }
    }

    /**
     * The 3D board (#63): the critters in and on the water (the whale), drawn over the live water at the board's [tilt]
     * (radians from straight down). They keep clear of the coast, so they never cover a tile.
     */
    internal fun drawLiveCritters(ds: DrawScope, at: (Float, Float) -> Offset, s: Float, tilt: Float) {
        squash3d = cos(tilt); tiltStep3d = SeaSprites3d.tiltStep(tilt)
        for (layer in listOf("under", "underwater", "surface")) drawCritters(ds, layer, at, s)
    }

    /** Splash rings on the live water plane. Cloud particles still use drawSky's shared pool. */
    internal fun drawLiveSplashes(ds: DrawScope, at: (Float, Float) -> Offset, s: Float) = with(ds) {
        for (r in rings) {
            if (r.age < r.delay) continue
            val t = ((r.age - r.delay) / 1.2f).coerceIn(0f, 1f)
            val radius = .06f + t * .6f
            val path = Path()
            for (i in 0..32) {
                val a = i * 2f * PI.toFloat() / 32f
                val p = at(r.x + radius * cos(a), r.y + radius * .62f * sin(a))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            ds.drawPath(path, Color.White.copy(alpha = .9f * (1 - t)), style = Stroke(.03f * s))
        }
    }

    // Cloud sprites: each shape's ten-odd gradient puffs painted once into a bitmap instead of every frame.
    // Keyed by shape and a zoom bucket (the unit rounded up to a power of 1.5), so a zoom-in repaints sharper.
    private val cloudSprites = HashMap<Int, Pair<ImageBitmap, Rect>>()

    private fun cloudSprite(shape: Int, u: Float): Pair<ImageBitmap, Rect> {
        val bucket = kotlin.math.ceil(kotlin.math.ln(u.coerceAtLeast(8f) / 8f) / kotlin.math.ln(1.5f)).toInt()
        return cloudSprites.getOrPut(shape * 64 + bucket) {
            val unit = 8f * 1.5f.pow(bucket)
            val parts = UNDER + PUFFS[shape] + TOPS
            val box = Rect(parts.minOf { it.first - it.third }, parts.minOf { it.second - it.third },
                parts.maxOf { it.first + it.third }, parts.maxOf { it.second + it.third })
            val w = (box.width * unit).toInt().coerceAtLeast(1); val h = (box.height * unit).toInt().coerceAtLeast(1)
            val img = ImageBitmap(w, h)
            val o = Offset(-box.left * unit, -box.top * unit)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(img), Size(w.toFloat(), h.toFloat()),
            ) {
                for ((dx, dy, r) in UNDER) drawCircle(Color(0xFFB9CBE0), r * unit, o + Offset(dx * unit, dy * unit))
                for ((dx, dy, r) in PUFFS[shape]) drawCircle(
                    Brush.radialGradient(listOf(Color.White, Color(0xFFF4F8FC), Color(0xFFC9D8EA)), center = o + Offset((dx - 0.3f * r) * unit, (dy - 0.4f * r) * unit), radius = 1.5f * r * unit),
                    r * unit, o + Offset(dx * unit, dy * unit))
                for ((dx, dy, r) in TOPS) drawCircle(Color.White, r * unit, o + Offset(dx * unit, dy * unit))
            }
            img to box
        }
    }

    // The coast halos and surf line, one path per grow factor holding every land hex, rebuilt only
    // when the projection moves (sampled at three lattice points) instead of 76 new paths a frame.
    private val coastPaths = HashMap<Float, Path>()
    private val coastKey = FloatArray(6)

    private fun coastPath(at: (Float, Float) -> Offset, grow: Float): Path {
        val o = at(0f, 0f); val x = at(1f, 0f); val y = at(0f, 1f)
        if (o.x != coastKey[0] || o.y != coastKey[1] || x.x != coastKey[2] || x.y != coastKey[3] || y.x != coastKey[4] || y.y != coastKey[5]) {
            coastPaths.clear()
            coastKey[0] = o.x; coastKey[1] = o.y; coastKey[2] = x.x; coastKey[3] = x.y; coastKey[4] = y.x; coastKey[5] = y.y
        }
        return coastPaths.getOrPut(grow) { Path().apply { for (h in land) addPath(hexPath(at, h, grow)) } }
    }

    private fun hexPath(at: (Float, Float) -> Offset, c: Offset, grow: Float) = Path().apply {
        for (i in 0 until 6) {
            val a = (-90 + 60 * i) * PI / 180
            val q = at(c.x + grow * cos(a).toFloat(), c.y + grow * sin(a).toFloat())
            if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
        }
        close()
    }

    companion object {
        /** True when a thing at [p] with reach [r] px touches the canvas (off-screen things are skipped, not stopped). */
        internal fun DrawScope.onScreen(p: Offset, r: Float): Boolean =
            p.x > -r && p.y > -r && p.x < size.width + r && p.y < size.height + r
        /** Quadratic density: fine low-end control, default at a quarter of the expanded pool. */
        internal fun sceneryCount(value: Float, maximum: Int): Int =
            (value.coerceIn(0f, 1f).pow(2) * maximum).roundToInt()
        private val SQRT3 = sqrt(3f)
        /** Inradius of a land hex (side 1). */
        private val HEX_IN = SQRT3 / 2
        /** Radius of the framed sea, in hex sides. */
        const val SEA_R = 5.4f
        /** Width of the wooden rim. */
        const val RIM = 0.26f
        /** Pokes it takes to poof a cloud. */
        const val POKES_TO_POOF = CloudFeel.POKES_TO_POOF
        /** The open sea: an ellipse matching a landscape phone screen at fit zoom (lattice units). */
        const val OPEN_RX = 12.8f
        const val OPEN_RY = 5.75f
        /** How far inside the open-sea edge clouds stay. */
        const val CLOUD_EDGE = 0.4f
        private const val WOBBLE = 0.45f
        /** Share of a cloud's speed into a bound that it keeps, reversed (soft bounce). Other cloud tunables: [CloudFeel]. */
        const val BOUNCE = 0.4f
        /** Cruising sailboats at Boats = 100% (four at the default). */
        const val BOAT_TURN_RATE = 0.35f
        const val MAX_BOATS = 16
        /** Wake trail points kept per boat (one every 0.2 s). */
        const val TRAIL = 14
        /** Boat lanes stay within this distance of the middle, where the sea is wide. */
        const val LANE_Y = OPEN_RY * 0.6f
        /** Speed (units/s) at which a cloud left over the island floats back out to sea. */
        /** How opaque a cloud is while it drifts over the island. */
        const val OVER_LAND_ALPHA = 0.6f

        const val SMALL_WAVELETS = 660
        const val BIG_WAVELETS = 60
        /** How far a wavelet may hop from home between loops, and how far it drifts within one. */
        const val HOP = 0.09f
        const val DRIFT = 0.035f
        /** Mean direction of the open-sea swell's crests. */
        private const val SWELL = 0.5f

        private val DEEP_RIM = Color(0xFF04203A)
        private val DEEP_MID = Color(0xFF0B3F6B)
        private val DEEP_SHORE = Color(0xFF1D5E8E)
        private val SHALLOW = Color(0xFF3F8FB8)
        // Wavelet and foam paints, picked from the board's existing blues.
        private val LIGHT_BLUE = Color(0xFF86BCE2)
        private val CYAN = Color(0xFF8FD6E4)
        private val PALE = Color(0xFFE6F4FB)
        private val SHADOW = Color(0xFF082947)
        private val FOAM_CYAN = Color(0xFFB6E9F2)
        private val FOAM_WHITE = Color(0xFFF4FCFF)
        // Tonal patch layers: navy-to-cobalt blotches, and medium-blue-to-muted-cyan bands.
        private const val PATCH_A_LOW = 0xFF0A2B50.toInt()
        private const val PATCH_A_HIGH = 0xFF2563A8.toInt()
        private const val PATCH_B_LOW = 0xFF2B6699.toInt()
        private const val PATCH_B_HIGH = 0xFF4E9AB5.toInt()
        /** Patch texture size in pixels, and how many hex sides one tile spans. */
        const val PATCH_TEX = 64
        /** How many hex sides one ocean tile spans on screen. */
        const val OCEAN_TILE = 7f
        /** Ocean layers: scale, rotation (degrees), alpha, drift speed. */
        private val OCEAN_LAYERS = listOf(floatArrayOf(1f, 0f, 1f, 0.05f), floatArrayOf(1.37f, 31f, 0.4f, -0.035f), floatArrayOf(2.11f, -23f, 0.3f, 0.022f))
        private const val PATCH_TILE_A = 4.3f
        private const val PATCH_TILE_B = 3.1f

        private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

        /** Deterministic pseudo-random in -1..1 for (id, loop, channel): no state, no allocation. */
        internal fun hop(id: Int, n: Int, ch: Int): Float {
            var h = id * 374761393 + n * 668265263 + ch * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 32767.5f - 1f
        }

        /**
         * A seamless soft noise texture (value noise on an 8×8 wrapped grid, two octaves) mapped to
         * translucent patches: dark where the noise is low, bright where it's high, clear in between.
         */
        internal fun patchTexture(low: Int, high: Int, seed: Int): IntArray {
            val r = Random(seed)
            fun grid(n: Int) = FloatArray(n * n) { r.nextFloat() }
            val g1 = grid(6); val g2 = grid(12)
            fun sample(g: FloatArray, n: Int, x: Float, y: Float): Float {
                val fx = x * n; val fy = y * n
                val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
                val tx = smooth(fx - ix); val ty = smooth(fy - iy)
                fun v(i: Int, j: Int) = g[((j % n + n) % n) * n + ((i % n + n) % n)]
                val a = v(ix, iy) + (v(ix + 1, iy) - v(ix, iy)) * tx
                val b = v(ix, iy + 1) + (v(ix + 1, iy + 1) - v(ix, iy + 1)) * tx
                return a + (b - a) * ty
            }
            return IntArray(PATCH_TEX * PATCH_TEX) { i ->
                val x = (i % PATCH_TEX) / PATCH_TEX.toFloat(); val y = (i / PATCH_TEX) / PATCH_TEX.toFloat()
                val v = 0.7f * sample(g1, 6, x, y) + 0.3f * sample(g2, 12, x, y)
                val d = (v - 0.5f) * 2f                      // -1..1
                val col = if (d < 0) low else high
                val a = (smooth(abs(d) * 1.6f) * 110).toInt()  // up to ~43% opacity at the extremes
                (a shl 24) or (col and 0xFFFFFF)
            }
        }

        private val UNDER = listOf(Triple(-1.1f, 0.4f, 0.8f), Triple(1.1f, 0.4f, 0.8f), Triple(0f, 0.55f, 1.4f))
        private val PUFFS = listOf(
            listOf(Triple(-1.1f, 0.1f, 0.65f), Triple(-0.4f, -0.4f, 0.8f), Triple(0.5f, -0.6f, 0.9f), Triple(1.2f, -0.1f, 0.65f), Triple(0f, 0.2f, 0.9f)),
            listOf(Triple(-0.9f, 0f, 0.7f), Triple(0f, -0.5f, 0.95f), Triple(0.9f, -0.1f, 0.75f), Triple(0.1f, 0.25f, 0.85f)),
            listOf(Triple(-1.2f, 0.15f, 0.55f), Triple(-0.5f, -0.3f, 0.75f), Triple(0.3f, -0.55f, 0.8f), Triple(1f, -0.2f, 0.7f), Triple(1.5f, 0.2f, 0.5f), Triple(0.2f, 0.2f, 0.9f)),
        )
        private val TOPS = listOf(Triple(-0.6f, -0.7f, 0.32f), Triple(0.3f, -1.0f, 0.36f), Triple(-1.2f, -0.2f, 0.24f))
    }
}
