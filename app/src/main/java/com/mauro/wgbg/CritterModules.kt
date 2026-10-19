package com.mauro.wgbg

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Critter modules (docs/design/critter-spec.md, #130): a critter is a folder, and adding one needs no game code.
 *
 *   assets/critters/<id>/critter.json                   the spec (id = the folder's name)
 *   assets/critters/<id>/<model>.glb                    optional, for the 3D board
 *   assets/critters/<id>/frames/<pose>_h<H>_t<T>.webp   flat-board frames: H heading (0 = east, counter-clockwise),
 *                                                       T tilt step (0 = straight down)
 *
 * A pose is a frame name: `d50` holds still, `d50_beat00`..`d50_beat07` animate the clip `beat` at pose `d50`.
 */

/** A frame used in place of a clip that has no render of its own: [frames] spread over the clip, faded in or out. */
internal class CritterStandIn(val frames: List<String>, val fade: String?)

/** The flat-board frame layout ("frames" in critter.json); [span] is how many hex units a frame's side covers. */
internal class CritterFrameSpec(val headings: Int, val tilts: Int, val size: Int, val span: Float, val standIns: Map<String, CritterStandIn>)

/** One frame to draw: its pose name and how opaque. */
internal class CritterFrame(val pose: String, val alpha: Float)

/** A loaded critter module. [open] reads a file in its folder (null in tests, where nothing is drawn). */
internal class CritterModule(
    val id: String, val spec: CritterSpec, val frames: CritterFrameSpec,
    /** Frame files by pose: (heading, tilt) pairs present, and the extension they use. */
    val files: Map<String, Set<Pair<Int, Int>>>, val ext: String,
    private val open: ((String) -> java.io.InputStream)? = null,
) {
    /** Animated runs: "d50_beat" → 8 when d50_beat00..07 are all there. */
    private val runs: Map<String, Int> = files.keys.mapNotNull { Regex("^(.+?)(\\d\\d)$").find(it)?.groupValues?.get(1) }.toSet()
        .associateWith { base -> generateSequence(0) { it + 1 }.takeWhile { "%s%02d".format(base, it) in files }.count() }
        .filterValues { it > 0 }

    /** The frame for a pose of the engine, or null when it is hidden, see-through or has no frame. */
    fun frameFor(p: CritterPose): CritterFrame? {
        val anim = p.anim ?: return null
        if (p.opacity <= 0.0) return null
        val clip = anim.substringBefore(':')
        val pose = anim.substringAfter(':', "")
        val share = spec.clips[clip]?.let { (p.animTime / it.seconds).coerceIn(0.0, 0.999) } ?: 0.0
        val base = if (pose.isEmpty()) clip else "${pose}_$clip"
        val n = runs[base] ?: 0
        val (name, alpha) = when {
            n > 0 -> "%s%02d".format(base, floor(share * n).toInt()) to 1f
            (pose.ifEmpty { clip }) in files -> pose.ifEmpty { clip } to 1f
            else -> {
                val si = frames.standIns[clip] ?: return null
                si.frames[floor(share * si.frames.size).toInt()] to when (si.fade) {
                    "in" -> (share / 0.4).toFloat().coerceIn(0f, 1f)
                    "out" -> ((1 - share) / 0.4).toFloat().coerceIn(0f, 1f)
                    else -> 1f
                }
            }
        }
        return CritterFrame(name, alpha * p.opacity.toFloat())
    }

    /** The rendered heading for a [course] on the sea (radians, lattice y down) seen with [squash]. */
    fun headingIndex(course: Float, squash: Float): Int {
        val screen = atan2(-sin(course) * squash, cos(course))
        val i = (screen / (2 * PI.toFloat() / frames.headings)).roundToInt()
        return ((i % frames.headings) + frames.headings) % frames.headings
    }

    /** The module's tilt frame for the board's tilt step (0..7, [SeaSprites3d.tiltStep]). */
    fun tiltIndex(step8: Int): Int = (step8 * (frames.tilts - 1) / 7f).roundToInt().coerceIn(0, frames.tilts - 1)

    /** What is wrong with this module; empty when it is good to ship. */
    fun problems(dir: String): List<String> = buildList {
        if (spec.id != dir) add("id '${spec.id}' is not the folder's name '$dir'")
        if (spec.layer != "auto" && spec.layer !in CritterModules.LAYERS) add("layer '${spec.layer}' is not auto or one of ${CritterModules.LAYERS}")
        // Avoiding the board needs a height clear of it wherever its zone takes it over land.
        if (spec.boardContact == "avoid" && spec.anchor == null && spec.zone in listOf("over-land", "coast", "anywhere") &&
            spec.altitude[1] < CritterSpec.LAND_TOP && spec.altitude[0] > -CritterSpec.BOARD_DEPTH)
            add("zone '${spec.zone}' goes over land, but altitude ${spec.altitude.toList()} never clears the board: widen it or use pass-through")
        if (!(frames.span > 0f)) add("frames.span must be > 0")
        val all = (0 until frames.headings).flatMap { h -> (0 until frames.tilts).map { h to it } }.toSet()
        for ((pose, have) in files) if (have != all) add("$pose: ${all.size - (all intersect have).size} of ${all.size} frames missing")
        for (si in frames.standIns.values) for (f in si.frames) if (f !in files) add("stand-in frame '$f' missing")
        for (s in spec.stages) {
            val clip = s.anim ?: continue
            for (t in listOf(0.0, 0.5, 0.99)) {
                val f = frameFor(CritterPose(0.0, 0.0, 0.0, 0.0, s.id, clip, 1.0, t * (spec.clips[clip.substringBefore(':')]?.seconds ?: 1.0)))
                if (f == null || f.pose !in files) { add("${s.id}: '$clip' has no frames (render it, or give a stand_in)"); break }
            }
        }
    }

    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_FRAMES
    }

    /** The bitmap for [frame] at [heading], [tilt]; null without assets (tests) or if the file won't decode. */
    fun image(frame: CritterFrame, heading: Int, tilt: Int): ImageBitmap? {
        val o = open ?: return null
        val name = "frames/${frame.pose}_h${heading}_t$tilt.$ext"
        cache[name]?.let { return it }
        return runCatching { o(name).use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()?.also { cache[name] = it }
    }

    companion object {
        /** Frames kept decoded per module: a 288 px frame is ~330 KB, so ~16 MB at most. */
        const val MAX_FRAMES = 48
    }
}

/** Draws [frame] centred on [p] (its contact point), [side] px square. */
internal fun DrawScope.drawCritterFrame(m: CritterModule, p: Offset, side: Float, frame: CritterFrame, heading: Int, tilt: Int) {
    if (side < 2f || !side.isFinite() || frame.alpha <= 0.01f) return
    val img = m.image(frame, heading, tilt) ?: return
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((p.x - side / 2).toInt(), (p.y - side / 2).toInt()),
        IntSize(side.toInt(), side.toInt()), alpha = frame.alpha, filterQuality = FilterQuality.Medium)
}

internal object CritterModules {
    /** The bands, bottom to top (critter spec, Layers); the board draws each band in this order. */
    val LAYERS = listOf("under", "underwater", "surface", "air", "sky")

    private val FRAME = Regex("^(.+)_h(\\d+)_t(\\d+)\\.(webp|png)$")

    /** Every module under `critters/` in the app's assets. A broken module is logged and left out, never a crash. */
    fun load(assets: AssetManager): List<CritterModule> =
        load({ assets.list(it).orEmpty().toList() }, { assets.open(it) })

    fun loadSpace(assets: AssetManager): List<CritterModule> = SpaceArt.critters.map { path ->
        module(path.substringAfterLast('/'), assets.list("$path/frames").orEmpty().toList(),
            assets.open("$path/critter.json").bufferedReader().use { it.readText() }) { assets.open("$path/$it") }
    }

    /** [list] names a folder's entries and [open] reads a file, both by path from the assets root. */
    fun load(list: (String) -> List<String>, open: (String) -> java.io.InputStream, keepBroken: Boolean = false): List<CritterModule> =
        list("critters").sorted().filter { "critter.json" in list("critters/$it") }.mapNotNull { dir ->
            runCatching {
                val m = module(dir, list("critters/$dir/frames"), open("critters/$dir/critter.json").bufferedReader().use { it.readText() }) { open("critters/$dir/$it") }
                val bad = m.problems(dir)
                if (bad.isNotEmpty()) critterLog("$dir: " + bad.joinToString("; "))
                m.takeIf { bad.isEmpty() || keepBroken }
            }.onFailure { critterLog("$dir: ${it.message}") }.getOrNull()
        }

    /** Builds one module from its critter.json text and the names in its frames folder. */
    fun module(dir: String, frameNames: List<String>, json: String, open: ((String) -> java.io.InputStream)? = null): CritterModule {
        val c = MiniJson.parse(json) as Map<*, *>
        val f = c["frames"] as Map<*, *>
        val standIns = (f["stand_in"] as Map<*, *>?).orEmpty().map { (k, v) -> v as Map<*, *>
            k as String to CritterStandIn((v["frames"] as List<*>).map { it as String }, v["fade"] as String?) }.toMap()
        val frames = CritterFrameSpec((f["headings"] as Number).toInt(), (f["tilts"] as Number).toInt(), (f["size"] as Number).toInt(),
            (f["span"] as Number? ?: error("frames.span missing")).toFloat(), standIns)
        val matched = frameNames.mapNotNull { FRAME.find(it)?.groupValues }
        val files = matched.groupBy({ it[1] }, { it[2].toInt() to it[3].toInt() }).mapValues { it.value.toSet() }
        return CritterModule(dir, CritterSpec.compile(c), frames, files, matched.firstOrNull()?.get(4) ?: "webp", open)
    }
}
