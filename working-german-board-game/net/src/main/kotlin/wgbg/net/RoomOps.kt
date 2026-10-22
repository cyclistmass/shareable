package wgbg.net

/**
 * Every change a player makes to a room, as a pure function from the room before to the room after. [uid] is the player
 * making it. Both transports apply these, so the in-memory one the tests use writes exactly what Firestore gets, and
 * the app's real writes can be replayed against the server rules (firebase/rules-test/app-writes.json).
 * Each throws (require) where the rules would refuse.
 */
object RoomOps {
    fun join(r: Room, uid: String, google: Boolean, seat: Int, name: String): Room {
        require(r.status == Status.lobby && seat in 1..3 && name.isNotBlank())
        require(r.seats.none { it.uid == uid } && r.seats[seat].uid == null && r.seats[seat].open)
        val taken = r.seats.filter { it.uid != null }.map { it.name }
        return r.withSeat(seat, r.seats[seat].copy(uid = uid, name = uniqueSeatName(name, taken), open = false, ready = false, google = google))
    }

    /** In the lobby the seat reopens; in a running game a bot takes it over (the host's phone plays it). */
    fun leave(r: Room, uid: String): Room {
        require((r.status == Status.lobby || r.status == Status.playing) && uid != r.host)
        val i = r.seats.indexOfFirst { it.uid == uid }; require(i > 0)
        val s = r.seats[i]
        return r.withSeat(i, if (r.status == Status.lobby) s.copy(uid = null, name = "", ready = false, open = true, google = false)
            else s.copy(uid = null, name = "", ready = false, open = false, bot = s.bot ?: "GREEDY", google = false))
    }

    /** The host sets any seat but its own owner; anyone else only their own seat's ready. */
    fun editLobby(r: Room, uid: String, seats: List<Seat>, expectedSeats: List<Seat>?): Room {
        require(r.status == Status.lobby && seats.size == 4)
        require(expectedSeats == null || expectedSeats == r.seats) { "Lobby changed; try again" }
        require(seats[0].uid == r.host)
        require(uid == r.host || seats.indices.all { i -> seats[i] == r.seats[i] ||
            (r.seats[i].uid == uid && seats[i].copy(ready = r.seats[i].ready) == r.seats[i]) })
        return r.copy(seats = seats)
    }

    fun start(r: Room, uid: String): Room {
        require(r.host == uid && r.status == Status.lobby && r.seats.all { it.uid == null || it.ready })
        return r.copy(config = r.config.copy(numPlayers = r.players.size), status = Status.playing)
    }

    fun finish(r: Room, uid: String): Room {
        require(r.host == uid && r.status == Status.playing)
        return r.copy(status = Status.done)
    }

    fun invite(r: Room, uid: String, uids: List<String>): Room {
        require(r.host == uid && r.status == Status.lobby && uids.size <= 3 && uid !in uids)
        return r.copy(invited = uids)
    }

    fun setNext(r: Room, uid: String, next: String): Room {
        require(r.host == uid && r.status == Status.done && r.next == null)
        return r.copy(next = next)
    }

    private fun Room.withSeat(i: Int, s: Seat) = copy(seats = seats.toMutableList().also { it[i] = s })
}
