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
    /**
     * The camera as it is right now, read when each 3D frame is drawn. The renderer's frame callback runs before
     * Compose recomposes, so [projection] alone is a frame old there: the 3D roads and pieces trailed the overlays drawn
     * over them (harbours, highlights) when the board was panned or tilted, then caught up (Mauro, 0.3.4).
     */
    projectionNow: (() -> LiveProjection?)? = null,
    sea: Sea? = null, raiders: Boolean = false,
    scenery: Boolean = true,
    onFailure: (String) -> Unit) {
    val latestFailure by rememberUpdatedState(onFailure)
    val context = androidx.compose.ui.platform.LocalContext.current
    // The Space board is batched in one model per depth layer (liveDepthBias), and the renderer slides each layer toward
    // the camera every frame. The bias used to be baked in, so on a tilt or twist the roads and pieces sat off their
    // places until the rebuild landed (Mauro, 0.3.4: "roads lag behind the board"). Pieces posed by the camera (numbers
    // kept upright, standing pieces facing you) aren't batched: rebuilt as it turned, they lay on their backs or showed
    // their numbers upside down for most of a second after each turn. They take the per-model path, posed every frame.
    val space = remember(placements) { placements.filter { liveBatched(it.model) } }
    val ordinary = remember(placements) { placements.filterNot { liveBatched(it.model) } }
    var still by remember { mutableStateOf(emptyList<SpaceLayer>()) }
    LaunchedEffect(space) {
        try {
            still = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                space.groupBy { liveDepthBias(it.model) }.map { (bias, group) ->
                    SpaceLayer(bias, SpaceBoardBatch.build(group.map { p ->
                        val color = PLAYER_COLORS.getOrNull(p.owner)
                        fun linear(v: Float) = if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)
                        SpaceBoardBatch.Instance(p.model.removePrefix("space_"), liveModelMatrix(p), color?.let { listOf(linear(it.red), linear(it.green), linear(it.blue)) })
                    }) { path -> context.assets.open(path).use { it.readBytes() } })
                }
            }
        }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
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
            renderer.batch = still
            renderer.critters = critters.take(SpaceArt.MAX_CRITTERS)
            renderer.animate = animate
            renderer.projection = projection
            renderer.projectionNow = projectionNow
            renderer.facing = facing
            renderer.sea = sea
            renderer.raiders = raiders
            renderer.scenery = scenery
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

/** One depth layer of the Space batch: the renderer moves it [bias] toward the camera ([liveTowardCamera]) each frame. */
internal class SpaceLayer(val bias: Float, val result: SpaceBoardBatch.Result)

/** True for models posed by the camera itself: digits kept upright and standing pieces facing it, posed every frame. */
internal fun liveTurnsWithCamera(model: String): Boolean = liveStanding(model) || model.startsWith("space_number_")

/** True for the Space models drawn in the depth-layer batch: the board, roads and scenery, not the camera-posed pieces. */
internal fun liveBatched(model: String): Boolean = model.startsWith("space_") && !liveTurnsWithCamera(model)

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
    private val loop = FrameLoop({ Choreographer.getInstance().postFrameCallback(this) }, { Choreographer.getInstance().removeFrameCallback(this) })
    var placements = emptyList<LivePlacement>()
    var batch = emptyList<SpaceLayer>()
    private val batchAssets = mutableMapOf<SpaceLayer, FilamentAsset>()
    var critters = emptyList<SeaCritter>()
    var animate = true
    private var shownCritters = emptyList<SeaCritter>()
    private val critterInstances = mutableListOf<FilamentInstance>()
    private var critterTime = 0L
    var projection: LiveProjection? = null
    var projectionNow: (() -> LiveProjection?)? = null
    var facing = true
    var sea: Sea? = null
    var raiders = false
    var scenery = true
    private var shown = emptyList<LivePlacement>()
    private var active = mutableListOf<FilamentInstance>()
    private var moving = emptySet<FilamentInstance>()
    private val fades = mutableMapOf<FilamentInstance, List<MaterialInstance>>()
    private val shownAlpha = mutableMapOf<FilamentInstance, Float>()
    private var water: LiveWaterMaterial? = null
    private data class Model(val asset: FilamentAsset, val first: FilamentInstance)
    private val models = mutableMapOf<String, Model>()
    private val pools = mutableMapOf<Pair<String, Int>, MutableList<FilamentInstance>>()
    private val tints = mutableMapOf<Pair<String, Int>, MaterialInstance>()
    private val lights = mutableListOf<Int>()
    private val fill = IndirectLight.Builder().irradiance(1, floatArrayOf(0.7f, 0.76f, 0.85f)).intensity(25_000f).build(engine)
    private val sky = Skybox.Builder().color(.035f, .13f, .22f, 1f).build(engine)
    private val intervals = mutableListOf<Double>()
    private val submit = mutableListOf<Double>()
    private val prepare = mutableListOf<Double>()
    private var lastRendered = 0L
    private var windowStart = 0L
    private var skipped = 0
    private var timedProjection: LiveProjection? = null
    /** When the board last put a frame on screen (System.nanoTime), for [liveStalled]. */
    private var lastFrameAt = 0L
    /** On while the screen is started: frames run (#134: they stop for a pause and come back on resume). */
    var running: Boolean
        get() = loop.running
        set(value) { if (value) loop.start(System.nanoTime()) else { loop.stop(); resetTiming() } }

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
                if (p.model == "live_water_mesh") {
                    val waterMaterial = water ?: LiveWaterMaterial(engine, context).also { water = it }
                    rm.setMaterialInstanceAt(renderable, primitive, waterMaterial.instance)
                    // Wave displacement is bounded; avoid culling this single expansive surface by its flat box.
                    rm.setCulling(renderable, false)
                } else if (p.model.startsWith("sea_")) {
                    val fade = MaterialInstance.duplicate(material, "${p.model}-fade-${result.root}")
                    rm.setMaterialInstanceAt(renderable, primitive, fade)
                    fades[result] = fades.getOrDefault(result, emptyList()) + fade
                } else if (color != null) {
                    val tint = tints.getOrPut(key) {
                        MaterialInstance.duplicate(material, "${p.model}-player-${p.owner}").apply {
                            setParameter("baseColorFactor", Colors.RgbaType.SRGB, color.red, color.green, color.blue, 1f)
                            if (p.model == LIVE_PLATE_MODEL) {
                                setParameter("metallicFactor", BrassPlateLook.LIVE_METALLIC)
                                setParameter("roughnessFactor", BrassPlateLook.LIVE_ROUGHNESS)
                            }
                        }
                    }
                    rm.setMaterialInstanceAt(renderable, primitive, tint)
                }
            }
        }
        pool.add(result)
        return result
    }

    /** Moving poses reuse model instances and update transforms; static scenery stays in place. */
    private fun updateSea() {
        val simulation = sea
        water?.update(simulation?.liveCoastEdges ?: emptyList(), simulation)
        val poses = simulation?.takeIf { scenery }?.let { liveSeaPlacements(it, raiders) } ?: emptyList()
        val counts = mutableMapOf<Pair<String, Int>, Int>()
        val next = HashSet<FilamentInstance>()
        val transforms = engine.transformManager
        transforms.openLocalTransformTransaction()
        try {
        for (p in poses) {
            val key = p.model to p.owner
            val index = counts.getOrDefault(key, 0); counts[key] = index + 1
            val instance = instance(p, index)
            next.add(instance)
            transforms.setTransform(transforms.getInstance(instance.root), liveModelMatrix(p))
            val alpha = (p.alpha.coerceIn(0f, 1f) * 64).roundToInt() / 64f
            if (shownAlpha[instance] != alpha) for (material in fades[instance].orEmpty()) {
                val shade = p.model == "sea_shadow"
                material.setParameter("baseColorFactor", if (shade) .02f else .8f,
                    if (shade) .05f else .94f, if (shade) .08f else 1f, alpha)
            }
            shownAlpha[instance] = alpha
            if (instance !in moving) scene.addEntities(instance.entities)
        }
        } finally { transforms.commitLocalTransformTransaction() }
        for (instance in moving) if (instance !in next) scene.removeEntities(instance.entities)
        moving = next
    }

    private fun updateScene() {
        if (batch.toSet() != batchAssets.keys) {
            for ((layer, asset) in batchAssets.filterKeys { it !in batch }) { scene.removeEntities(asset.entities); loader.destroyAsset(asset); batchAssets.remove(layer) }
            for (layer in batch) if (layer !in batchAssets) {
                val buffer = ByteBuffer.allocateDirect(layer.result.glb.size).apply { put(layer.result.glb); flip() }
                val instances = arrayOfNulls<FilamentInstance>(1)
                batchAssets[layer] = requireNotNull(loader.createInstancedAsset(buffer, instances)).also { resources.loadResources(it); scene.addEntities(it.entities) }
                Log.i("WGBG-3D", "Space batch bias=${layer.bias} triangles=${layer.result.triangles} draws=${layer.result.draws}")
            }
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
        for ((layer, asset) in batchAssets) engine.transformManager.setTransform(engine.transformManager.getInstance(asset.root),
            liveTowardCamera(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), p.tilt, p.rotation, layer.bias))
        val lift = liveLift(p.tilt)
        for ((index, placement) in shown.withIndex()) {
            val m = when {
                placement.model.startsWith("digit_") -> liveModelMatrix(placement.copy(heading = p.rotation))
                placement.model.startsWith("space_number_") -> liveModelMatrix(placement.copy(heading = placement.heading + p.rotation))
                liveStanding(placement.model) -> liveStandingMatrix(placement, lift, p.rotation, facing)
                liveDepthBias(placement.model) > 0f -> liveModelMatrix(placement)
                else -> continue
            }
            // Pieces draw over the terrain in front of them (#95), and numbers over roads: nearer in depth, the same on screen.
            liveDepthBias(placement.model).let { d -> if (d > 0f) liveTowardCamera(m, p.tilt, p.rotation, d) }
            engine.transformManager.setTransform(engine.transformManager.getInstance(active[index].root), m)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!loop.frame()) return
        val p = projectionNow?.invoke() ?: projection ?: return
        if (liveStalled(System.nanoTime(), loop.since, lastFrameAt)) {
            // Nothing on screen for a while (no surface, or every frame skipped): the flat board rather than a blank one.
            running = false
            Log.e("WGBG-3D", "No frame for ${LIVE_STALL_NANOS / 1_000_000_000} s; reverting to sprites")
            fail("The 3D board drew nothing")
            return
        }
        if (swapChain == null || width == 0 || height == 0) {
            // A static Compose canvas can attach a new SurfaceView after the window's pre-draw
            // pass, then schedule no more draws. Request that pass so SurfaceView creates its
            // native window; otherwise enabling 3D after a sprite frame can stay black forever.
            surfaceView.requestLayout()
            surfaceView.invalidate()
            return
        }
        try {
            val prepareStart = System.nanoTime()
            updateScene(); updateSea(); updateCamera(p); updateCritters(frameTimeNanos, p)
            prepare.add((System.nanoTime() - prepareStart) / 1e6)
            val start = System.nanoTime()
            if (renderer.beginFrame(requireNotNull(swapChain), frameTimeNanos)) {
                renderer.render(view); renderer.endFrame()
                val now = System.nanoTime()
                lastFrameAt = now
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
        val dt = frameStep(now, critterTime, .1f).takeIf { animate } ?: 0f
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
            "submitP95Ms=${percentile(submit, .95)} prepareP95Ms=${percentile(prepare, .95)} skipped=$skipped tilt=${p.tilt} rotation=${p.rotation} " +
            "lod=${shown.firstOrNull { it.model.startsWith("tile_") }?.model?.takeLast(1)} pirate=${shown.any { it.model.startsWith("pirate_") }} " +
            "placements=${shown.size} moving=${moving.size} renderables=${scene.renderableCount}")
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intervals.isNotEmpty()) {
            val sample = org.json.JSONObject().apply {
                put("elapsedMs", (now - windowStart) / 1e6); put("intervalMs", org.json.JSONArray(intervals))
                put("submitMs", org.json.JSONArray(submit)); put("skipped", skipped)
                put("prepareMs", org.json.JSONArray(prepare))
                put("tilt", p.tilt); put("rotation", p.rotation)
                put("lod", shown.firstOrNull { it.model.startsWith("tile_") }?.model?.takeLast(1)?.toIntOrNull() ?: 0)
                put("pirate", shown.any { it.model.startsWith("pirate_") })
                put("placements", shown.size); put("renderables", scene.renderableCount)
                put("moving", moving.size)
            }
            context.cacheDir.resolve("live-board-frames.jsonl").appendText(sample.toString() + "\n")
        }
        resetTiming()
    }

    private fun resetTiming() { intervals.clear(); submit.clear(); prepare.clear(); windowStart = 0; lastRendered = 0; skipped = 0 }

    override fun close() {
        if (closed) return
        running = false
        surfaceView.holder.removeCallback(this)
        surfaceDestroyed(surfaceView.holder)
        closed = true
        loop.close()
        scene.removeEntities(lights.toIntArray())
        for (i in active) scene.removeEntities(i.entities)
        for (i in critterInstances) scene.removeEntities(i.entities)
        for (asset in batchAssets.values) { scene.removeEntities(asset.entities); loader.destroyAsset(asset) }
        for (i in moving) scene.removeEntities(i.entities)
        for (m in models.values) loader.destroyAsset(m.asset)
        for (m in tints.values) engine.destroyMaterialInstance(m)
        for (m in fades.values.flatten()) engine.destroyMaterialInstance(m)
        water?.close()
        resources.destroy(); loader.destroy(); provider.destroyMaterials(); provider.destroy()
        for (l in lights) { engine.destroyEntity(l); EntityManager.get().destroy(l) }
        engine.destroyIndirectLight(fill); engine.destroySkybox(sky)
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
