package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import wgbg.engine.Board
import kotlin.math.*

/** The 3D water's rest height: the harbours' sea ([LIVE_SEA]); live_water_mesh.glb has it built in (export_sea.py). */
internal const val LIVE_WATER_HEIGHT = LIVE_SEA
internal const val LIVE_COAST_FOOT = .17f
internal const val LIVE_COAST_TEXTURE_SIZE = 128
internal const val LIVE_COAST_TEXTURE_EXTENT = 16f

// Twenty-four water-only phase keys: eight extra keys over a 16-key loop. The
// shared Sea clock and its boats, gulls and clouds continue to run forwards.
internal const val LIVE_WATER_PHASE_KEYS = 24
internal const val LIVE_WATER_CYCLE_SECONDS = 24f
private val liveWaterPhases = FloatArray(LIVE_WATER_PHASE_KEYS) {
    6f * (1f - cos(PI.toFloat() * it / (LIVE_WATER_PHASE_KEYS - 1)))
}

/** Forward/reverse playback with zero speed at both ends and no endpoint jump. */
internal fun liveWaterPhase(time: Float): Float {
    require(time.isFinite())
    val cycle = ((time % LIVE_WATER_CYCLE_SECONDS) + LIVE_WATER_CYCLE_SECONDS) % LIVE_WATER_CYCLE_SECONDS
    val triangle = if (cycle <= 12f) cycle / 12f else (24f - cycle) / 12f
    val index = triangle * (LIVE_WATER_PHASE_KEYS - 1)
    val a = index.toInt().coerceIn(0, LIVE_WATER_PHASE_KEYS - 1)
    val b = minOf(a + 1, LIVE_WATER_PHASE_KEYS - 1)
    if (a == b) return liveWaterPhases[a]
    // Cubic interpolation, with mirrored neighbour keys giving zero endpoint
    // tangents. More phase keys refine this curve rather than quantising time.
    val p0 = liveWaterPhases[if (a == 0) 1 else a - 1]
    val p1 = liveWaterPhases[a]; val p2 = liveWaterPhases[b]
    val p3 = liveWaterPhases[if (b == LIVE_WATER_PHASE_KEYS - 1) b - 1 else b + 1]
    val u = index - a
    return (.5f * (2f*p1 + (-p0+p2)*u + (2f*p0-5f*p1+4f*p2-p3)*u*u +
        (-p0+3f*p1-3f*p2+p3)*u*u*u)).coerceIn(0f, 12f)
}

internal data class LiveCoastEdge(val edge: Int, val a: Offset, val b: Offset, val outward: Offset) {
    val center get() = (a + b) / 2f
}

/** An edge belongs to the coast exactly when it borders one land hex. */
internal fun liveCoast(board: Board): List<LiveCoastEdge> = board.topology.edgeVertices.mapIndexedNotNull { e, (v1, v2) ->
    val shared = board.topology.vertexHexes[v1].filter { it in board.topology.vertexHexes[v2] }
    if (shared.size != 1) return@mapIndexedNotNull null
    val a = liveVertex(board, v1); val b = liveVertex(board, v2)
    val delta = (a + b) / 2f - liveHex(board, shared.single())
    LiveCoastEdge(e, a, b, delta / delta.getDistance())
}

/** Exact signed distance to boundary segments: positive at sea, negative inside the island. */
internal fun liveCoastDistance(coast: List<LiveCoastEdge>, p: Offset): Float {
    require(coast.isNotEmpty() && p.x.isFinite() && p.y.isFinite())
    var distanceSquared = Float.POSITIVE_INFINITY
    var inside = false
    for (edge in coast) {
        val dx = edge.b.x - edge.a.x; val dy = edge.b.y - edge.a.y
        val qx = p.x - edge.a.x; val qy = p.y - edge.a.y
        val t = ((qx * dx + qy * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val ex = qx - dx * t; val ey = qy - dy * t
        distanceSquared = minOf(distanceSquared, ex * ex + ey * ey)
        if ((edge.a.y > p.y) != (edge.b.y > p.y) &&
            p.x < edge.a.x + (p.y - edge.a.y) * dx / dy) inside = !inside
    }
    val distance = sqrt(distanceSquared)
    return if (inside) -distance else distance
}

/** Packed 16-bit signed distance in RG, constant alpha; generated once for this board. */
internal fun liveCoastTexture(coast: List<LiveCoastEdge>): ByteArray = ByteArray(LIVE_COAST_TEXTURE_SIZE * LIVE_COAST_TEXTURE_SIZE * 4).also { pixels ->
    for (y in 0 until LIVE_COAST_TEXTURE_SIZE) for (x in 0 until LIVE_COAST_TEXTURE_SIZE) {
        val p = Offset(((x + .5f) / LIVE_COAST_TEXTURE_SIZE * 2 - 1) * LIVE_COAST_TEXTURE_EXTENT,
            ((y + .5f) / LIVE_COAST_TEXTURE_SIZE * 2 - 1) * LIVE_COAST_TEXTURE_EXTENT)
        val distance = liveCoastDistance(coast, p).coerceIn(-4f, 4f)
        val packed = ((distance + 4f) / 8f * 65535).roundToInt()
        val i = (y * LIVE_COAST_TEXTURE_SIZE + x) * 4
        pixels[i] = (packed ushr 8).toByte(); pixels[i + 1] = packed.toByte(); pixels[i + 3] = 255.toByte()
    }
}

/** Same three Gerstner components as live_water.mat, so boats and wakes ride its surface. */
internal fun liveWaterHeight(coast: List<LiveCoastEdge>, p: Offset, time: Float, strength: Float = 1f): Float {
    require(time.isFinite() && strength.isFinite() && strength in 0f..2f)
    val distance = liveCoastDistance(coast, p)
    val u = ((distance - LIVE_COAST_FOOT) / .9f).coerceIn(0f, 1f)
    val fade = .2f + .8f * u * u * (3 - 2 * u)
    val phase = liveWaterPhase(time)
    val wave = .030f * sin(2 * PI.toFloat() * (p.x * .857493f + p.y * .514496f) / 4.4f - phase * .82f) +
        .020f * sin(2 * PI.toFloat() * (p.x * -.393919f + p.y * .919145f) / 3.1f - phase * 1.04f + 1.7f) +
        .010f * sin(2 * PI.toFloat() * (p.x * .242536f + p.y * -.970143f) / 2.2f - phase * 1.23f + 3.2f)
    return LIVE_WATER_HEIGHT + fade * wave * strength
}

internal fun liveCoastPlacements(board: Board): List<LivePlacement> = liveCoast(board).map {
    LivePlacement("coast_a", it.center, heading = atan2(it.outward.x, it.outward.y))
}

/** Bow points +X in the source; convert the simulation's clockwise lattice heading to glTF yaw. */
internal fun liveSeaHeading(heading: Float): Float {
    require(heading.isFinite())
    return -heading
}

/** Snapshot only: all motion, wake history, density and bird flapping come from the shared Sea. */
internal fun liveSeaPlacements(sea: Sea, raiders: Boolean): List<LivePlacement> = buildList {
    fun surface(p: Offset) = liveWaterHeight(sea.liveCoastEdges, p, sea.time, sea.whitecapScale)
    val waterPhase = liveWaterPhase(sea.time)
    for (i in 0 until sea.boatCount) {
        val boat = sea.boats[i]
        val at = Offset(boat.x, boat.y)
        add(LivePlacement("live_sloop", at, surface(at),
            heading = liveSeaHeading(boat.heading), owner = boat.color))
        for (j in 1 until boat.trailN) {
            val a = Offset(boat.trail[2 * (j - 1)], boat.trail[2 * (j - 1) + 1])
            val b = Offset(boat.trail[2 * j], boat.trail[2 * j + 1])
            val delta = a - b
            if (delta.getDistance() < .001f) continue
            add(LivePlacement("sea_wake", (a + b) / 2f, surface((a + b) / 2f) + .006f,
                scale = .7f * (1 - j.toFloat() / Sea.TRAIL), heading = liveSeaHeading(atan2(delta.y, delta.x)),
                alpha = .35f * (1 - j.toFloat() / Sea.TRAIL)))
        }
    }
    for (i in 0 until sea.birdCount) {
        val bird = sea.birds[i]
        val frame = bird.flapFrame(16).coerceAtLeast(0)
        add(LivePlacement("live_gull_$frame", Offset(bird.x, bird.y), .65f, bird.scale,
            heading = liveSeaHeading(bird.heading)))
        val shadow = Offset(bird.x + .3f, bird.y + .45f)
        add(LivePlacement("sea_shadow", shadow, surface(shadow) + .004f,
            bird.scale, liveSeaHeading(bird.heading), alpha = .23f))
    }
    for (i in 0 until sea.waveCount) {
        val w = sea.wavelets[i]
        val alpha = sea.waveletAlpha(w, waterPhase) * sea.whitecapAlpha
        add(LivePlacement("sea_wavelet", Offset(sea.waveletX(w, waterPhase), sea.waveletY(w, waterPhase)),
            surface(Offset(sea.waveletX(w, waterPhase), sea.waveletY(w, waterPhase))) + .008f,
            w.len * sea.whitecapScale, liveSeaHeading(w.angle), alpha = alpha))
    }
    for (i in 0 until sea.amount(sea.waves01, sea.breaks.size)) {
        val b = sea.breaks[i]
        val u = (waterPhase / b.period + b.phase).let { it - floor(it) }
        if (u > .62f) continue
        val distance = .32f - .16f * (u / .38f).coerceAtMost(1f)
        add(LivePlacement("sea_wavelet", Offset(b.x + b.nx * distance, b.y + b.ny * distance),
            surface(Offset(b.x + b.nx * distance, b.y + b.ny * distance)) + .009f,
            .4f, liveSeaHeading(atan2(b.ny, b.nx) + PI.toFloat()/2),
            alpha = sin(PI.toFloat() * u / .62f) * .8f))
    }
    if (raiders) {
        val pose = raidersPose(sea.raidersTrack, sea.landR, sea.time)
        val at = Offset(pose.x, pose.y)
        add(LivePlacement("live_raiders", at, surface(at), heading = liveSeaHeading(pose.heading)))
        add(LivePlacement("sea_wake", Offset(pose.x - cos(pose.heading)*.6f, pose.y - sin(pose.heading)*.6f),
            LIVE_WATER_HEIGHT + .006f, 1.5f, liveSeaHeading(pose.heading), alpha = .4f))
    }
}
