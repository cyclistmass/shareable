package com.mauro.wgbg

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.*

@Composable
internal fun LiveBoardView(placements: List<LivePlacement>, projection: LiveProjection?, modifier: Modifier,
    /** Standing pieces turn to face you when the board turns (Settings, default on). */
    facing: Boolean = true,
    critters: List<SeaCritter> = emptyList(),
    animate: Boolean = true,
    onFailure: (String) -> Unit) {
    val latestFailure by rememberUpdatedState(onFailure)
    val context = androidx.compose.ui.platform.LocalContext.current
    val space = remember(placements) { placements.filter { it.model.startsWith("space_") } }
    val ordinary = remember(placements) { placements.filterNot { it.model.startsWith("space_") } }
    var batch by remember { mutableStateOf<SpaceBoardBatch.Result?>(null) }
    LaunchedEffect(space, projection?.rotation, projection?.tilt, facing) {
        try {
        batch = if (space.isEmpty()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            SpaceBoardBatch.build(space.map { p ->
                val color = PLAYER_COLORS.getOrNull(p.owner)
                fun linear(v: Float) = if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)
                val rotation = projection?.rotation ?: 0f
                // Standing pieces lie back toward the camera like the per-model path (updateCamera): upright under the
                // near top-down camera, a Space knight showed only its thin top edge (Space playtest).
                val matrix = when {
                    liveStanding(p.model) -> liveStandingMatrix(p, liveLift(projection?.tilt ?: 0f), rotation, facing)
                    p.model.startsWith("space_number_") -> liveModelMatrix(p.copy(heading = p.heading + rotation))
                    else -> liveModelMatrix(p)
                }.let { if (livePiece(p.model)) liveTowardCamera(it, projection?.tilt ?: 0f, rotation) else it }
                SpaceBoardBatch.Instance(p.model.removePrefix("space_"), matrix, color?.let { listOf(linear(it.red), linear(it.green), linear(it.blue)) })
            }) { path -> context.assets.open(path).use { it.readBytes() } }
        }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { latestFailure(error.message ?: "Space batch failed") }
    }
    val holder = remember { arrayOfNulls<LiveBoardRenderer>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(modifier = modifier, factory = { context ->
        SurfaceView(context).also { surface ->
            try { holder[0] = LiveBoardRenderer(surface, context) { latestFailure(it) } }
            catch (e: Exception) { Log.e("WGBG-3D", "Initialisation failed", e); latestFailure(e.message ?: "Initialisation failed") }
        }
    }, update = {
        holder[0]?.let { renderer ->
            renderer.placements = ordinary
            renderer.batch = batch
            renderer.critters = critters.take(SpaceArt.MAX_CRITTERS)
            renderer.animate = animate
            renderer.projection = projection
            renderer.facing = facing
            renderer.running = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            holder[0]?.running = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); holder[0]?.close(); holder[0] = null }
    }
}

/** All engine access is on the main thread, as required by Filament's Android API. */
internal class LiveBoardRenderer(private val surfaceView: SurfaceView, private val context: Context, private val fail: (String) -> Unit) :
    Choreographer.FrameCallback, SurfaceHolder.Callback, AutoCloseable {
    companion object {
        init { Utils.init(); Gltfio.init() }
    }
    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity)
    private val view = engine.createView()
    private val provider = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, provider, EntityManager.get())
    private val resources = ResourceLoader(engine, true)
    private val display = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private var width = 0
    private var height = 0
    private var closed = false
    private var scheduled = false
    var placements = emptyList<LivePlacement>()
    var batch: SpaceBoardBatch.Result? = null
    private var shownBatch: SpaceBoardBatch.Result? = null
    private var batchAsset: FilamentAsset? = null
    var critters = emptyList<SeaCritter>()
    var animate = true
    private var shownCritters = emptyList<SeaCritter>()
    private val critterInstances = mutableListOf<FilamentInstance>()
    private var critterTime = 0L
    var projection: LiveProjection? = null
    var facing = true
    private var shown = emptyList<LivePlacement>()
    private var active = mutableListOf<FilamentInstance>()
    private data class Model(val asset: FilamentAsset, val first: FilamentInstance)
    private val models = mutableMapOf<String, Model>()
    private val pools = mutableMapOf<Pair<String, Int>, MutableList<FilamentInstance>>()
    private val tints = mutableMapOf<Pair<String, Int>, MaterialInstance>()
    private val lights = mutableListOf<Int>()
    private val fill = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.76f, 0.85f)).intensity(25_000f).build(engine)
    private val sky = Skybox.Builder().color(0.055f, 0.12f, 0.17f, 1f).build(engine)
    private val intervals = mutableListOf<Double>()
    private val submit = mutableListOf<Double>()
    private var lastRendered = 0L
    private var windowStart = 0L
    private var skipped = 0
    private var timedProjection: LiveProjection? = null
    var running = false
        set(value) {
            field = value
            if (value && !closed && !scheduled) { scheduled = true; Choreographer.getInstance().postFrameCallback(this) }
            if (!value) { Choreographer.getInstance().removeFrameCallback(this); scheduled = false; resetTiming() }
        }

    init {
        view.scene = scene; view.camera = camera
        view.isPostProcessingEnabled = true
        view.antiAliasing = View.AntiAliasing.FXAA
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply { enabled = false }
        camera.setExposure(16f, 1f / 125f, 100f)
        scene.indirectLight = fill; scene.skybox = sky
        // Direction is where the rays travel: source (-x, +y, -z), upper left on the board.
        addLight(0.5f, -0.8f, 0.6f, 80_000f, 1f, 0.91f, 0.78f)
        addLight(-0.5f, -0.4f, -0.4f, 12_000f, 0.75f, 0.85f, 1f)
        surfaceView.holder.addCallback(this)
        // Compose can reuse an already-sized native surface after the sprite fallback. Adding a
        // callback alone doesn't replay surfaceChanged; adopt its current frame explicitly.
        if (surfaceView.holder.surface.isValid) {
            surfaceCreated(surfaceView.holder)
            val frame = surfaceView.holder.surfaceFrame
            surfaceChanged(surfaceView.holder, 0, frame.width(), frame.height())
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(holder.surface)
        display.attach(renderer, surfaceView.display)
        Log.i("WGBG-3D", "surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (closed) return
        width = w; height = h
        view.viewport = Viewport(0, 0, w, h)
        engine.flushAndWait()
        Log.i("WGBG-3D", "surface resized ${w}x$h")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (closed) return
        display.detach()
        swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
        swapChain = null
    }

    private fun addLight(x: Float, y: Float, z: Float, intensity: Float, r: Float, g: Float, b: Float) {
        val entity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).direction(x, y, z).color(r, g, b)
            .intensity(intensity).castShadows(false).build(engine, entity)
        lights.add(entity); scene.addEntity(entity)
    }

    private fun model(name: String): Model = models.getOrPut(name) {
        val bytes = if (name.startsWith("digit_")) liveNumberGlb(context, name.removePrefix("digit_").toInt()) else {
            val data = context.assets.open(if (name.startsWith("critters/")) name else "filament/${liveModelFile(name)}.glb").use { it.readBytes() }
            ByteBuffer.allocateDirect(data.size).apply { put(data); flip() }
        }
        val instances = arrayOfNulls<FilamentInstance>(1)
        val asset = requireNotNull(loader.createInstancedAsset(bytes, instances)) { "Cannot load $name" }
        resources.loadResources(asset)
        // Keep source data for later createInstance calls; destruction frees it once the view closes.
        Model(asset, requireNotNull(instances[0]))
    }

    private fun instance(p: LivePlacement, index: Int): FilamentInstance {
        val key = p.model to p.owner
        val pool = pools.getOrPut(key) { mutableListOf() }
        if (index < pool.size) return pool[index]
        val m = model(p.model)
        val alreadyUsed = pools.any { (k, v) -> k.first == p.model && v.isNotEmpty() }
        val result = if (!alreadyUsed) m.first else requireNotNull(loader.createInstance(m.asset))
        val rm = engine.renderableManager
        for (entity in result.entities) {
            val renderable = rm.getInstance(entity)
            if (renderable == 0) continue
            rm.setCastShadows(renderable, false); rm.setReceiveShadows(renderable, false)
            for (primitive in 0 until rm.getPrimitiveCount(renderable)) {
                val material = rm.getMaterialInstanceAt(renderable, primitive)
                val color = liveTint(p.model, material.name, p.owner)
                if (color != null) {
                    val tint = tints.getOrPut(key) {
                        MaterialInstance.duplicate(material, "${p.model}-player-${p.owner}").apply {
                            setParameter("baseColorFactor", Colors.RgbaType.SRGB, color.red, color.green, color.blue, 1f)
                        }
                    }
                    rm.setMaterialInstanceAt(renderable, primitive, tint)
                }
            }
        }
        pool.add(result)
        return result
    }

    private fun updateScene() {
        if (batch !== shownBatch) {
            batchAsset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
            batchAsset = batch?.let { result ->
                val buffer = ByteBuffer.allocateDirect(result.glb.size).apply { put(result.glb); flip() }
                val instances = arrayOfNulls<FilamentInstance>(1)
                requireNotNull(loader.createInstancedAsset(buffer, instances)).also { resources.loadResources(it); scene.addEntities(it.entities) }
            }
            shownBatch = batch
            batch?.let { Log.i("WGBG-3D", "Space batch triangles=${it.triangles} draws=${it.draws}") }
        }
        if (critters != shownCritters) {
            for (i in critterInstances) scene.removeEntities(i.entities)
            critterInstances.clear()
            for (c in critters) {
                val path = "critters/space/${c.spec.id}/${c.spec.id}.glb"
                val i = instance(LivePlacement(path, Offset.Zero), 0)
                critterInstances.add(i)
            }
            shownCritters = critters
        }
        if (placements == shown) return
        for (i in active) scene.removeEntities(i.entities)
        active.clear()
        val counts = mutableMapOf<Pair<String, Int>, Int>()
        for (p in placements) {
            val key = p.model to p.owner
            val index = counts.getOrDefault(key, 0); counts[key] = index + 1
            val i = instance(p, index)
            engine.transformManager.setTransform(engine.transformManager.getInstance(i.root), liveModelMatrix(p))
            scene.addEntities(i.entities); active.add(i)
        }
        shown = placements
        resetTiming()
        Log.i("WGBG-3D", "scene models=${models.size} placements=${shown.size} renderables=${scene.renderableCount}")
    }

    private fun updateCamera(p: LiveProjection) {
        if (timedProjection != p) { resetTiming(); timedProjection = p }
        val st = sin(p.tilt).toDouble(); val ct = cos(p.tilt).toDouble()
        val sr = sin(p.rotation).toDouble(); val cr = cos(p.rotation).toDouble()
        camera.lookAt(20.0 * st * sr, 20.0 * ct, 20.0 * st * cr, 0.0, 0.0, 0.0, -ct * sr, st, -ct * cr)
        camera.setProjection(com.google.android.filament.Camera.Projection.ORTHO,
            -p.center.x.toDouble() / p.scale, (width - p.center.x).toDouble() / p.scale,
            -(height - p.center.y).toDouble() / p.scale, p.center.y.toDouble() / p.scale, 0.1, 100.0)
        // Keep digits upright under twist, while retaining their horizontal plane and real depth; standing pieces
        // tip toward the camera so they read as clearly as the flat board's.
        val lift = liveLift(p.tilt)
        for ((index, placement) in shown.withIndex()) {
            val m = when {
                placement.model.startsWith("digit_") -> liveModelMatrix(placement.copy(heading = p.rotation))
                liveStanding(placement.model) -> liveStandingMatrix(placement, lift, p.rotation, facing)
                livePiece(placement.model) -> liveModelMatrix(placement)
                else -> continue
            }
            // Pieces draw over the terrain in front of them (#95): nearer in depth, the same on screen.
            if (livePiece(placement.model)) liveTowardCamera(m, p.tilt, p.rotation)
            engine.transformManager.setTransform(engine.transformManager.getInstance(active[index].root), m)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!running || closed) return
        scheduled = true; Choreographer.getInstance().postFrameCallback(this)
        val p = projection ?: return
        if (swapChain == null || width == 0 || height == 0) {
            // A static Compose canvas can attach a new SurfaceView after the window's pre-draw
            // pass, then schedule no more draws. Request that pass so SurfaceView creates its
            // native window; otherwise enabling 3D after a sprite frame can stay black forever.
            surfaceView.requestLayout()
            surfaceView.invalidate()
            return
        }
        try {
            updateScene(); updateCamera(p); updateCritters(frameTimeNanos, p)
            val start = System.nanoTime()
            if (renderer.beginFrame(requireNotNull(swapChain), frameTimeNanos)) {
                renderer.render(view); renderer.endFrame()
                val now = System.nanoTime()
                if (lastRendered != 0L) intervals.add((now - lastRendered) / 1e6)
                lastRendered = now
                submit.add((now - start) / 1e6)
                if (windowStart == 0L) windowStart = now
                if (now - windowStart >= 5_000_000_000L) reportTiming(now, p)
            } else skipped++
        } catch (e: Exception) {
            running = false
            Log.e("WGBG-3D", "Rendering failed; reverting to sprites", e)
            fail(e.message ?: "Rendering failed")
        }
    }

    private fun updateCritters(now: Long, p: LiveProjection) {
        val dt = if (critterTime == 0L || !animate) 0f else ((now - critterTime) / 1e9f).coerceAtMost(.1f)
        critterTime = now
        val corners = listOf(Offset.Zero, Offset(width.toFloat(), 0f), Offset(0f, height.toFloat()), Offset(width.toFloat(), height.toFloat())).map(p::unproject)
        val bounds = androidx.compose.ui.geometry.Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
        for ((index, c) in shownCritters.withIndex()) {
            c.update(dt, bounds)
            val pose = c.pose() ?: continue
            val i = critterInstances[index]
            if (pose.opacity <= 0.0) { scene.removeEntities(i.entities); continue }
            scene.addEntities(i.entities)
            val place = LivePlacement("", c.at, pose.z.toFloat(), heading = -c.course)
            engine.transformManager.setTransform(engine.transformManager.getInstance(i.root), liveModelMatrix(place))
            val animator = i.animator
            val clip = pose.anim?.substringBefore(':')
            val animation = (0 until animator.animationCount).firstOrNull { animator.getAnimationName(it) == clip }
            if (animation != null) { animator.applyAnimation(animation, pose.animTime.toFloat()); animator.updateBoneMatrices() }
        }
    }

    private fun reportTiming(now: Long, p: LiveProjection) {
        fun percentile(values: List<Double>, q: Double): Double = values.sorted()[(q * (values.size - 1)).roundToInt()]
        if (intervals.isNotEmpty()) Log.i("WGBG-3D-PERF", "frames=${intervals.size} elapsedMs=${(now - windowStart) / 1e6} " +
            "fps=${intervals.size * 1e9 / (now - windowStart)} p50Ms=${percentile(intervals, .50)} p95Ms=${percentile(intervals, .95)} " +
            "submitP95Ms=${percentile(submit, .95)} skipped=$skipped tilt=${p.tilt} rotation=${p.rotation} " +
            "lod=${shown.firstOrNull { it.model.startsWith("tile_") }?.model?.takeLast(1)} pirate=${shown.any { it.model.startsWith("pirate_") }} " +
            "placements=${shown.size} renderables=${scene.renderableCount}")
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intervals.isNotEmpty()) {
            val sample = org.json.JSONObject().apply {
                put("elapsedMs", (now - windowStart) / 1e6); put("intervalMs", org.json.JSONArray(intervals))
                put("submitMs", org.json.JSONArray(submit)); put("skipped", skipped)
                put("tilt", p.tilt); put("rotation", p.rotation)
                put("lod", shown.firstOrNull { it.model.startsWith("tile_") }?.model?.takeLast(1)?.toIntOrNull() ?: 0)
                put("pirate", shown.any { it.model.startsWith("pirate_") })
                put("placements", shown.size); put("renderables", scene.renderableCount)
            }
            context.cacheDir.resolve("live-board-frames.jsonl").appendText(sample.toString() + "\n")
        }
        resetTiming()
    }

    private fun resetTiming() { intervals.clear(); submit.clear(); windowStart = 0; lastRendered = 0; skipped = 0 }

    override fun close() {
        if (closed) return
        running = false
        surfaceView.holder.removeCallback(this)
        surfaceDestroyed(surfaceView.holder)
        closed = true
        scene.removeEntities(lights.toIntArray())
        for (i in active) scene.removeEntities(i.entities)
        for (i in critterInstances) scene.removeEntities(i.entities)
        batchAsset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        for (m in models.values) loader.destroyAsset(m.asset)
        for (m in tints.values) engine.destroyMaterialInstance(m)
        resources.destroy(); loader.destroy(); provider.destroyMaterials(); provider.destroy()
        for (l in lights) { engine.destroyEntity(l); EntityManager.get().destroy(l) }
        engine.destroyIndirectLight(fill); engine.destroySkybox(sky)
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
