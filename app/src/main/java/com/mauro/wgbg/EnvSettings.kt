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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
/** A saved list index, or -1 when the saved value is broken (roundToInt throws on NaN, which crashed the art getter). */
internal fun Float.savedIndex(): Int = if (isFinite()) roundToInt() else -1

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

    /**
     * True when the scenery animates (sea, clouds, birds, boats, the rolling dice): Density above 0. At 0 none of that
     * code runs at all, so a bug in it can't stop a game (Mauro, 2026-10-11).
     */
    val animating: Boolean get() = density > 0f

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

    private val autoNoState = mutableFloatStateOf(store.get(AUTO_NO, AUTO_NO_DEFAULT.toFloat()))
    /**
     * Seconds before an offer you can't pay is declined for you (#26, Mauro's report 2026-10-22), one of [AUTO_NO_CHOICES];
     * 0 is Off: it waits for your tap.
     */
    var autoNoSeconds: Int
        get() = autoNoState.floatValue.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in AUTO_NO_CHOICES } ?: AUTO_NO_DEFAULT
        set(v) { val c = v.takeIf { it in AUTO_NO_CHOICES } ?: AUTO_NO_DEFAULT; autoNoState.floatValue = c.toFloat(); store.put(AUTO_NO, autoNoState.floatValue) }

    private val liveLodState = mutableFloatStateOf(store.get(LIVE_LOD, 0f))
    var liveLod: Int
        get() = liveLodState.floatValue.toInt().coerceIn(0, 1)
        set(v) { liveLodState.floatValue = v.coerceIn(0, 1).toFloat(); store.put(LIVE_LOD, liveLodState.floatValue) }

    /** Board tiles: the painted faces (default) or the rendered 3D tiles (V3, a preview to try). Saved. */
    var tiles3d: Boolean
        get() = tiles3dState.floatValue > 0.5f
        set(v) { tiles3dState.floatValue = if (v) 1f else 0f; store.put(TILES_3D, tiles3dState.floatValue) }

    // The theme keeps the old Normal/Pirate key (0 Default, 1 Pirate) so saved choices carry over; -1 = follows the theme.
    private val themeState = mutableFloatStateOf(store.get(THEME, 0f))
    private val swapStates = ArtObject.entries.associateWith { mutableFloatStateOf(store.get(artKey(it), -1f)) }

    /** The chosen theme and any pieces swapped to another set on the Inventory screen. Saved. */
    var art: ArtChoice
        get() = ArtChoice(ArtSet.entries.getOrElse(themeState.floatValue.savedIndex()) { ArtSet.DEFAULT },
            swapStates.mapNotNull { (o, v) -> ArtSet.entries.getOrNull(v.floatValue.savedIndex())?.let { o to it } }.toMap()).tidy()
        set(v) {
            val tidy = v.tidy()
            themeState.floatValue = tidy.theme.ordinal.toFloat(); store.put(THEME, themeState.floatValue)
            for ((o, state) in swapStates) { state.floatValue = tidy.swaps[o]?.ordinal?.toFloat() ?: -1f; store.put(artKey(o), state.floatValue) }
        }

    /** Whether the whole theme is Pirate (the debug launch flag and the 3D view log). */
    var pirateTheme: Boolean
        get() = art.theme == ArtSet.PIRATE
        set(v) { art = art.withTheme(if (v) ArtSet.PIRATE else ArtSet.DEFAULT) }

    enum class TileEdge(val label: String) { NONE("None"), THIN("Thin line"), CLASSIC("Classic frame") }
    private val edge = mutableFloatStateOf(store.get(EDGE, TileEdge.THIN.ordinal.toFloat()))

    /** How tile borders look: none, a thin dark line, or the original painted frame. Saved. */
    var tileEdge: TileEdge
        get() = TileEdge.entries.getOrElse(edge.floatValue.toInt()) { TileEdge.THIN }
        set(v) { edge.floatValue = v.ordinal.toFloat(); store.put(EDGE, edge.floatValue) }

    private val palette = mutableFloatStateOf(store.get(PALETTE, 0f))

    /** Board terrain colours (#86): each palette grades the terrain art its own way. Saved; not used in Space. */
    var terrainPalette: TerrainPalette
        get() = TerrainPalette.entries.getOrElse(palette.floatValue.toInt()) { TerrainPalette.TODAY }
        set(v) { palette.floatValue = v.ordinal.toFloat(); store.put(PALETTE, palette.floatValue) }

    private val eventDie = mutableFloatStateOf(store.get(EVENT_DIE, 0f))

    /** Pirate event die art, an index into DiceArt.EVENT_STYLES (Mauro: all four as options). Saved. */
    var eventDieStyle: Int
        get() = eventDie.floatValue.toInt().coerceIn(0, DiceArt.EVENT_STYLES.size - 1)
        set(v) { eventDie.floatValue = v.coerceIn(0, DiceArt.EVENT_STYLES.size - 1).toFloat(); store.put(EVENT_DIE, eventDie.floatValue) }

    /** Every setting back to its default (saved): scenery, board, look and play. */
    fun reset() {
        for (i in Item.entries) this[i] = DEFAULT; density = DEFAULT; pirateTheme = false; tileEdge = TileEdge.THIN; terrainPalette = TerrainPalette.TODAY; eventDieStyle = 0
        liveBoard = false; liveLod = 0; facePieces = true; aiPace = AI_PACE_DEFAULT; autoNoSeconds = AUTO_NO_DEFAULT; viewBars = false
        art = ArtChoice(ArtSet.DEFAULT); tiles3d = false; seeThrough = false; bugButton = false
    }

    val clouds get() = this[Item.CLOUDS]
    val birds get() = this[Item.BIRDS]
    val boats get() = this[Item.BOATS]
    val waves get() = this[Item.WAVES]

    companion object {
        const val DEFAULT = 0.5f
        const val THEME = "theme-pirate"
        fun artKey(o: ArtObject) = "art-${o.name.lowercase()}"
        const val EDGE = "tile-edge"
        const val EVENT_DIE = "event-die-style"
        const val PALETTE = "terrain-palette"
        const val DENSITY = "env-density"
        const val VIEW_BARS = "view-bars"
        const val TILES_3D = "tiles-3d"
        const val LIVE_BOARD = "live-board"
        const val LIVE_LOD = "live-board-lod"
        const val FACE_PIECES = "live-face-pieces"
        const val AI_PACE = "ai-pace"
        const val AUTO_NO = "auto-no"
        /** Mauro kept 5 s as the default (2026-10-22). */
        const val AUTO_NO_DEFAULT = 5
        val AUTO_NO_CHOICES = listOf(0, 5, 10, 20)
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

/** The 3D board On/Off switch and its detail level (High = the full models, Low = the lighter ones), in Settings. */
@Composable
fun LiveBoardChoice(env: EnvSettings) {
    Text("3D board", style = Wood.sans(13.sp, color = Wood.BRASS_EDGE))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("On", selected = env.liveBoard, tag = "live-board-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.liveBoard = true }
        Choice("Off", selected = !env.liveBoard, tag = "live-board-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.liveBoard = false }
    }
    if (env.liveBoard) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Detail: High", selected = env.liveLod == 0, tag = "live-lod-0", modifier = Modifier.height(36.dp).width(140.dp), size = 13.sp) { env.liveLod = 0 }
        Choice("Detail: Low", selected = env.liveLod == 1, tag = "live-lod-1", modifier = Modifier.height(36.dp).width(140.dp), size = 13.sp) { env.liveLod = 1 }
    }
    if (env.liveBoard) {
        Text("Buildings face me when I turn the board", style = Wood.sans(13.sp, color = Wood.BRASS_EDGE))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("On", selected = env.facePieces, tag = "face-pieces-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.facePieces = true }
            Choice("Off", selected = !env.facePieces, tag = "face-pieces-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.facePieces = false }
        }
    }
}

/**
 * The gear menu: every setting in one wood-and-brass panel, in groups that open and shut (Fable review T4: one
 * 25-item scroll hid the three things people change). Changes save as they happen; Reset puts scenery and sound back.
 * In a game, "This game" comes first with its own controls; the game's Menu keeps only actions (W7).
 */
@Composable
fun SettingsPanel(env: EnvSettings, sound: SoundSettings, modifier: Modifier = Modifier,
                  gameControls: (@Composable () -> Unit)? = null, lookAndArt: Boolean = false) {
    // No Done button: every change saves as it is made, and the Menu button (showing Hide) closes the panel (Mauro).
    // Scrolls when the screen is too short for every setting (landscape phones).
    Column(modifier.width(360.dp).brassEdgedWood().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp).testTag("settings-panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = Wood.title(18.sp), modifier = Modifier.weight(1f))
            ResetButton("Reset all") { env.reset(); sound.reset() }
        }
        // In a game: Pause, speed and Watch bots first (Mauro, 2026-10-12: "should be under the gear menu").
        if (gameControls != null) SettingsGroup("This game", "game") { gameControls() }
        SettingsGroup("Sound", "sound") { SoundBody(sound) }
        // Look & art opens on the board, where the theme lives.
        SettingsGroup("Board", "board", startOpen = lookAndArt) {
            // Mauro: a theme groups every piece; single pieces swap by tapping them on the Inventory, and Reset undoes that.
            Setting("Theme", "Tap a piece on the Inventory to swap just that one.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (t in ArtChoice.themes) Choice(t.label, selected = env.art.theme == t, tag = "theme-${if (t == ArtSet.DEFAULT) "normal" else t.name.lowercase()}",
                        modifier = Modifier.height(36.dp).width(100.dp), size = 13.sp) { env.art = env.art.withTheme(t) }
                }
                ArtObject.entries.filter { env.art.swapped(it) }.takeIf { it.isNotEmpty() }?.let { swapped ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Swapped: " + swapped.joinToString { "${it.label} (${env.art[it].label})" }, style = Wood.sans(12.sp), modifier = Modifier.weight(1f))
                        Choice("Reset pieces", selected = false, tag = "theme-reset", modifier = Modifier.height(36.dp).width(130.dp), size = 13.sp) {
                            env.art = env.art.reset()
                        }
                    }
                }
            }
            Setting("Event die", "The Pirate theme's ship face.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (pair in DiceArt.EVENT_STYLES.indices.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (i in pair) Choice(DiceArt.EVENT_STYLES[i], selected = env.eventDieStyle == i, tag = "event-die-$i",
                                modifier = Modifier.height(36.dp).width(122.dp), size = 12.sp) { env.eventDieStyle = i; env.art = env.art.with(ArtObject.DICE, ArtSet.PIRATE) }
                        }
                    }
                    // A preview of the chosen style's ship face.
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(DiceArt.eventFace(true, 0, env.eventDieStyle)), null, Modifier.size(56.dp))
                }
            }
            Setting("Tiles") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Painted", selected = !env.tiles3d, tag = "tiles-painted", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.tiles3d = false }
                    Choice("3D (preview)", selected = env.tiles3d, tag = "tiles-3d", modifier = Modifier.height(36.dp).width(130.dp), size = 13.sp) { env.tiles3d = true }
                }
            }
            Setting("Tile edges") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in EnvSettings.TileEdge.entries)
                        Choice(e.label, selected = env.tileEdge == e, tag = "edge-${e.name.lowercase()}", modifier = Modifier.height(36.dp).width(100.dp), size = 13.sp) { env.tileEdge = e }
                }
            }
            // Terrain colours grade the painted and 3D tiles; Space has its own board, so the row hides there.
            if (!env.art.space(ArtObject.BOARD)) Setting("Terrain colours") {
                // Two by two, as the event die: four in a row ran past the panel's edge.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pair in TerrainPalette.entries.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in pair) Choice(p.label, selected = env.terrainPalette == p, tag = "palette-${p.name.lowercase()}",
                            modifier = Modifier.height(36.dp).width(122.dp), size = 13.sp) { env.terrainPalette = p }
                    }
                }
            }
            LiveBoardChoice(env)
            Setting("Side panel") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Solid", selected = !env.seeThrough, tag = "side-solid", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.seeThrough = false }
                    Choice("See-through", selected = env.seeThrough, tag = "side-see-through", modifier = Modifier.height(36.dp).width(130.dp), size = 13.sp) { env.seeThrough = true }
                }
            }
            Setting("View sliders", "Zoom, Tilt and Turn on the game screen. Pinch, drag and twist work without them.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Show", selected = env.viewBars, tag = "view-bars-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.viewBars = true }
                    Choice("Hide", selected = !env.viewBars, tag = "view-bars-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.viewBars = false }
                }
            }
        }
        // Density scales the four kinds together, so it leads the group (W9: it sat alone in the game's Menu).
        SettingsGroup("Scenery", "scenery") {
            SliderRow("Density", env.density, "env-density") { env.density = it }
            Text("Boats, birds and sea life, all together. At 0 nothing moves.", style = Wood.sans(12.sp, color = Wood.INK_SOFT))
            for (item in EnvSettings.Item.entries)
                SliderRow(item.label, env[item], "env-${item.name.lowercase()}") { env[item] = it }
        }
        SettingsGroup("Play", "play") {
            Setting("AI pace", "Seconds between the AI players' turns; their moves take half.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.1f s".format(env.aiPace), style = Wood.sans(12.sp), modifier = Modifier.width(100.dp))
                    BrassSlider(env.aiPace, { env.aiPace = Math.round(it * 2) / 2f }, "ai-pace", 220.dp,
                        EnvSettings.AI_PACE_MIN..EnvSettings.AI_PACE_MAX, steps = 7)
                }
            }
            Setting("Offers you can't pay", "Declined for you after this long, saying which cards you lack. Off waits for your tap.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in EnvSettings.AUTO_NO_CHOICES)
                        Choice(if (n == 0) "Off" else "$n s", selected = env.autoNoSeconds == n, tag = "auto-no-$n",
                            modifier = Modifier.height(36.dp).width(70.dp), size = 13.sp) { env.autoNoSeconds = n }
                }
            }
            Setting("Report button", "On the game screen: a bug report with a screenshot, in one tap.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Show", selected = env.bugButton, tag = "bug-button-on", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.bugButton = true }
                    Choice("Hide", selected = !env.bugButton, tag = "bug-button-off", modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { env.bugButton = false }
                }
            }
        }
        // #85: the trademark notice, where players look for it in a game too (the full About board is on the start screen).
        SettingsGroup("About", "about") {
            Text("Working German Board Game ${appVersion()} · GPL-3.0", style = Wood.sans(13.sp))
            Text(Splash.TRADEMARK, style = Wood.sans(12.sp, color = Wood.INK_SOFT), modifier = Modifier.testTag("settings-trademark"))
        }
    }
}

/** A Settings group: a brass title that opens and shuts it. */
@Composable
private fun SettingsGroup(title: String, tag: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(startOpen) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }.testTag("settings-$tag"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Wood.title(15.sp), modifier = Modifier.weight(1f))
        Text(if (open) "^" else "v", style = Wood.title(18.sp, Wood.BRASS_EDGE), modifier = Modifier.width(24.dp))
    }
    if (open) Column(Modifier.padding(start = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** One setting: a one-word label, its control, and any explanation under the control in small soft ink. */
@Composable
private fun Setting(label: String, help: String? = null, control: @Composable () -> Unit) {
    Text(label, style = Wood.sans(13.sp, color = Wood.BRASS_EDGE))
    control()
    if (help != null) Text(help, style = Wood.sans(12.sp, color = Wood.INK_SOFT), modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SliderRow(label: String, value: Float, tag: String, sliderWidth: androidx.compose.ui.unit.Dp = 220.dp, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${(value * 100).roundToInt()}%", style = Wood.sans(12.sp), modifier = Modifier.width(100.dp))
        BrassSlider(value, onChange, tag, sliderWidth)
    }
}

/** A slider drawn in the motif: brass groove and a plain brass knob, no Material halo (Fable review T4). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BrassSlider(value: Float, onChange: (Float) -> Unit, tag: String, width: androidx.compose.ui.unit.Dp,
                        range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    Slider(value, onChange, Modifier.width(width).height(36.dp).testTag(tag), valueRange = range, steps = steps, colors = brassSlider(),
        thumb = { BrassKnob(Modifier.size(24.dp)) })
}

@Composable
private fun BrassKnob(modifier: Modifier) = androidx.compose.foundation.Canvas(modifier) {
    val r = size.minDimension / 2
    drawCircle(Wood.BRASS_SHADOW, r)
    drawCircle(Wood.BRASS, r * .84f)
    drawCircle(Wood.BRASS_LIGHT, r * .34f, center + androidx.compose.ui.geometry.Offset(-r * .25f, -r * .25f))
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

    private val turn = mutableFloatStateOf(store.get(TURN_ALERT, 1f))
    /** A bell and a buzz when it becomes your turn. */
    var turnAlert: Boolean
        get() = turn.floatValue > 0.5f
        set(v) { turn.floatValue = if (v) 1f else 0f; store.put(TURN_ALERT, turn.floatValue) }
    private val raiders = mutableFloatStateOf(store.get(RAIDERS_ALERT, 1f))
    /** A horn when the Raiders' ship moves to within [raidersSteps] steps of the island (Age of Chivalry). */
    var raidersAlert: Boolean
        get() = raiders.floatValue > 0.5f
        set(v) { raiders.floatValue = if (v) 1f else 0f; store.put(RAIDERS_ALERT, raiders.floatValue) }
    private val steps = mutableFloatStateOf(store.get(RAIDERS_STEPS, DEFAULT_RAIDERS_STEPS.toFloat()))
    /** How close (steps left on the Raiders' track, [RAIDERS_STEPS_RANGE]) the ship comes before the horn sounds. */
    var raidersSteps: Int
        get() = steps.floatValue.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(RAIDERS_STEPS_RANGE) ?: DEFAULT_RAIDERS_STEPS
        set(v) { steps.floatValue = v.coerceIn(RAIDERS_STEPS_RANGE).toFloat(); store.put(RAIDERS_STEPS, steps.floatValue) }

    /** Back to the defaults (saved); every song ticked again. */
    fun reset() {
        musicVolume = DEFAULT_MUSIC; soundVolume = DEFAULT_SOUNDS; SONGS.indices.forEach { setSongOn(it, true) }
        turnAlert = true; raidersAlert = true; raidersSteps = DEFAULT_RAIDERS_STEPS
    }

    companion object {
        const val DEFAULT_MUSIC = 0.6f
        const val DEFAULT_SOUNDS = 0.8f
        const val MUSIC = "vol-music"
        const val SOUNDS = "vol-sounds"
        const val MUSIC_PAUSED = "music-paused"
        const val TURN_ALERT = "alert-turn"
        const val RAIDERS_ALERT = "alert-raiders"
        const val RAIDERS_STEPS = "alert-raiders-steps"
        const val DEFAULT_RAIDERS_STEPS = 3
        val RAIDERS_STEPS_RANGE = 1..10
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

/** Settings > Sound: Music and Game sounds sliders, Pause / Next, and the song list. */
@Composable
fun SoundBody(sound: SoundSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        run {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Music ${(sound.musicVolume * 100).roundToInt()}%", style = Wood.sans(12.sp), modifier = Modifier.width(96.dp))
                VolumeSlider(sound.musicVolume, "vol-music", 200.dp) { sound.musicVolume = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sounds ${(sound.soundVolume * 100).roundToInt()}%", style = Wood.sans(12.sp), modifier = Modifier.width(96.dp))
                VolumeSlider(sound.soundVolume, "vol-sounds", 200.dp) { sound.soundVolume = it }
            }
            // Alerts (0.3.4): each its own sound, so you know which it is without looking.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your turn: bell and buzz", style = Wood.sans(12.sp), modifier = Modifier.width(150.dp))
                Choice("On", selected = sound.turnAlert, tag = "alert-turn-on", modifier = Modifier.height(36.dp).width(70.dp), size = 13.sp) { sound.turnAlert = true }
                Choice("Off", selected = !sound.turnAlert, tag = "alert-turn-off", modifier = Modifier.height(36.dp).width(70.dp), size = 13.sp) { sound.turnAlert = false }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Raiders close: horn", style = Wood.sans(12.sp), modifier = Modifier.width(150.dp))
                Choice("On", selected = sound.raidersAlert, tag = "alert-raiders-on", modifier = Modifier.height(36.dp).width(70.dp), size = 13.sp) { sound.raidersAlert = true }
                Choice("Off", selected = !sound.raidersAlert, tag = "alert-raiders-off", modifier = Modifier.height(36.dp).width(70.dp), size = 13.sp) { sound.raidersAlert = false }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val n = sound.raidersSteps
                Text("Horn at $n step${if (n == 1) "" else "s"} away", style = Wood.sans(12.sp), modifier = Modifier.width(150.dp).testTag("alert-raiders-steps"))
                Choice("−", selected = false, tag = "alert-raiders-fewer", modifier = Modifier.height(36.dp).width(70.dp), size = 15.sp) { sound.raidersSteps = n - 1 }
                Choice("+", selected = false, tag = "alert-raiders-more", modifier = Modifier.height(36.dp).width(70.dp), size = 15.sp) { sound.raidersSteps = n + 1 }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(if (sound.musicPaused) "▶ Play" else "⏸ Pause", selected = sound.musicPaused, tag = "music-pause",
                    modifier = Modifier.height(36.dp).width(110.dp), size = 13.sp) { sound.musicPaused = !sound.musicPaused }
                Choice("⏭ Next", selected = false, tag = "music-next", modifier = Modifier.height(36.dp).width(100.dp), size = 13.sp) {
                    sound.musicPaused = false; sound.onNext()
                }
            }
            Text("Songs (tick to keep in the list, tap a name to play it)", style = Wood.sans(12.sp))
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

/** Reset to defaults: the brass circular-arrow icon (Mauro's art), darker while pressed, with its [label] beside it (#91). */
@Composable
private fun ResetButton(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(Modifier.clickable(interactionSource = source, indication = null, onClick = onClick).testTag("settings-reset"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Wood.sans(13.sp, color = Wood.BRASS_EDGE))
        Image(painterResource(if (pressed) R.drawable.reset_arrow_pressed else R.drawable.reset_arrow), "Reset every setting to its default",
            Modifier.size(44.dp))
    }
}
