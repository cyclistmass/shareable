package com.mauro.wgbg

import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import wgbg.net.*
import java.util.concurrent.CompletableFuture

/** AppRoot owns this session and closes it when leaving the room. */
class LobbySession(val transport: GameTransport, val setup: GameSetup, val id: String, val uid: String) : AutoCloseable {
    val client = GameClient(transport, id, uid).also { it.start() }
    fun resume() = client.resume()
    override fun close() = client.close()
    fun model(room: Room): Lobby = Lobby(id, setup.copy(decks = room.decks,
        ruleset = Ruleset.entries.first { it.expansion == room.config.expansion }, points = room.config.vpToWin),
        room.seats.map { LobbySeat(it.name.takeIf { _ -> it.uid != null }, it.bot?.let(BotLevel::valueOf), it.ready, open = it.open) },
        me = room.seats.indexOfFirst { it.uid == uid }.coerceAtLeast(0), host = room.host == uid)
    fun onChange(lobby: Lobby, room: Room): CompletableFuture<Unit> = transport.editLobby(id,
        lobby.seats.mapIndexed { i, s -> room.seats[i].copy(bot = s.bot?.name, ready = s.ready) }, room.seats)
    companion object {
        /**
         * Hosts a new room. Codes are short (ROVER-42) and rooms are kept after their games, so a fresh code can belong
         * to an old room; the server then refuses (in use, or not even readable): try another code, up to [attempts].
         */
        fun host(transport: GameTransport, setup: GameSetup, seed: Long, code: String = Lobby.newCode(), attempts: Int = 8,
                 nextCode: () -> String = { Lobby.newCode() }): CompletableFuture<LobbySession> =
            transport.signIn().thenCompose { uid -> create(transport, setup, seed, uid, code, attempts, nextCode) }

        private fun create(transport: GameTransport, setup: GameSetup, seed: Long, uid: String, code: String, attempts: Int,
                           nextCode: () -> String): CompletableFuture<LobbySession> {
            val lobby = Lobby.host(setup, code)
            val seats = lobby.seats.mapIndexed { i, s -> Seat(if (i == 0) uid else null, s.name ?: "", s.bot?.name, s.open, s.ready) }
            return transport.create(Room(code, uid, setup.config(), seed, seats, decks = setup.decks))
                .handle { _, e -> e }
                .thenCompose { e ->
                    when {
                        e == null -> CompletableFuture.completedFuture(LobbySession(transport, setup, code, uid))
                        attempts > 1 -> create(transport, setup, seed, uid, nextCode(), attempts - 1, nextCode)
                        else -> CompletableFuture<LobbySession>().also { it.completeExceptionally(e) }
                    }
                }
        }
        fun join(transport: GameTransport, setup: GameSetup, code: String, seat: Int): CompletableFuture<LobbySession> =
            transport.signIn().thenCompose { uid -> transport.join(code, seat, setup.name).thenApply { LobbySession(transport, setup, code, uid) } }

        /**
         * Back into room [code] where this phone already holds a seat (after the app was closed or killed):
         * the session replays the room's moves and play carries on. Fails if the room is gone, over, or this
         * phone isn't seated in it.
         */
        fun rejoin(transport: GameTransport, setup: GameSetup, code: String): CompletableFuture<LobbySession> =
            transport.signIn().thenCompose { uid ->
                val s = LobbySession(transport, setup, code, uid)
                val f = CompletableFuture<LobbySession>()
                val sub = s.client.watch { v ->
                    val r = v.room
                    when {
                        // A started room you don't sit in can't even be read (permission denied): say it plainly.
                        r == null -> v.error?.let { e ->
                            f.completeExceptionally(if (onlineProblem(e) == "No room with that code") e else IllegalStateException("That room is full or has started"))
                        }
                        r.status == Status.done -> f.completeExceptionally(IllegalStateException("That game has ended"))
                        r.seats.any { it.uid == uid } -> f.complete(s)
                        else -> f.completeExceptionally(IllegalStateException("That room is full or has started"))
                    }
                }
                f.whenComplete { _, e -> sub.close(); if (e != null) s.close() }
                f.orTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            }
    }
}

/** Existing tabletop screen with the transport behind its onChange seam. */
@Composable
fun OnlineLobbyScreen(session: LobbySession, onPlaying: (ClientView) -> Unit, onSettings: () -> Unit,
    onError: (Throwable) -> Unit, modifier: Modifier = Modifier) {
    var view by remember(session) { mutableStateOf(session.client.view()) }
    val playing by rememberUpdatedState(onPlaying); val error by rememberUpdatedState(onError)
    DisposableEffect(session) {
        val sub = session.client.watch {
            view = it
            it.error?.let(error)
            if (it.room?.status == Status.playing) playing(it)
        }
        onDispose { sub.close() }
    }
    view.room?.let { room -> LobbyScreen(session.model(room),
        { session.onChange(it, room).whenComplete { _, e -> if (e != null) error(e) } },
        { session.transport.start(session.id).whenComplete { _, e -> if (e != null) error(e) } }, onSettings, modifier,
        reconnecting = view.reconnecting) }
}

/** Place beside the game HUD; never disables gameplay or opens a blocking dialog. */
@Composable
fun OnlineConnectionStatus(view: ClientView, modifier: Modifier = Modifier) {
    // Your own moves normally confirm in milliseconds; if some stay unconfirmed for 2 s the server is
    // probably out of reach (Firestore can take a while to notice), so say so instead of looking normal.
    var stuck by remember { mutableStateOf(false) }
    val waiting = view.pendingCount > 0
    LaunchedEffect(waiting) { stuck = false; if (waiting) { kotlinx.coroutines.delay(2000); stuck = true } }
    connectionLabel(view.reconnecting, view.pendingCount, stuck)?.let {
        Text(it, style = Wood.sans(13.sp, androidx.compose.ui.text.font.FontWeight.Bold, Wood.WAITING),
            modifier = modifier.brassEdgedWood().padding(horizontal = 10.dp, vertical = 3.dp).testTag("connection"))
    }
}

/** The connection note, or null when all is well. */
internal fun connectionLabel(reconnecting: Boolean, pending: Int, stuck: Boolean): String? = when {
    pending > 0 && stuck -> "Not synced: $pending move${if (pending > 1) "s" else ""} waiting for the server"
    reconnecting -> "Reconnecting…"
    else -> null
}

/**
 * Who plays which engine player in an online room (seats are frozen once it starts). Active seats map to
 * engine players in seat order; this phone is [me] (-1 if not seated); the host's phone moves for [bot] seats.
 */
class OnlineSeats(room: Room, uid: String) {
    val players: List<Int> = room.players
    val me: Int = players.indexOfFirst { room.seats[it].uid == uid }
    val isHost: Boolean = room.host == uid
    val config: wgbg.engine.GameConfig = room.config.copy(numPlayers = players.size)
    val seed: Long = room.seed
    /** Names by engine player; your own seat shows the name you typed (Mauro: it said "You" instead of my name). */
    val names: List<String> = players.mapIndexed { i, s ->
        val seat = room.seats[s]
        when {
            i == me -> seat.name.ifBlank { "You" }
            seat.uid != null -> seat.name.ifBlank { PLAYER_NAMES[i] }
            else -> PLAYER_NAMES[i]   // just the colour name: the bot's level only took room (Mauro)
        }
    }
    private val bots: List<BotLevel?> = players.map { s -> room.seats[s].takeIf { it.uid == null }?.bot?.let(BotLevel::valueOf) }
    /** The bot level playing engine player [i], or null when a person plays it. */
    fun bot(i: Int): BotLevel? = bots.getOrNull(i)
    /** The room seat of engine player [i] (what GameClient.act takes). */
    fun seatOf(i: Int): Int = players[i]
}

/** Join a room by its code (ROVER-42; any case, spaces or no dash). Takes the first open seat. */
@Composable
fun JoinRoomScreen(name: String, busy: Boolean, onJoin: (code: String, name: String) -> Unit, onBack: () -> Unit,
                   modifier: Modifier = Modifier, initialCode: String = "") {
    var typed by remember { mutableStateOf(initialCode) }
    var me by remember { mutableStateOf(if (onlineName(name)) name else "") }
    val code = Lobby.normalizeCode(typed)
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize().tableTop().displayCutoutPadding().testTag("join"), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            Modifier.width(380.dp).brassEdgedWood().padding(18.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Text("Join a room", style = Wood.title(26.sp, Wood.BRASS_INK))
            Text("Your name", style = Wood.sans(13.sp, color = Wood.INK_MUTED))
            ParchmentField(me, { me = it.take(16) }, "join-name")
            Text("Room code", style = Wood.sans(13.sp, color = Wood.INK_MUTED))
            ParchmentField(typed, { typed = it.take(12) }, "join-code", androidx.compose.ui.text.input.KeyboardCapitalization.Characters)
            Text(when {
                typed.isBlank() -> "The host reads it out or shares the link: like ROVER-42"
                code == null -> "That doesn't look like a room code"
                else -> "Room $code"
            }, style = Wood.sans(13.sp, color = if (typed.isNotBlank() && code == null) Wood.WAITING else Wood.INK_MUTED), modifier = Modifier.testTag("join-hint"))
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                BrassButton("Back", "join-back", Modifier.height(44.dp)) { onBack() }
                GoButton(if (busy) "Joining…" else "Join", "join-go", code != null && onlineName(me) && !busy,
                    Modifier.weight(1f).height(44.dp)) { onJoin(code!!, me.trim()) }
            }
        }
    }
}
