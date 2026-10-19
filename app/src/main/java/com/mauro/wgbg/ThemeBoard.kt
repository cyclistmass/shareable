package com.mauro.wgbg

import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import wgbg.engine.GameState
import kotlin.math.*

/** Cached, authored views; shadow and neutral trim never inherit the owner's tint. */
internal class SpaceSprites(private val resources: Resources, val choice: ArtChoice) {
    private val images = object : LinkedHashMap<Int, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > 160
    }
    private fun image(id: Int) = images.getOrPut(id) { ImageBitmap.imageResource(resources, id) }
    fun draw(scope: DrawScope, sprite: SpaceArt.Sprite, at: Offset, unit: Float, tilt: Float, owner: Color? = null) = with(scope) {
        val step = SpaceArt.tiltStep(Math.toDegrees(tilt.toDouble()).toFloat())
        val side = sprite.frameUnits * unit
        if (!side.isFinite() || side < 2) return@with
        val dest = IntOffset((at.x - side / 2).toInt(), (at.y - side / 2).toInt())
        val dims = IntSize(side.toInt(), side.toInt())
        fun layer(ids: List<Int>, filter: ColorFilter? = null) {
            if (ids.isEmpty()) return
            val img = image(ids[step])
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), dest, dims, colorFilter = filter, filterQuality = FilterQuality.Medium)
        }
        layer(sprite.shadow)
        if (owner != null && sprite.tintable) { layer(sprite.body, pieceTint(owner)); layer(sprite.trim) }
        else layer(sprite.full)
    }
    /** The starfield repeated over the whole canvas (one 12-unit frame left the edges bare beside the side panel). */
    fun fillSea(scope: DrawScope, unit: Float): Unit = with(scope) {
        val img = image(SpaceArt.sea.full[0])
        val side = (SpaceArt.sea.frameUnits * unit).coerceAtLeast(64f)
        val k = side / img.width
        val shader = ImageShader(img, TileMode.Repeated, TileMode.Repeated)
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawRect(ShaderBrush(shader), size = androidx.compose.ui.geometry.Size(size.width / k, size.height / k))
        }
    }
}

internal fun spaceModel(sprite: SpaceArt.Sprite) = sprite.model.removePrefix("filament/").removeSuffix(".glb")

internal fun themedInventoryPlacements(choice: ArtChoice): List<LivePlacement> {
    if (ArtObject.entries.none { choice.space(it) }) return liveInventoryPlacements()
    return buildList {
        fun selected(obj: ArtObject, space: SpaceArt.Sprite, normal: String) =
            if (choice.space(obj)) spaceModel(space) else normal
        val prefix = if (choice.pirate(ArtObject.BUILDINGS)) "pirate" else "default"
        add(LivePlacement(selected(ArtObject.BOARD, SpaceArt.sea, "sea"), Offset.Zero, LIVE_SEA, 2f))
        for (r in 0..5) {
            val at = Offset(-6.5f + r * 2.6f, -3.5f)
            val terrain = listOf("timber", "clay", "fleece", "grain", "stone", "desert")[r]
            add(LivePlacement(selected(ArtObject.BOARD, SpaceArt.tile(if (r == 5) -1 else r), "tile_${terrain}_lod0"), at))
            if (r < 5) {
                val number = listOf(5, 6, 8, 9, 10)[r]
                if (choice.space(ArtObject.BOARD)) add(LivePlacement(spaceModel(SpaceArt.numberPlate(number)), at + Offset(.36f, .30f), SpaceArt.TILE_DECK_HEIGHT + .012f))
                else { add(LivePlacement("number_plate", at + LIVE_PLATE, .012f)); add(LivePlacement("digit_$number", at + LIVE_PLATE, .039f)) }
            }
            if (choice.space(ArtObject.BOARD)) add(LivePlacement(spaceModel(SpaceArt.port(if (r == 5) -1 else r)), Offset(at.x, -.9f)))
        }
        val pieces = listOf("homestead" to SpaceArt.homestead, "town" to SpaceArt.town,
            "ramparts" to SpaceArt.ramparts(1), "ramparts" to SpaceArt.ramparts(2), "ramparts" to SpaceArt.ramparts(3),
            "capital_guilds" to SpaceArt.capital(0), "capital_crown" to SpaceArt.capital(1), "capital_academy" to SpaceArt.capital(2), "road_v" to SpaceArt.road(0))
        for ((i, item) in pieces.withIndex()) add(LivePlacement(selected(ArtObject.BUILDINGS, item.second, "${prefix}_${item.first}"), Offset(-7.2f + i * 1.8f, 1.3f), scale = 1.8f, owner = i % PLAYER_COLORS.size))
        for (lv in 1..3) for (active in listOf(false, true)) {
            val normal = "${if (choice.pirate(ArtObject.KNIGHTS)) "pirate" else "default"}_knight_l$lv" + if (active) "" else "_inactive"
            add(LivePlacement(selected(ArtObject.KNIGHTS, SpaceArt.knight(lv, active), normal), Offset(-6.5f + ((lv - 1) * 2 + if (active) 1 else 0) * 1.7f, 3.8f), owner = lv - 1))
        }
        add(LivePlacement(selected(ArtObject.MERCHANT, SpaceArt.merchant, LIVE_MERCHANT), Offset(4.5f, 3.8f), scale = BUILDING_SCALE, owner = 0))
        add(LivePlacement(selected(ArtObject.BANDIT, SpaceArt.bandit, "bandit"), Offset(6.5f, 3.8f)))
        if (choice.space(ArtObject.BOARD)) {
            add(LivePlacement(spaceModel(SpaceArt.raidersShip), Offset(-3f, 5.5f)))
            add(LivePlacement(spaceModel(SpaceArt.boat), Offset(0f, 5.5f), owner = 1))
            add(LivePlacement(spaceModel(SpaceArt.cliff), Offset(3f, 5.5f)))
        }
    }
}

/** Leave the Default/Pirate placement list untouched unless an object explicitly selects Space. */
internal fun themedLivePlacements(s: GameState, choice: ArtChoice, lod: Int, hasModel: (String) -> Boolean): List<LivePlacement> {
    val original = livePlacements(s, choice.pirate(ArtObject.BUILDINGS), lod, choice.pirate(ArtObject.KNIGHTS)) {
        hasModel(it) || (choice.space(ArtObject.KNIGHTS) && "_knight_" in it) || choice.space(ArtObject.MERCHANT) && it == LIVE_MERCHANT
    }
    if (ArtObject.entries.none { choice.space(it) }) return original
    val board = choice.space(ArtObject.BOARD)
    val deck = if (board) SpaceArt.TILE_DECK_HEIGHT else 0f
    return buildList {
        for (p in original) {
            val m = p.model
            if (board && (m == "sea" || m.startsWith("tile_") || m == "number_plate" || m.startsWith("digit_"))) continue
            if (choice.space(ArtObject.KNIGHTS) && m in listOf(KNIGHT_RING, KNIGHT_BAND)) continue
            var sprite: SpaceArt.Sprite? = null
            var position = p.position
            var height = p.height + deck
            if (m == "bandit") {
                if (choice.space(ArtObject.BANDIT)) sprite = SpaceArt.bandit
                if (board) position += Offset(SpaceArt.PLATE_X, SpaceArt.PLATE_Y) - LIVE_PLATE
            }
            if (m == LIVE_MERCHANT && choice.space(ArtObject.MERCHANT)) sprite = SpaceArt.merchant
            if ("_knight_" in m && choice.space(ArtObject.KNIGHTS)) {
                val level = m.substringAfter("_knight_l").take(1).toInt()
                sprite = SpaceArt.knight(level, !m.endsWith("_inactive")); height = deck
            }
            if (choice.space(ArtObject.BUILDINGS)) {
                when {
                    m.endsWith("_homestead") -> sprite = SpaceArt.homestead
                    m.endsWith("_town") -> sprite = SpaceArt.town
                    m.endsWith("_road_v") -> sprite = SpaceArt.road(0)
                    m.endsWith("_ramparts") -> sprite = SpaceArt.ramparts(1 + BuildingSprites.rampartsHeight((0..2).maxOf { s.ck!!.level(p.owner, it) }))
                    "_capital_" in m -> {
                        val v = s.ck!!.metroVertex.indexOfFirst { it >= 0 && liveVertex(s.board, it) == p.position }
                        sprite = SpaceArt.capital(v)
                    }
                }
            }
            add(p.copy(model = sprite?.let(::spaceModel) ?: m, position = position, height = height))
        }
        if (board) {
            add(LivePlacement(spaceModel(SpaceArt.sea), Offset.Zero, LIVE_SEA))
            for (h in 0 until s.board.hexCount) {
                val at = liveHex(s.board, h)
                add(LivePlacement(spaceModel(SpaceArt.tile(s.board.hexResource[h], lod)), at))
                if (s.numberAt(h) > 0 && h != s.robberHex) add(LivePlacement(spaceModel(SpaceArt.numberPlate(s.numberAt(h))), at + Offset(SpaceArt.PLATE_X, SpaceArt.PLATE_Y), deck + .012f))
            }
            for (port in s.board.ports) {
                val n = coastOutward(s.board, port.v1, port.v2)
                val mid = (liveVertex(s.board, port.v1) + liveVertex(s.board, port.v2)) / 2f
                add(LivePlacement(spaceModel(SpaceArt.port(port.type)), mid + n * PORT_BUOY_OUT, deck, heading = atan2(n.x, n.y)))
            }
            for (e in 0 until s.board.edgeCount) {
                val a = liveVertex(s.board, s.board.edgeA[e]); val b = liveVertex(s.board, s.board.edgeB[e])
                val common = s.board.topology.vertexHexes[s.board.edgeA[e]].count { it in s.board.topology.vertexHexes[s.board.edgeB[e]] }
                val dir = b - a
                add(LivePlacement(spaceModel(if (common == 1) SpaceArt.cliff else SpaceArt.lane), (a + b) / 2f, heading = atan2(dir.x, dir.y)))
            }
            if (s.ck != null) {
                val radius = (0 until s.board.vertexCount).maxOf { liveVertex(s.board, it).getDistance() }
                val pose = raidersPose(s.ck!!.barbarianPos.toFloat(), radius, 0f)
                add(LivePlacement(spaceModel(SpaceArt.raidersShip), Offset(pose.x, pose.y), LIVE_SEA, heading = -pose.heading))
            }
        }
    }
}
