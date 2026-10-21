package wgbg.net

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import wgbg.engine.*

/** #72: people played with, in-app invites and the online rematch. */
class FriendsTest {
    private val day = 24L * 60 * 60 * 1000
    private fun room(vararg seats: Seat) = Room("ROOM-1", "host", GameConfig(numPlayers = 4), 42, seats.toList())
    private val host = Seat("host", "Host", open = false, ready = true, google = true)

    @Test fun metListsOnlyOtherPeopleSignedInWithGoogle() {
        val r = room(host, Seat("erin", "Erin", open = false, google = true), Seat("guest", "Guest", open = false), Seat(null, "", "GREEDY", open = false))
        assertEquals(listOf("erin"), Friends.met(r, "host", emptyMap(), 5).map { it.uid })
        assertEquals(listOf("host"), Friends.met(r, "erin", emptyMap(), 5).map { it.uid })
    }

    @Test fun metCountsGamesAndKeepsTheStar() {
        val r = room(host, Seat("erin", "Erin R", open = false, google = true), Seat(), Seat())
        val f = Friends.met(r, "host", mapOf("erin" to Friend("erin", "Erin", 1, games = 3, starred = true)), 9).single()
        assertEquals(Friend("erin", "Erin R", 9, games = 4, starred = true), f)
    }

    @Test fun shownPutsStarsFirstAndDropsOldUnstarred() {
        val now = 200 * day
        val list = listOf(Friend("a", "A", now - day), Friend("b", "B", now - 100 * day), Friend("c", "C", now - 100 * day, starred = true),
            Friend("d", "D", now - 2 * day))
        assertEquals(listOf("c", "a", "d"), Friends.shown(list, now).map { it.uid })
    }

    @Test fun rematchOpensThePeoplesSeatsKeepsBotsAndInvitesThem() {
        val old = room(host, Seat("erin", "Erin", open = false, ready = true, google = true), Seat(null, "", "STRONG", open = false), Seat("bo", "Bo", open = false, ready = true))
            .copy(status = Status.done, decks = 2)
        val r = Friends.rematch(old, "ROOM-2", 7)
        assertEquals(listOf("erin", "bo"), r.invited)
        assertEquals("ROOM-1", r.rematchOf)
        assertEquals(Status.lobby, r.status)
        assertEquals(listOf("host", null, null, null), r.seats.map { it.uid })
        assertEquals(listOf(true, false, true), r.seats.drop(1).map { it.open })
        assertEquals("STRONG", r.seats[2].bot)
        assertEquals(2, r.decks)
    }

    @Test fun invitesReachTheInvitedOnlyWhileInTheLobby() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store, google = true); val erin = InMemoryTransport("erin", store, google = true)
        val other = InMemoryTransport("other", store)
        h.create(room(host, Seat(), Seat(), Seat())).join()
        var seen = emptyList<String>(); var otherSeen = emptyList<String>()
        erin.observeInvites({ rooms -> seen = rooms.map { it.id } }) { throw it }
        other.observeInvites({ rooms -> otherSeen = rooms.map { it.id } }) { throw it }
        assertThrows(Exception::class.java) { erin.invite("ROOM-1", listOf("other")).join() }
        h.invite("ROOM-1", listOf("erin")).join()
        assertEquals(listOf("ROOM-1"), seen); assertEquals(emptyList<String>(), otherSeen)
        erin.join("ROOM-1", 1, "Erin").join()
        assertTrue(store.rooms["ROOM-1"]!!.room.seats[1].google)
        val r = store.rooms["ROOM-1"]!!.room
        erin.editLobby("ROOM-1", r.seats.map { if (it.uid == "erin") it.copy(ready = true) else it }).join()
        h.start("ROOM-1").join()
        assertEquals(emptyList<String>(), seen)
    }

    @Test fun onlyTheHostLinksAFinishedRoomToItsRematch() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val erin = InMemoryTransport("erin", store)
        h.create(room(host, Seat("erin", "Erin", open = false, ready = true), Seat(), Seat())).join(); h.start("ROOM-1").join()
        assertThrows(Exception::class.java) { h.setNext("ROOM-1", "ROOM-2").join() }   // still playing
        h.finish("ROOM-1").join()
        assertThrows(Exception::class.java) { erin.setNext("ROOM-1", "ROOM-2").join() }
        h.setNext("ROOM-1", "ROOM-2").join()
        assertEquals("ROOM-2", store.rooms["ROOM-1"]!!.room.next)
        assertThrows(Exception::class.java) { h.setNext("ROOM-1", "ROOM-3").join() }
    }

    @Test fun friendsAreEachPlayersOwn() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val erin = InMemoryTransport("erin", store)
        h.putFriend(Friend("erin", "Erin", 1)).join()
        assertEquals(listOf("erin"), h.friends().join().map { it.uid })
        assertEquals(emptyList<Friend>(), erin.friends().join())
        assertThrows(Exception::class.java) { h.putFriend(Friend("host", "Me", 1)).join() }
        h.forgetFriend("erin").join()
        assertEquals(emptyList<Friend>(), h.friends().join())
    }
}
