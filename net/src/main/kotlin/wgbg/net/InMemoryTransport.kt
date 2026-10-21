package wgbg.net

import java.util.concurrent.CompletableFuture
import wgbg.engine.Rules

/** Shared store models multiple independently authenticated clients. */
class MemoryStore {
    internal val rooms = mutableMapOf<String, Snapshot>()
    internal val listeners = mutableMapOf<String, MutableList<() -> Unit>>()
    internal val drivers = mutableMapOf<String, SyncDriver>()
    internal val deltaListeners = mutableMapOf<String, MutableList<(MoveUpdate) -> Unit>>()
    /** Everyone's people played with, by owner uid (#72). */
    internal val friends = mutableMapOf<String, MutableMap<String, Friend>>()
    internal val inviteListeners = mutableListOf<() -> Unit>()
}
class InMemoryTransport(val uid: String, private val store: MemoryStore = MemoryStore(), override val google: Boolean = false) : GameTransport {
    private fun <T> operation(f: () -> T): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(synchronized(store) { f() })
    } catch (e: Exception) { CompletableFuture<T>().also { it.completeExceptionally(e) } }
    private fun snapshot(id: String) = store.rooms[id] ?: error("Unknown room")
    private fun publish(s: Snapshot) {
        val previousCount = store.rooms[s.room.id]?.moves?.size ?: 0
        store.rooms[s.room.id] = s
        store.listeners[s.room.id]?.toList()?.forEach { it() }
        store.inviteListeners.toList().forEach { it() }
        val update = MoveUpdate(s.room, s.moves.drop(previousCount))
        store.deltaListeners[s.room.id]?.toList()?.forEach { it(update) }
    }
    override fun signIn() = operation { uid }
    override fun create(room: Room) = operation {
        require(room.host == uid && room.status == Status.lobby && room.nextSeq == 0L)
        require(room.id !in store.rooms); publish(Snapshot(room, emptyList())); room
    }
    override fun listOpen() = operation { store.rooms.values.map { it.room }.filter { it.status == Status.lobby } }
    /** Applies one of [RoomOps] to room [id] and publishes the result. */
    private fun change(id: String, f: (Room) -> Room) = operation { val s = snapshot(id); publish(s.copy(room = f(s.room))) }
    override fun join(id: String, seat: Int, name: String) = change(id) { RoomOps.join(it, uid, google, seat, name) }
    override fun leave(id: String) = change(id) { RoomOps.leave(it, uid) }
    override fun editLobby(id: String, seats: List<Seat>, expectedSeats: List<Seat>?) = change(id) { RoomOps.editLobby(it, uid, seats, expectedSeats) }
    override fun start(id: String) = change(id) { RoomOps.start(it, uid) }
    override fun submit(id: String, seq: Long, seat: Int, action: String) = operation {
        val s = snapshot(id); require(s.room.status == Status.playing && seq == s.room.nextSeq)
        val driver = store.drivers.getOrPut(id) { SyncDriver() }
        driver.acceptDelta(s.room, s.moves.drop((driver.confirmedSeq + 1).toInt())); val state = driver.state!!
        val owner = s.room.seats.getOrNull(seat)?.let { it.uid ?: if (it.bot != null) s.room.host else null }
        val a = MoveCodec.decode(action) ?: error("Invalid move")
        require(owner == uid && s.room.players.indexOf(seat) == Rules.mover(state, a)) // the offerer may withdraw out of turn (#114)
        require(Rules.isLegal(state, a))
        publish(s.copy(room = s.room.copy(nextSeq = seq + 1), moves = s.moves + Move(seq, seat, uid, action)))
    }
    override fun finish(id: String) = change(id) { RoomOps.finish(it, uid) }
    override fun invite(id: String, uids: List<String>) = change(id) { RoomOps.invite(it, uid, uids) }
    override fun setNext(id: String, next: String) = change(id) { RoomOps.setNext(it, uid, next) }
    override fun observeInvites(onRooms: (List<Room>) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val listener = { onRooms(store.rooms.values.map { it.room }.filter { it.status == Status.lobby && uid in it.invited }) }
        synchronized(store) { store.inviteListeners.add(listener); listener() }
        return Subscription { synchronized(store) { store.inviteListeners.remove(listener) } }
    }
    override fun friends() = operation { store.friends[uid]?.values?.toList() ?: emptyList() }
    override fun putFriend(f: Friend) = operation { require(f.uid != uid); store.friends.getOrPut(uid) { mutableMapOf() }[f.uid] = f }
    override fun forgetFriend(uid: String) = operation { store.friends[this.uid]?.remove(uid); Unit }
    override fun observe(id: String, onSnapshot: (Snapshot) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val listener = { try { val s = snapshot(id); require(s.room.status == Status.lobby || s.room.seats.any { it.uid == uid }); onSnapshot(s) } catch (e: Exception) { runCatching { onError(e) }; Unit } }
        synchronized(store) { store.listeners.getOrPut(id) { mutableListOf() }.add(listener); listener() }
        return Subscription { synchronized(store) { store.listeners[id]?.remove(listener) } }
    }
    override fun observeMoves(id: String, afterSeq: Long, onUpdate: (MoveUpdate) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val listener: (MoveUpdate) -> Unit = { u ->
            try {
                require(u.room.status == Status.lobby || u.room.seats.any { it.uid == uid })
                onUpdate(u.copy(moves = u.moves.filter { it.seq > afterSeq }))
            } catch (e: Exception) { runCatching { onError(e) } }
        }
        synchronized(store) {
            store.deltaListeners.getOrPut(id) { mutableListOf() }.add(listener)
            runCatching { val s = snapshot(id); listener(MoveUpdate(s.room, s.moves.filter { it.seq > afterSeq })) }.onFailure(onError)
        }
        return Subscription { synchronized(store) { store.deltaListeners[id]?.remove(listener) } }
    }
}
