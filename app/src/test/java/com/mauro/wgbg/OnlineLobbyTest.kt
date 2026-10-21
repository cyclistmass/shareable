package com.mauro.wgbg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import wgbg.engine.*
import wgbg.net.*

class OnlineLobbyTest {
    @Test fun `#72 a host leaving their lobby withdraws its invites, and a guest leaving frees the seat`() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store, google = true); val erin = InMemoryTransport("erin", store, google = true)
        var invites = listOf<String>()
        erin.observeInvites({ rooms -> invites = rooms.map { it.id } }) { throw it }
        val dead = LobbySession.host(h, GameSetup(), 1, code = "OAK-1", invited = listOf("erin")).join()
        val live = LobbySession.host(h, GameSetup().withSeat(1, SeatKind.FRIEND), 2, code = "ELM-2", invited = listOf("erin")).join()
        assertEquals(setOf("OAK-1", "ELM-2"), invites.toSet())
        dead.leave()!!.join()
        assertEquals(listOf("ELM-2"), invites)
        assertEquals(emptyList<String>(), dead.client.view().room!!.invited); dead.close()
        erin.join("ELM-2", 1, "Erin").join()
        val guest = LobbySession(erin, GameSetup(), "ELM-2", "erin")
        guest.leave()!!.join(); guest.close()
        val r = live.client.view().room!!
        assertEquals(null, r.seats[1].uid)
        assertEquals(listOf("erin"), r.invited) // the live room still asks for them
        live.close()
    }
    @Test fun `Firestore wire preserves every config field and long seeds`() {
        for (expansion in Expansion.entries) for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
            val config = GameConfig(expansion, 3, 16, BoardLayout.BEGINNER, false, 9, 321, true, true, true)
            val r = Room("ROVER-42", "host", config, seed, listOf(Seat("host", "Host"), Seat("guest", "Guest", ready = true), Seat(bot = "STRONG", open = false), Seat()), Status.playing, 123, 4)
            assertEquals(r, room(r.id, r.wire()))
        }
    }
    @Test fun `#72 invites, the rematch link and the Google mark survive the wire, and rooms without them read as none`() {
        val seats = listOf(Seat("host", "Host", google = true), Seat("erin", "Erin", open = false, google = true), Seat(), Seat())
        val r = Room("ROVER-42", "host", GameSetup().config(), 9, seats, Status.done, 0, 0, listOf("erin", "bo"), "OAK-12", "ELM-310")
        assertEquals(r, room(r.id, r.wire()))
        val plain = r.copy(invited = emptyList(), rematchOf = null, next = null)
        assertFalse("next" in plain.wire() || "rematchOf" in plain.wire())
        @Suppress("UNCHECKED_CAST")
        val bare = plain.wire().toMutableMap().also { w -> w.remove("invited")
            w["seats"] = (w["seats"] as Map<String, Map<String, Any?>>).mapValues { it.value - "google" } }
        assertEquals(plain.copy(seats = seats.map { it.copy(google = false) }), room(r.id, bare))
    }
    @Test fun `#126 online games start from the seed's seat, and a room made before #126 reads as seat 0 starting`() {
        assertTrue(GameSetup().config().randomFirstPlayer)
        val r = Room("ROVER-42", "host", GameSetup().config(), 9, listOf(Seat("host", "Host"), Seat(), Seat(), Seat()), Status.playing, 0, 0)
        assertTrue(room(r.id, r.wire()).config.randomFirstPlayer)
        @Suppress("UNCHECKED_CAST")
        val old = r.wire().toMutableMap().also { w -> w["options"] = (w["options"] as Map<String, Any?>) - "randomFirstPlayer" }
        assertFalse(room(r.id, old).config.randomFirstPlayer)
    }
    @Test fun `a room from another version of the game is refused with an update message, never misread`() {
        val r = Room("ROVER-42", "host", GameSetup().config(), 9, listOf(Seat("host", "Host"), Seat(), Seat(), Seat()), Status.lobby, 0, 0)
        assertEquals(ROOM_VERSION, r.wire()["version"])
        for (v in listOf<Any?>(null, 1, ROOM_VERSION + 1)) {
            val e = assertThrows(RoomVersionException::class.java) { room(r.id, r.wire() + ("version" to v)) }
            assertTrue(e.message!!.contains(if (v == ROOM_VERSION + 1) "newer" else "older") && e.message!!.contains("update"), e.message)
        }
    }
    @Test fun `session maps identity bots settings and readiness through onChange`() {
        val store = MemoryStore(); val host = InMemoryTransport("host", store); val guest = InMemoryTransport("guest", store)
        val setup = GameSetup(name = "Host").withSeat(1, SeatKind.FRIEND)
        val h = LobbySession.host(host, setup, 7, "ROVER-42").join()
        val g = LobbySession.join(guest, setup.copy(name = "Guest"), h.id, 1).join()
        var latest: Snapshot? = null; val sub = guest.observe(g.id, { latest = it }) { throw it }
        val model = g.model(latest!!.room); assertEquals(1, model.me); assertFalse(model.isHost)
        g.onChange(model.toggleReady(), latest!!.room).join(); assertTrue(g.model(latest!!.room).seats[1].ready)
        assertTrue(h.model(latest!!.room).isHost); assertEquals(BotLevel.GREEDY, model.seats[2].bot)
        guest.leave(g.id).join(); assertFalse(g.model(latest!!.room).isHost)
        sub.close()
    }
    @Test fun `stub host semantics still follow copied seat identity`() {
        val sample = Lobby.sample(); assertTrue(sample.isHost); assertFalse(sample.copy(me = 1).isHost)
        assertTrue(sample.copy(me = 1).toggleReady().seats[1].ready.not())
    }

    @Test
    fun `online seats map rooms to engine players, me, names and host bots`() {
        val room = wgbg.net.Room("ROVER-42", "h", wgbg.engine.GameConfig(numPlayers = 4), 7, listOf(
            wgbg.net.Seat("h", "Mauro", ready = true), wgbg.net.Seat(null, "", null, open = true),
            wgbg.net.Seat(null, "", "EASY", open = false), wgbg.net.Seat("g", "Erin", ready = true)))
        val guest = OnlineSeats(room, "g")
        org.junit.jupiter.api.Assertions.assertEquals(listOf(0, 2, 3), guest.players)   // the empty open seat doesn't play
        org.junit.jupiter.api.Assertions.assertEquals(2, guest.me)
        org.junit.jupiter.api.Assertions.assertEquals(3, guest.config.numPlayers)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("Mauro", "Blue", "Erin"), guest.names)  // your own seat shows your name
        org.junit.jupiter.api.Assertions.assertEquals(BotLevel.EASY, guest.bot(1))
        org.junit.jupiter.api.Assertions.assertNull(guest.bot(0)); org.junit.jupiter.api.Assertions.assertNull(guest.bot(2))
        org.junit.jupiter.api.Assertions.assertEquals(3, guest.seatOf(2))
        org.junit.jupiter.api.Assertions.assertFalse(guest.isHost)
        val host = OnlineSeats(room, "h")
        org.junit.jupiter.api.Assertions.assertEquals(0, host.me); org.junit.jupiter.api.Assertions.assertTrue(host.isHost)
        org.junit.jupiter.api.Assertions.assertEquals(-1, OnlineSeats(room, "stranger").me)
    }

    @Test
    fun `joining by code takes the first open seat, and says so when the room is full or missing`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withSeat(1, SeatKind.FRIEND).withSeat(2, SeatKind.FRIEND)
        val host = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "ROVER-42").join()
        val erin = joinFirstOpenSeat(InMemoryTransport("e", store), GameSetup(name = "Erin"), "ROVER-42").join()
        val sam = joinFirstOpenSeat(InMemoryTransport("s", store), GameSetup(name = "Sam"), "ROVER-42").join()
        val room = sam.client.view().room!!
        assertEquals(listOf("h", "e", "s"), room.seats.take(3).map { it.uid })
        val full = runCatching { joinFirstOpenSeat(InMemoryTransport("x", store), GameSetup(name = "Max"), "ROVER-42").join() }
        assertEquals("That room is full or has started", onlineProblem(full.exceptionOrNull()!!))
        val missing = runCatching { joinFirstOpenSeat(InMemoryTransport("y", store), GameSetup(name = "Ann"), "OTTER-11").join() }
        assertTrue(missing.isFailure)
        listOf(host, erin, sam).forEach { it.close() }
    }

    @Test
    fun `online needs a real name, not the default You`() {
        assertFalse(onlineName("You")); assertFalse(onlineName(" you ")); assertFalse(onlineName("  "))
        assertTrue(onlineName("Erin"))
    }

    @Test
    fun `online problems read as short sentences`() {
        assertEquals("No room with that code", onlineProblem(java.util.concurrent.CompletionException(IllegalStateException("Unknown room"))))
        assertEquals("The server said no (permission denied)", onlineProblem(RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions.")))
        assertEquals("Can't reach the server. Check the connection.", onlineProblem(java.io.IOException("x")))
    }

    @Test
    fun `the game says so when the server is out of reach`() {
        assertNull(connectionLabel(reconnecting = false, pending = 0, stuck = false))
        assertNull(connectionLabel(reconnecting = false, pending = 1, stuck = false))   // a normal, quick confirm
        assertEquals("Not synced: 2 moves waiting for the server", connectionLabel(false, 2, stuck = true))
        assertEquals("Reconnecting…", connectionLabel(reconnecting = true, pending = 0, stuck = false))
    }

    @Test
    fun `a seated player gets back into a started game after the app was closed, by rejoin or by typing the code`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro").withSeat(1, SeatKind.FRIEND)
        val host = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "TIDE-94").join()
        val guest = joinFirstOpenSeat(InMemoryTransport("c", store), GameSetup(name = "Crest"), "TIDE-94").join()
        val seats = host.client.view().room!!.seats
        host.transport.editLobby("TIDE-94", seats.map { it.copy(ready = true) }, seats).join()
        host.transport.start("TIDE-94").join()
        guest.close()                                            // the app was killed
        val back = LobbySession.rejoin(InMemoryTransport("c", store), GameSetup(name = "Crest"), "TIDE-94").join()
        assertEquals(Status.playing, back.client.view().room!!.status)
        assertNotNull(back.client.view().state)
        back.close()
        val typed = joinFirstOpenSeat(InMemoryTransport("c", store), GameSetup(name = "Crest"), "TIDE-94").join()   // typed the code again
        assertEquals("c", typed.client.view().room!!.seats[typed.client.view().room!!.seats.indexOfFirst { it.uid == "c" }].uid)
        val stranger = runCatching { LobbySession.rejoin(InMemoryTransport("x", store), GameSetup(name = "Max"), "TIDE-94").join() }
        assertEquals("That room is full or has started", onlineProblem(stranger.exceptionOrNull()!!))
        listOf(host, typed).forEach { it.close() }
    }

    @Test
    fun `hosting picks another code when the first ones are taken by old rooms, and gives up after its tries`() {
        val store = MemoryStore()
        val setup = GameSetup(name = "Mauro")
        for (c in listOf("ROVER-42", "HARBOR-43")) LobbySession.host(InMemoryTransport("old", store), setup, 1, code = c).join().close()
        val codes = ArrayDeque(listOf("HARBOR-43", "CEDAR-44"))
        val s = LobbySession.host(InMemoryTransport("h", store), setup, 5, code = "ROVER-42") { codes.removeFirst() }.join()
        assertEquals("CEDAR-44", s.id)
        s.close()
        val stuck = runCatching { LobbySession.host(InMemoryTransport("h2", store), setup, 5, code = "ROVER-42", attempts = 2) { "HARBOR-43" }.join() }
        assertTrue(stuck.isFailure, "two taken codes and two tries: fails")
    }

    @Test fun `local lobby numbers a name that is already seated`() {
        val l = Lobby.host(GameSetup(name = "Erin").withSeat(1, SeatKind.FRIEND).withSeat(2, SeatKind.FRIEND), code = "ROVER-42")
            .join("Erin")!!.join("erin")!!
        assertEquals(listOf("Erin", "Erin 2", "erin 3"), l.seats.take(3).map { it.name })
    }
}
