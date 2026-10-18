package com.mauro.wgbg

import android.content.Context
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** Where settings are kept: SharedPreferences on device, a map in tests. */
interface FloatStore {
    fun get(key: String, default: Float): Float
    fun put(key: String, value: Float)
}

class PrefsStore(context: Context) : FloatStore {
    private val prefs = context.getSharedPreferences("wgbg-settings", Context.MODE_PRIVATE)
    override fun get(key: String, default: Float) = prefs.getFloat(key, default)
    override fun put(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
}

/**
 * Scenery amounts, each 0..1 and saved across restarts: how many clouds, birds and harbour
 * boats show, and how busy the waves are. Pure decoration; the game never reads them.
 */
class EnvSettings(private val store: FloatStore) {
    enum class Item(val label: String, val key: String) {
        CLOUDS("Clouds", "env-clouds"), BIRDS("Birds", "env-birds"), BOATS("Boats", "env-boats"), WAVES("Waves", "env-waves")
    }

    private val values = Item.entries.associateWith { mutableFloatStateOf(store.get(it.key, DEFAULT).coerceIn(0f, 1f)) }

    operator fun get(item: Item): Float = values.getValue(item).floatValue

    /** Clamped to 0..1 and saved on every change. */
    operator fun set(item: Item, v: Float) {
        val c = v.coerceIn(0f, 1f)
        values.getValue(item).floatValue = c
        store.put(item.key, c)
    }

    private val densityState = mutableFloatStateOf(store.get(DENSITY, DEFAULT).coerceIn(0f, 1f))

    /** One multiplier on all water objects (0 none, 0.5 as the four sliders say, 1 double). Saved. */
    var density: Float
        get() = densityState.floatValue
        set(v) { densityState.floatValue = v.coerceIn(0f, 1f); store.put(DENSITY, densityState.floatValue) }

    private val viewBarsState = mutableFloatStateOf(store.get(VIEW_BARS, 0f))

    /**
     * On-screen Zoom, Tilt and (3D board) Turn bars, off by default on every device: pinch, two-finger drag and twist do
     * the same (#137, Mauro: gestures by default, the sliders only when switched on under the gear). Saved.
     */
    var viewBars: Boolean
        get() = viewBarsState.floatValue > 0.5f
        set(v) { viewBarsState.floatValue = if (v) 1f else 0f; store.put(VIEW_BARS, viewBarsState.floatValue) }

    private val bugButtonState = mutableFloatStateOf(store.get(BUG_BUTTON, 0f))

    /** A Report button on the game screen, beside Menu, that opens the report with a screenshot (#128, Mauro). Saved. */
    var bugButton: Boolean
        get() = bugButtonState.floatValue > 0.5f
        set(v) { bugButtonState.floatValue = if (v) 1f else 0f; store.put(BUG_BUTTON, bugButtonState.floatValue) }

    private val seeThroughState = mutableFloatStateOf(store.get(SEE_THROUGH, 0f))

    /** The left side panel (players and turn news): solid wood, or see-through so the board shows behind it (#73). Saved. */
    var seeThrough: Boolean
        get() = seeThroughState.floatValue > 0.5f
        set(v) { seeThroughState.floatValue = if (v) 1f else 0f; store.put(SEE_THROUGH, seeThroughState.floatValue) }

    private val animationsState = mutableFloatStateOf(store.get(ANIMATIONS, 1f))

    /**
     * Scenery animations (sea, clouds, birds, boats, the rolling dice). Off, or Density at 0, and none of that code
     * runs at all, so a bug in it can't stop a game (Mauro, 2026-10-11). Saved.
     */
    var animations: Boolean
        get() = animationsState.floatValue > 0.5f
        set(v) { animationsState.floatValue = if (v) 1f else 0f; store.put(ANIMATIONS, animationsState.floatValue) }

    /** True when the scenery animates: the switch is on and Density is above 0. */
    val animating: Boolean get() = animations && density > 0f

    private val tiles3dState = mutableFloatStateOf(store.get(TILES_3D, 0f))

    private val liveBoardState = mutableFloatStateOf(store.get(LIVE_BOARD, 0f))
    /** Opt-in Filament spike. The existing sprite board remains the default. */
    var liveBoard: Boolean
        get() = liveBoardState.floatValue > 0.5f
        set(v) { liveBoardState.floatValue = if (v) 1f else 0f; store.put(LIVE_BOARD, liveBoardState.floatValue) }

    private val faceState = mutableFloatStateOf(store.get(FACE_PIECES, 1f))
    /** 3D board: buildings, knights and the Bandit turn to face you when you turn the board (Mauro, 2026-10-12). */
    var facePieces: Boolean
        get() = faceState.floatValue > 0.5f
        set(v) { faceState.floatValue = if (v) 1f else 0f; store.put(FACE_PIECES, faceState.floatValue) }

    private val aiPaceState = mutableFloatStateOf(store.get(AI_PACE, AI_PACE_DEFAULT))
    /**
     * Seconds between the AI players' turns, [AI_PACE_MIN]..[AI_PACE_MAX]; each move inside an AI's turn waits half of
     * it (Mauro, 2026-10-13: "1 sec default up to 5 sec on a slider", replacing Relaxed/Normal/Quick).
     */
    var aiPace: Float
        get() = aiPaceState.floatValue.takeIf { it.isFinite() }?.coerceIn(AI_PACE_MIN, AI_PACE_MAX) ?: AI_PACE_DEFAULT
        set(v) { aiPaceState.floatValue = v.coerceIn(AI_PACE_MIN, AI_PACE_MAX); store.put(AI_PACE, aiPaceState.floatValue) }

    private val liveLodState = mutableFloatStateOf(store.get(LIVE_LOD, 0f))
    var liveLod: Int
        get() = liveLodState.floatValue.toInt().coerceIn(0, 1)
        set(v) { liveLodState.floatValue = v.coerceIn(0, 1).toFloat(); store.put(LIVE_LOD, liveLodState.floatValue) }

    /** Board tiles: the painted faces (default) or the rendered 3D tiles (V3, a preview to try). Saved. */
    var tiles3d: Boolean
        get() = tiles3dState.floatValue > 0.5f
        set(v) { tiles3dState.floatValue = if (v) 1f else 0f; store.put(TILES_3D, tiles3dState.floatValue) }

    private val pirate = mutableFloatStateOf(store.get(THEME, 0f))

    /** Knight art: Normal (armoured knights) or Pirate themed (afro skeleton crew). Saved. */
    var pirateTheme: Boolean
        get() = pirate.floatValue > 0.5f
        set(v) { pirate.floatValue = if (v) 1f else 0f; store.put(THEME, pirate.floatValue) }

    enum class TileEdge(val label: String) { NONE("None"), THIN("Thin line"), CLASSIC("Classic frame") }
    private val edge = mutableFloatStateOf(store.get(EDGE, TileEdge.THIN.ordinal.toFloat()))

    /** How tile borders look: none, a thin dark line, or the original painted frame. Saved. */
    var tileEdge: TileEdge
        get() = TileEdge.entries.getOrElse(edge.floatValue.toInt()) { TileEdge.THIN }
        set(v) { edge.floatValue = v.ordinal.toFloat(); store.put(EDGE, edge.floatValue) }

    private val eventDie = mutableFloatStateOf(store.get(EVENT_DIE, 0f))

    /** Pirate event die art, an index into DiceArt.EVENT_STYLES (Mauro: all four as options). Saved. */
    var eventDieStyle: Int
        get() = eventDie.floatValue.toInt().coerceIn(0, DiceArt.EVENT_STYLES.size - 1)
        set(v) { eventDie.floatValue = v.coerceIn(0, DiceArt.EVENT_STYLES.size - 1).toFloat(); store.put(EVENT_DIE, eventDie.floatValue) }

    /** Back to the defaults (saved). */
    fun reset() { for (i in Item.entries) this[i] = DEFAULT; density = DEFAULT; animations = true; pirateTheme = false; tileEdge = TileEdge.THIN; eventDieStyle = 0; liveBoard = false; liveLod = 0; facePieces = true; aiPace = AI_PACE_DEFAULT; viewBars = false }

    val clouds get() = this[Item.CLOUDS]
    val birds get() = this[Item.BIRDS]
    val boats get() = this[Item.BOATS]
    val waves get() = this[Item.WAVES]

    companion object {
        const val DEFAULT = 0.5f
        const val THEME = "theme-pirate"
        const val EDGE = "tile-edge"
        const val EVENT_DIE = "event-die-style"
        const val DENSITY = "env-density"
        const val VIEW_BARS = "view-bars"
        const val TILES_3D = "tiles-3d"
        const val LIVE_BOARD = "live-board"
        const val LIVE_LOD = "live-board-lod"
        const val FACE_PIECES = "live-face-pieces"
        const val ANIMATIONS = "animations"
        const val AI_PACE = "ai-pace"
        const val AI_PACE_DEFAULT = 1f
        const val AI_PACE_MIN = 1f
        const val AI_PACE_MAX = 5f
        const val SEE_THROUGH = "side-panel-see-through"
        const val BUG_BUTTON = "bug-button"
    }
}

/** The dice throw's timing; [fallMs]'s factor stretches it (the AI pace slider replaced the old Pace choices). */
object Pace {
    /** The dice throw in milliseconds at [factor]. */
    fun fallMs(factor: Float = 1f): Int = (DiceSprites.FALL_S * 1000 * factor).toInt()
}

/** The 3D board (test) On/Off switch and its detail level; in the game's Menu, and in Settings on Look & art. */
@Composable
fun LiveBoardChoice(env: EnvSettings) {
    Text("3D board (test)", fontSize = 13.sp, color = Color(0xFFD9B865))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("On", selected = env.liveBoard, tag = "live-board-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.liveBoard = true }
        Choice("Off", selected = !env.liveBoard, tag = "live-board-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.liveBoard = false }
    }
    if (env.liveBoard) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Detail: LOD0", selected = env.liveLod == 0, tag = "live-lod-0", modifier = Modifier.height(36.dp).width(140.dp), size = 13.sp) { env.liveLod = 0 }
        Choice("Detail: LOD1", selected = env.liveLod == 1, tag = "live-lod-1", modifier = Modifier.height(36.dp).width(140.dp), size = 13.sp) { env.liveLod = 1 }
    }
    if (env.liveBoard) {
        Text("Buildings face me when I turn the board", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("On", selected = env.facePieces, tag = "face-pieces-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.facePieces = true }
            Choice("Off", selected = !env.facePieces, tag = "face-pieces-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.facePieces = false }
        }
    }
}

/**
 * The gear menu: every setting in one wood-and-brass panel. Changes save as they happen;
 * Reset puts scenery and sound back to their defaults.
 */
@Composable
fun SettingsPanel(env: EnvSettings, sound: SoundSettings, modifier: Modifier = Modifier,
                  gameControls: (@Composable () -> Unit)? = null, liveBoardChoice: Boolean = true) {
    // No Done button: every change saves as it is made, and the Menu button (showing Hide) closes the panel (Mauro).
    // Scrolls when the screen is too short for every setting (landscape phones).
    Column(modifier.width(360.dp).brassEdgedWood().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp).testTag("settings-panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 18.sp, color = Color(0xFFF1D58A), modifier = Modifier.weight(1f))
            ResetButton { env.reset(); sound.reset() }
        }
        // In a game: Pause, speed and Watch bots first (Mauro, 2026-10-12: "should be under the gear menu").
        if (gameControls != null) {
            Text("This game", fontSize = 13.sp, color = Color(0xFFD9B865))
            gameControls()
        }
        AccountSection()
        // In a game the 3D switch sits in the main Menu instead (Mauro: "3d board should move out to that main menu").
        if (liveBoardChoice) LiveBoardChoice(env)
        Text("AI pace: seconds between the AI players' turns (their moves take half)", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%.1f s".format(env.aiPace), fontSize = 12.sp, color = Color(0xFFEDE6D8), modifier = Modifier.width(100.dp))
            Slider(env.aiPace, { env.aiPace = Math.round(it * 2) / 2f }, Modifier.width(220.dp).height(32.dp).testTag("ai-pace"),
                valueRange = EnvSettings.AI_PACE_MIN..EnvSettings.AI_PACE_MAX, steps = 7, colors = brassSlider())
        }
        Text("Animations (off, or Density at 0 in the game Menu: nothing moves)", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("On", selected = env.animations, tag = "animations-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.animations = true }
            Choice("Off", selected = !env.animations, tag = "animations-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.animations = false }
        }
        Text("Scenery", fontSize = 13.sp, color = Color(0xFFD9B865))
        // The four kinds; Density, which scales them all together, is in the game's Menu (Mauro, 2026-10-18: "in the game
        // menu not front page").
        for (item in EnvSettings.Item.entries)
            SliderRow(item.label, env[item], "env-${item.name.lowercase()}") { env[item] = it }
        Text("Theme", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Normal", selected = !env.pirateTheme, tag = "theme-normal", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.pirateTheme = false }
            Choice("Pirate themed", selected = env.pirateTheme, tag = "theme-pirate", modifier = Modifier.height(36.dp).width(140.dp), size = 13.sp) { env.pirateTheme = true }
        }
        Text("Event die (Pirate theme)", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pair in DiceArt.EVENT_STYLES.indices.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in pair) Choice(DiceArt.EVENT_STYLES[i], selected = env.eventDieStyle == i, tag = "event-die-$i",
                        modifier = Modifier.height(36.dp).width(122.dp), size = 12.sp) { env.eventDieStyle = i; env.pirateTheme = true }
                }
            }
            // A preview of the chosen style's ship face.
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(DiceArt.eventFace(true, 0, env.eventDieStyle)), null, Modifier.size(56.dp))
        }
        Text("Zoom, Tilt and Turn sliders on the game screen (pinch, drag and twist work without them)", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Show", selected = env.viewBars, tag = "view-bars-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.viewBars = true }
            Choice("Hide", selected = !env.viewBars, tag = "view-bars-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.viewBars = false }
        }
        Text("Report button on the game screen (opens a bug report with a screenshot)", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Show", selected = env.bugButton, tag = "bug-button-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.bugButton = true }
            Choice("Hide", selected = !env.bugButton, tag = "bug-button-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.bugButton = false }
        }
        Text("Side panel", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Solid", selected = !env.seeThrough, tag = "side-solid", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.seeThrough = false }
            Choice("See-through", selected = env.seeThrough, tag = "side-see-through", modifier = Modifier.height(36.dp).width(130.dp), size = 13.sp) { env.seeThrough = true }
        }
        Text("Tiles", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Painted", selected = !env.tiles3d, tag = "tiles-painted", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.tiles3d = false }
            Choice("3D (preview)", selected = env.tiles3d, tag = "tiles-3d", modifier = Modifier.height(36.dp).width(130.dp), size = 13.sp) { env.tiles3d = true }
        }
        Text("Tile edges", fontSize = 13.sp, color = Color(0xFFD9B865))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in EnvSettings.TileEdge.entries)
                Choice(e.label, selected = env.tileEdge == e, tag = "edge-${e.name.lowercase()}", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.tileEdge = e }
        }
        Text("Sound", fontSize = 13.sp, color = Color(0xFFD9B865))
        VolumeRow("Music", sound.musicVolume, "vol-music") { sound.musicVolume = it }
        VolumeRow("Sounds", sound.soundVolume, "vol-sounds") { sound.soundVolume = it }
        Text("Songs", fontSize = 13.sp, color = Color(0xFFD9B865))
        SoundSettings.SONGS.forEachIndexed { i, song ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongTick(sound, i)
                Text("♪ ${song.title}", fontSize = 12.sp, color = if (sound.songOn(i)) Color(0xFFCDBFA6) else Color(0xFF8A7A62))
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, tag: String, sliderWidth: androidx.compose.ui.unit.Dp = 220.dp, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${(value * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFFEDE6D8), modifier = Modifier.width(100.dp))
        Slider(value, onChange, Modifier.width(sliderWidth).height(32.dp).testTag(tag), colors = brassSlider())
    }
}

/** The brass gear button (Mauro's art): darker while pressed, and it turns a little each tap. */
@Composable
fun GearButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val turn by animateFloatAsState(if (open) 60f else 0f, label = "gear-turn")
    Image(
        painterResource(if (pressed) R.drawable.settings_gear_pressed else R.drawable.settings_gear), "Settings",
        modifier.rotate(turn).clickable(interactionSource = source, indication = null, onClick = onClick).testTag("gear"),
    )
}

/** A volume slider whose handle is a little brass phonograph that grows with the volume (Mauro, #58: no knobs). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(value: Float, tag: String, width: androidx.compose.ui.unit.Dp, onChange: (Float) -> Unit) {
    val colors = brassSlider()
    Slider(value, onChange, Modifier.width(width).height(44.dp).testTag(tag), colors = colors,
        thumb = { Phonograph(value, Modifier.size(44.dp)) })
}

@Composable
private fun VolumeRow(label: String, value: Float, tag: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${(value * 100).roundToInt()}%", fontSize = 12.sp, color = Color(0xFFEDE6D8), modifier = Modifier.width(100.dp))
        VolumeSlider(value, tag, 220.dp, onChange)
    }
}

/** A worn-brass gramophone horn on a little walnut box, drawn at 45% to 100% of its box as [volume] goes 0 to 1. */
@Composable
private fun Phonograph(volume: Float, modifier: Modifier) = androidx.compose.foundation.Canvas(modifier) {
    val k = 0.45f + 0.55f * volume.coerceIn(0f, 1f)
    val w = size.width * k; val h = size.height * k
    val left = (size.width - w) / 2; val top = (size.height - h) / 2
    fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(left + x * w, top + y * h)
    // Walnut box with a brass edge.
    drawRect(Color(0xFF4A2A14), p(0.18f, 0.66f), androidx.compose.ui.geometry.Size(0.64f * w, 0.28f * h))
    drawRect(Color(0xFFA9863F), p(0.18f, 0.66f), androidx.compose.ui.geometry.Size(0.64f * w, 0.28f * h),
        style = androidx.compose.ui.graphics.drawscope.Stroke(0.04f * w))
    // The horn: a brass tube from the box rising to a wide bell up and to the right, darker inside.
    val horn = androidx.compose.ui.graphics.Path().apply {
        moveTo(p(0.42f, 0.66f).x, p(0.42f, 0.66f).y)
        cubicTo(p(0.40f, 0.48f).x, p(0.40f, 0.48f).y, p(0.52f, 0.36f).x, p(0.52f, 0.36f).y, p(0.62f, 0.10f).x, p(0.62f, 0.10f).y)
        lineTo(p(0.98f, 0.30f).x, p(0.98f, 0.30f).y)
        cubicTo(p(0.74f, 0.40f).x, p(0.74f, 0.40f).y, p(0.56f, 0.50f).x, p(0.56f, 0.50f).y, p(0.54f, 0.66f).x, p(0.54f, 0.66f).y)
        close()
    }
    drawPath(horn, Color(0xFFB8913F))
    drawPath(horn, Color(0xFF6E5420), style = androidx.compose.ui.graphics.drawscope.Stroke(0.03f * w))
    drawLine(Color(0xFF3B2A10), p(0.63f, 0.12f), p(0.96f, 0.30f), strokeWidth = 0.08f * w)   // the bell's dark mouth
    drawLine(Color(0x99FFF2CD), p(0.47f, 0.60f), p(0.58f, 0.24f), strokeWidth = 0.025f * w)  // a worn highlight
}

/** Sliders in the motif: brass thumb and track over dark wood. */
@Composable
private fun brassSlider() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = Color(0xFFD9B865),
    activeTrackColor = Color(0xFFC9A04A),
    inactiveTrackColor = Color(0xFF3A2414),
)

/** Music and game-sound volumes (0..1), saved across restarts; the soundtrack (#17) will play through these. */
class SoundSettings(private val store: FloatStore) {
    private val music = mutableFloatStateOf(store.get(MUSIC, DEFAULT_MUSIC).coerceIn(0f, 1f))
    private val sounds = mutableFloatStateOf(store.get(SOUNDS, DEFAULT_SOUNDS).coerceIn(0f, 1f))
    var musicVolume: Float
        get() = music.floatValue
        set(v) { music.floatValue = v.coerceIn(0f, 1f); store.put(MUSIC, music.floatValue) }
    var soundVolume: Float
        get() = sounds.floatValue
        set(v) { sounds.floatValue = v.coerceIn(0f, 1f); store.put(SOUNDS, sounds.floatValue) }

    private val held = mutableFloatStateOf(store.get(MUSIC_PAUSED, 0f))
    /** The Pause button (saved, so paused music stays paused next time). */
    var musicPaused: Boolean
        get() = held.floatValue > 0.5f
        set(v) { held.floatValue = if (v) 1f else 0f; store.put(MUSIC_PAUSED, held.floatValue) }
    /** The index in [SONGS] of the song playing now, or -1 (set by the game's music player). */
    var nowPlaying by mutableIntStateOf(-1)
    /** Wired to the music player by the game screen: play song i, skip to the next. */
    var onPlay: (Int) -> Unit = {}
    var onNext: () -> Unit = {}

    // Songs left out of the set list (#131), saved by title so a reordered or longer list keeps the choices.
    private val songOn = SONGS.map { mutableFloatStateOf(store.get(songKey(it), 1f)) }
    /** Whether song [i] plays when the set list moves on (a tap on its name still plays it). */
    fun songOn(i: Int): Boolean = songOn.getOrNull(i)?.floatValue?.let { it > 0.5f } ?: false
    fun setSongOn(i: Int, on: Boolean) { val v = if (on) 1f else 0f; songOn[i].floatValue = v; store.put(songKey(SONGS[i]), v) }

    /** Back to the defaults (saved); every song ticked again. */
    fun reset() { musicVolume = DEFAULT_MUSIC; soundVolume = DEFAULT_SOUNDS; SONGS.indices.forEach { setSongOn(it, true) } }

    companion object {
        const val DEFAULT_MUSIC = 0.6f
        const val DEFAULT_SOUNDS = 0.8f
        const val MUSIC = "vol-music"
        const val SOUNDS = "vol-sounds"
        const val MUSIC_PAUSED = "music-paused"
        fun songKey(song: Song) = "song-on:" + song.title
        /** The set list (#17, #58), our own recordings; more shanties to come. */
        val SONGS: List<Song> = listOf(
            Song("Drunken Sailor", R.raw.tune_drunken_sailor),
            Song("Carnival of Venice", R.raw.tune_carnival_of_venice),
            Song("Blow the Man Down", R.raw.tune_blow_the_man_down),
            Song("Haul Away Joe", R.raw.tune_haul_away_joe),
            Song("Leave Her Johnny", R.raw.tune_leave_her_johnny),
            Song("Leave Her Johnny (lament)", R.raw.tune_leave_her_johnny_lament),
            Song("Spanish Ladies", R.raw.tune_spanish_ladies),
            Song("South Australia", R.raw.tune_south_australia),
            Song("Rio Grande", R.raw.tune_rio_grande),
            Song("Shenandoah", R.raw.tune_shenandoah),
            Song("Wellerman", R.raw.tune_wellerman),
        )
    }
}

/** Scenery density in the game's Menu: how much moves and lives on the board, all kinds together (0 = nothing). */
@Composable
fun DensityControl(env: EnvSettings) {
    Text("Scenery density (boats, birds, sea life)", fontSize = 13.sp, color = Color(0xFFD9B865))
    SliderRow("Density", env.density, "env-density") { env.density = it }
}

/** Sound in the menu: a header with a ^ that opens Music and Game sounds sliders and the song list. */
@Composable
fun SoundControls(sound: SoundSettings) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.clickable { open = !open }.testTag("sound-toggle").height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sound and songs", fontSize = 14.sp)
            Text(if (open) "^" else "v", fontSize = 18.sp, color = Color(0xFFD9B865), modifier = Modifier.width(24.dp))
        }
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Music ${(sound.musicVolume * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.width(96.dp))
                VolumeSlider(sound.musicVolume, "vol-music", 200.dp) { sound.musicVolume = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sounds ${(sound.soundVolume * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.width(96.dp))
                VolumeSlider(sound.soundVolume, "vol-sounds", 200.dp) { sound.soundVolume = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(if (sound.musicPaused) "▶ Play" else "⏸ Pause", selected = sound.musicPaused, tag = "music-pause",
                    modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { sound.musicPaused = !sound.musicPaused }
                Choice("⏭ Next", selected = false, tag = "music-next", modifier = Modifier.height(36.dp).width(100.dp), size = 13.sp) {
                    sound.musicPaused = false; sound.onNext()
                }
            }
            Text("Songs (tick to keep in the list, tap a name to play it)", fontSize = 12.sp)
            SoundSettings.SONGS.forEachIndexed { i, song ->
                val now = i == sound.nowPlaying && !sound.musicPaused
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SongTick(sound, i)
                    Text((if (now) "▶ " else "♪ ") + song.title, fontSize = 12.sp,
                        color = if (now) Color(0xFFD9B865) else if (sound.songOn(i)) Color(0xFFCDBFA6) else Color(0xFF8A7A62),
                        modifier = Modifier.clickable { sound.musicPaused = false; sound.onPlay(i) }.testTag("song-$i").padding(vertical = 3.dp))
                }
            }
        }
    }
}

/** A song's tick box (#131): brass rim, a check when the song stays in the set list. */
@Composable
private fun SongTick(sound: SoundSettings, i: Int) {
    val on = sound.songOn(i)
    Box(Modifier.size(40.dp).clickable { sound.setSongOn(i, !on) }.testTag("song-on-$i")
        .semantics { contentDescription = SoundSettings.SONGS[i].title + if (on) ", in the list" else ", left out" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).background(if (on) Color(0xFF3A2414) else Color.Transparent, RoundedCornerShape(4.dp))
            .border(2.dp, Color(0xFFC9A04A), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (on) Text("✓", fontSize = 14.sp, color = Color(0xFFF1D58A), fontWeight = FontWeight.Bold)
        }
    }
}

/** Reset to defaults: the brass circular-arrow icon (Mauro's art), darker while pressed. */
@Composable
private fun ResetButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Image(
        painterResource(if (pressed) R.drawable.reset_arrow_pressed else R.drawable.reset_arrow), "Reset to defaults",
        Modifier.size(44.dp).clickable(interactionSource = source, indication = null, onClick = onClick).testTag("settings-reset"),
    )
}
