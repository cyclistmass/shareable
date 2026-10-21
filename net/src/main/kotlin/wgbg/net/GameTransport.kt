package wgbg.net

import wgbg.engine.*
import java.util.concurrent.CompletableFuture
import java.security.SecureRandom

/** [google]: the person in this seat is signed in with Google (#72: only they go on friends lists; a guest's id is lost on sign-out). */
data class Seat(val uid: String? = null, val name: String = "", val bot: String? = null, val open: Boolean = true, val ready: Boolean = false,
    val google: Boolean = false)
enum class Status { lobby, playing, done }
/**
 * [invited]: people the host asked in (#72), who see the room on their start screen. [rematchOf]: the finished room this
 * one plays again; [next]: set on a finished room once its host opens the rematch, so its players can follow.
 */
data class Room(val id: String, val host: String, val config: GameConfig, val seed: Long,
    val seats: List<Seat>, val status: Status = Status.lobby, val nextSeq: Long = 0,
    val decks: Int = 0, val invited: List<String> = emptyList(), val rematchOf: String? = null, val next: String? = null) {
    init { require(seats.size == 4); require(seats[0].uid == host) }
    val players get() = seats.indices.filter { seats[it].uid != null || seats[it].bot != null }
}
data class Move(val seq: Long, val seat: Int, val uid: String, val action: String)
data class Snapshot(val room: Room, val moves: List<Move>)
data class MoveUpdate(val room: Room, val moves: List<Move>, val fromCache: Boolean = false)
fun interface Subscription { fun close() }
fun moveId(seq: Long) = seq.toString().padStart(10, '0')

/**
 * [name] as it should sit in a room where [taken] are already seated: trimmed, and when someone there already has it
 * (ignoring case), numbered "Erin 2", "Erin 3"... so two players never share a name on the board or in the history.
 */
fun uniqueSeatName(name: String, taken: List<String>): String {
    val base = name.trim()
    val used = taken.map { it.trim().lowercase() }.toSet()
    if (base.lowercase() !in used) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in used }
}

/** Methods complete exceptionally for conflicts/denials. Callbacks may run off the UI thread. */
interface GameTransport {
    fun signIn(): CompletableFuture<String>
    fun create(room: Room): CompletableFuture<Room>
    fun listOpen(): CompletableFuture<List<Room>>
    fun join(id: String, seat: Int, name: String): CompletableFuture<Unit>
    fun leave(id: String): CompletableFuture<Unit>
    fun editLobby(id: String, seats: List<Seat>, expectedSeats: List<Seat>? = null): CompletableFuture<Unit>
    fun start(id: String): CompletableFuture<Unit>
    fun finish(id: String): CompletableFuture<Unit>
    fun submit(id: String, seq: Long, seat: Int, action: String): CompletableFuture<Unit>
    fun observe(id: String, onSnapshot: (Snapshot) -> Unit, onError: (Throwable) -> Unit): Subscription
    /** This player is signed in with Google (#72); a guest never is. */
    val google: Boolean get() = false
    /** Host, in the lobby: the people invited to room [id] (#72). */
    fun invite(id: String, uids: List<String>): CompletableFuture<Unit> = unsupported()
    /** Host, on a finished room: the rematch's code, for its players to follow (#72). */
    fun setNext(id: String, next: String): CompletableFuture<Unit> = unsupported()
    /** Rooms still in the lobby that invite this player, as they change (#72). */
    fun observeInvites(onRooms: (List<Room>) -> Unit, onError: (Throwable) -> Unit): Subscription = Subscription { }
    /** This player's people played with (#72), any order. */
    fun friends(): CompletableFuture<List<Friend>> = unsupported()
    fun putFriend(f: Friend): CompletableFuture<Unit> = unsupported()
    fun forgetFriend(uid: String): CompletableFuture<Unit> = unsupported()
    fun observeMoves(id: String, afterSeq: Long, onUpdate: (MoveUpdate) -> Unit, onError: (Throwable) -> Unit): Subscription {
        var last = afterSeq
        return observe(id, { s ->
            val delta = s.moves.filter { it.seq > last }
            if (delta.isNotEmpty()) last = delta.maxOf { it.seq }
            onUpdate(MoveUpdate(s.room, delta))
        }, onError)
    }
}

private fun <T> unsupported(): CompletableFuture<T> = CompletableFuture.failedFuture(UnsupportedOperationException("Not on this connection"))

/** Someone you played an online game with (#72): kept on your account, starred to keep for good. */
data class Friend(val uid: String, val name: String, val lastPlayed: Long, val games: Int = 1, val starred: Boolean = false)

object Friends {
    /** Unstarred people drop off the list this long after your last game together. */
    const val KEEP_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * The entries to write when [room] starts with [me] in it: everyone else seated and signed in with Google, with
     * their name in this room, [now] as the last game and one more game together than [known] says.
     */
    fun met(room: Room, me: String, known: Map<String, Friend>, now: Long): List<Friend> =
        room.seats.filter { it.uid != null && it.uid != me && it.google }.map { s ->
            val old = known[s.uid]
            Friend(s.uid!!, s.name.ifBlank { old?.name ?: "" }, now, (old?.games ?: 0) + 1, old?.starred ?: false)
        }

    /** The list to show: starred first, then the most recent; unstarred people not seen for [KEEP_MS] are left out. */
    fun shown(all: List<Friend>, now: Long): List<Friend> =
        all.filter { it.starred || now - it.lastPlayed <= KEEP_MS }
            .sortedWith(compareByDescending<Friend> { it.starred }.thenByDescending { it.lastPlayed })

    /**
     * The rematch of finished room [old], hosted again by its host as room [id]: the same settings and bots, the
     * people's seats open again, and those people invited.
     */
    fun rematch(old: Room, id: String, seed: Long): Room {
        val people = old.seats.drop(1).mapNotNull { it.uid }
        return Room(id, old.host, old.config, seed, old.seats.mapIndexed { i, s ->
            when {
                i == 0 -> s.copy(ready = true)
                s.uid != null -> Seat()
                else -> s.copy(ready = false)
            }
        }, decks = old.decks, invited = people, rematchOf = old.id)
    }
}

/** v1 deliberately uses type names, not enum ordinals, on the wire. */
object MoveCodec {
    fun encode(a: Action): String { require(Action.isKnownType(a.typeId)); return "1:${a.type}:${a.payload}" }
    fun decode(text: String): Action? = runCatching {
        val p = text.split(':'); require(p.size == 3 && p[0] == "1")
        val kind = (ActionType.entries + CkActionType.entries).first { it.toString() == p[1] }
        val payload = p[2].toLong(); require(payload in 0..Action.PAYLOAD_MASK && p[2] == payload.toString())
        Action.of(kind, payload)
    }.getOrNull()
}

/** Incremental ordered replay. Invalid entries consume their sequence, but never mutate state. */
class SyncDriver {
    private var identity: Room? = null
    private val prefix = mutableListOf<Move>()
    private var game: Game? = null
    private val buffered = java.util.TreeMap<Long, Move>()
    val confirmedSeq: Long get() = prefix.size.toLong() - 1
    fun move(seq: Long): Move? = prefix.getOrNull(seq.toInt())
    val state: GameState? get() = game?.state?.copy()
    val actions: List<Action> get() = game?.actions ?: emptyList()
    val rejected = mutableListOf<Long>()
    /** What makes it the same game: a new move, the finish, or the rematch link ([Room.next], #72) on a done room
     *  changes the room doc but not its game, so none of them may wipe the history. */
    private fun gameKey(room: Room) = room.copy(nextSeq = 0, status = Status.playing, next = null)
    /** Hot path: only process newly received entries; retain gaps until their predecessor arrives. */
    fun acceptDelta(room: Room, moves: List<Move>) {
        if (room.status == Status.lobby) return
        val key = gameKey(room)
        if (identity != key) {
            identity = key; prefix.clear(); buffered.clear(); rejected.clear()
            game = Game(room.config.copy(numPlayers = room.players.size), room.seed)
        }
        moves.filter { it.seq > confirmedSeq }.forEach { buffered[it.seq] = it }
        while (true) {
            val m = buffered.remove(confirmedSeq + 1) ?: break
            applyConfirmed(room, m)
        }
    }
    private fun applyConfirmed(room: Room, m: Move) {
        val g = game!!; val a = MoveCodec.decode(m.action)
        val seat = room.seats.getOrNull(m.seat)
        val owner = seat?.uid ?: if (seat?.bot != null) room.host else null
        if (a == null || owner != m.uid || room.players.indexOf(m.seat) != Rules.mover(g.state, a) || !Rules.isLegal(g.state, a)) rejected.add(m.seq)
        else g.apply(a)
        prefix.add(m)
    }
    fun accept(snapshot: Snapshot) {
        val room = snapshot.room
        if (room.status == Status.lobby) return
        val ordered = snapshot.moves.sortedBy { it.seq }
        val key = gameKey(room)
        if (identity != key || ordered.take(prefix.size) != prefix) {
            identity = key; prefix.clear(); buffered.clear(); rejected.clear()
            game = Game(room.config.copy(numPlayers = room.players.size), room.seed)
        }
        val g = game!!
        for (m in ordered.drop(prefix.size)) {
            if (m.seq != prefix.size.toLong()) break // wait for a missing snapshot entry
            applyConfirmed(room, m)
        }
    }
    /** Draw dice from accepted history, so stacked decks survive phone changes/reconnects. */
    fun nextRoll(decks: Int, rng: SecureRandom = SecureRandom()): Action {
        val s = game?.state ?: error("Game has not started")
        return rollFromHistory(s, actions, decks, rng)
    }
}

fun rollFromHistory(s: GameState, actions: List<Action>, decks: Int, rng: SecureRandom = SecureRandom()): Action {
    require(Rules.isLegal(s, Action.ROLL))
    val roll = if (decks == 0) TrueRandomDice(rng).next(s.config.citiesAndKnights) else {
        val drawn = actions.filter { it.type == ActionType.ROLL }.map {
            require(it.payload != 0L) { "Deck games require explicit dice in every roll" }
            DiceDeck.cardOf(it.a, it.b)
        }
        DiceDeck.nextFromLog(drawn, decks, rng = rng).copy(event = if (s.config.citiesAndKnights) rng.nextInt(6) else -1)
    }
    return roll.toAction()
}
