package com.mauro.wgbg

import java.io.File
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wgbg.net.*

/**
 * The documents the app really writes to Firestore, recorded through the app's own code paths (LobbySession, the lobby
 * model, RoomOps, [created], [STARTED], [FINISHED], [friend]) over a whole friends game and its rematch, and compared with
 * firebase/rules-test/app-writes.json. The rules tests replay that file against firestore.rules, so a change on either
 * side that would make the server refuse a real write fails a test instead of a player's game.
 *
 * A deliberate change of what the app writes: run with UPDATE_FIXTURES=1, then rerun the rules tests (npm test in
 * firebase/rules-test) and commit the new file with the change.
 */
class AppWritesTest {
    private val fixture = File("../firebase/rules-test/app-writes.json")
    private val steps = mutableListOf<String>()

    /** [inner]'s writes, recorded as Firestore would get them; [host] reads the room back (the host can always read it). */
    private inner class Recorder(private val inner: InMemoryTransport, private val host: () -> GameTransport) : GameTransport by inner {
        private fun now(id: String): Room { var r: Room? = null; host().observe(id, { r = it.room }) { throw it }.close(); return r!! }
        private fun record(op: String, write: String, path: String, data: Map<String, Any?>) {
            steps += json(linkedMapOf("step" to "${inner.uid} $op", "uid" to inner.uid, "google" to inner.google, "write" to write, "path" to path, "data" to data))
        }
        private fun <T> update(op: String, id: String, extra: Map<String, Any?> = emptyMap(), f: CompletableFuture<T>) =
            f.thenApply { it.also { record(op, "update", "games/$id", now(id).wire() + extra) } }
        override fun create(room: Room) = inner.create(room).thenApply { it.also { record(if (room.rematchOf != null) "creates the rematch" else "creates a room", "set", "games/${room.id}", created(room)) } }
        override fun join(id: String, seat: Int, name: String) = update("joins seat $seat", id, f = inner.join(id, seat, name))
        override fun leave(id: String): CompletableFuture<Unit> {
            val playing = now(id).status == Status.playing
            return update(if (playing) "quits the running game" else "leaves the lobby", id, f = inner.leave(id))
        }
        override fun editLobby(id: String, seats: List<Seat>, expectedSeats: List<Seat>?) = update("edits the lobby", id, f = inner.editLobby(id, seats, expectedSeats))
        override fun start(id: String) = update("starts", id, STARTED, inner.start(id))
        override fun finish(id: String) = update("ends the game", id, FINISHED, inner.finish(id))
        override fun invite(id: String, uids: List<String>) = update("invites ${uids.size}", id, f = inner.invite(id, uids))
        override fun setNext(id: String, next: String) = update("links the rematch", id, f = inner.setNext(id, next))
        override fun putFriend(f: Friend) = inner.putFriend(f).thenApply { record("keeps ${f.uid} as played with", "set", "users/${inner.uid}/met/${f.uid}", friend(f)) }
    }

    @Test fun `the app's writes over a friends game and its rematch match app-writes json`() {
        val store = MemoryStore()
        lateinit var hostT: Recorder
        fun player(uid: String, google: Boolean) = Recorder(InMemoryTransport(uid, store, google)) { hostT }
        hostT = player("host", google = true)
        val gus = player("gus", google = true); val ivy = player("ivy", google = false)
        val setup = GameSetup(name = "Mauro", theme = NameTheme.ENGLISH_QUEENS, randomColours = true)
            .withSeat(1, SeatKind.FRIEND).withSeat(2, SeatKind.FRIEND)

        // A room with an invite from the played-with list, two people joining (one with Google, one a guest), both ready.
        val h = LobbySession.host(hostT, setup, seed = 42, code = "COBALT-1", invited = listOf("erin")).join()
        val g = LobbySession.join(gus, setup.copy(name = "Gus"), h.id, 1).join()
        val i = LobbySession.join(ivy, setup.copy(name = "Ivy"), h.id, 2).join()
        for (s in listOf(g, i)) s.onChange(s.model(room(s)).toggleReady(), room(s)).join()
        hostT.invite(h.id, listOf("erin", "bo")).join()
        h.onChange(h.model(room(h)).setBot(3, BotLevel.EASY), room(h)).join()
        hostT.start(h.id).join()
        for (f in Friends.met(room(h), "host", emptyMap(), NOW)) hostT.putFriend(f).join()
        ivy.leave(h.id).join()
        hostT.finish(h.id).join()

        // The rematch: the same settings, its people invited back, the old room pointing at it; Gus follows.
        val old = room(h)
        val r = LobbySession.rematch(hostT, setup, old, seed = 43, code = "HAVEN-2").join()
        hostT.setNext(old.id, r.id).join()
        LobbySession.join(gus, setup.copy(name = "Gus"), r.id, 1).join()
        // A host leaving their own lobby takes back its invites.
        r.leave()!!.join()
        listOf(h, g, i, r).forEach { it.close() }

        val text = steps.joinToString(",\n", "[\n", "\n]\n")
        if (System.getenv("UPDATE_FIXTURES") != null) fixture.writeText(text)
        assertEquals(fixture.takeIf { it.exists() }?.readText()?.replace("\r\n", "\n"), text,
            "What the app writes to Firestore changed. If that's meant, run with UPDATE_FIXTURES=1, rerun the rules tests " +
                "(npm test in firebase/rules-test) and commit ${fixture.path}.")
    }

    private fun room(s: LobbySession): Room { var r: Room? = null; s.transport.observe(s.id, { r = it.room }) { throw it }.close(); return r!! }

    private companion object {
        /** A fixed "now" for the played-with entries, so the file doesn't change from run to run. */
        const val NOW = 1_790_000_000_000L

        fun json(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + v.flatMap { c -> when (c) {
                '"' -> listOf('\\', '"'); '\\' -> listOf('\\', '\\'); '\n' -> listOf('\\', 'n')
                else -> if (c < ' ') "\\u%04x".format(c.code).toList() else listOf(c) } }.joinToString("") + "\""
            is Boolean, is Int, is Long -> v.toString()
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> json(k.toString()) + ":" + json(x) }
            is List<*> -> v.joinToString(",", "[", "]") { json(it) }
            ServerTime -> """{"${'$'}serverTime":true}"""
            is InDays -> """{"${'$'}inDays":${v.days}}"""
            is At -> """{"${'$'}millis":${v.millis}}"""
            else -> error("No JSON for ${v::class}")
        }
    }
}
