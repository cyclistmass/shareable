package wgbg.net

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import wgbg.engine.*
import java.util.concurrent.Executors

class TransportTest {
    private fun room() = Room("ROOM", "host", GameConfig(numPlayers = 2, maxTurns = 30), 42,
        listOf(Seat("host", "Host", ready = true, open = false), Seat(), Seat(open = false), Seat(open = false)))
    @Test fun lifecycle() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val p = InMemoryTransport("guest", store)
        h.create(room()).join(); assertEquals(1, p.listOpen().join().size)
        p.join("ROOM", 1, "Guest").join()
        assertThrows(Exception::class.java) { InMemoryTransport("other", store).join("ROOM", 1, "Other").join() }
        assertThrows(Exception::class.java) { p.start("ROOM").join() }
        p.leave("ROOM").join(); p.join("ROOM", 1, "Guest").join()
        val r = p.listOpen().join().single(); p.editLobby("ROOM", r.seats.map { if (it.uid == "guest") it.copy(ready = true) else it }).join()
        h.start("ROOM").join(); assertTrue(h.listOpen().join().isEmpty())
        assertThrows(Exception::class.java) { h.leave("ROOM").join() }   // the host can't walk out of their own game
        // A guest quitting a running game hands the seat to a bot the host's phone plays, as on Firestore.
        p.leave("ROOM").join()
        var seat: Seat? = null; h.observe("ROOM", { seat = it.room.seats[1] }) { throw it }.close()
        assertEquals(Seat(null, "", "GREEDY", open = false, ready = false), seat)
        assertThrows(Exception::class.java) { p.leave("ROOM").join() }   // and only once
    }
    @Test fun codec() {
        for (kind in ActionType.entries + ChivalryActionType.entries) for (payload in listOf(0L, 1L, Action.PAYLOAD_MASK)) {
            val a = Action.of(kind, payload); assertEquals(a, MoveCodec.decode(MoveCodec.encode(a)))
        }
        for (bad in listOf("", "2:ROLL:0", "1:UNKNOWN:0", "1:ROLL:-1", "1:ROLL:+0", "1:ROLL:00", "1:ROLL:9999999999999999999", "1:ROLL:0:0")) assertNull(MoveCodec.decode(bad))
    }
    @Test fun rejectionAndRace() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val p = InMemoryTransport("guest", store)
        h.create(room().copy(seats = room().seats.toMutableList().also { it[1] = Seat("guest", "Guest", ready = true, open = false) })).join(); h.start("ROOM").join()
        val d = SyncDriver(); h.observe("ROOM", d::accept) { throw it }
        val hash = d.state!!.stateHash()
        assertThrows(Exception::class.java) { p.submit("ROOM", 0, 1, MoveCodec.encode(Action.buildSettlement(0))).join() }
        assertThrows(Exception::class.java) { h.submit("ROOM", 0, 0, "garbage").join() }
        assertThrows(Exception::class.java) { h.submit("ROOM", 0, 0, MoveCodec.encode(Action.END_TURN)).join() }
        assertEquals(hash, d.state!!.stateHash())
        val action = MoveCodec.encode(Rules.legalActions(d.state!!).first())
        val pool = Executors.newFixedThreadPool(2)
        try { val results = (0..1).map { pool.submit<Boolean> { runCatching { h.submit("ROOM", 0, 0, action).join() }.isSuccess } }; assertEquals(1, results.count { it.get() }) } finally { pool.shutdownNow() }
    }
    @Test fun hostileLog() {
        val r = room().copy(status = Status.playing, seats = room().seats.toMutableList().also { it[1] = Seat("guest", "Guest") })
        val a = SyncDriver(); val b = SyncDriver(); val initial = Game(r.config, r.seed).state.stateHash()
        val log = listOf(Move(0, 0, "host", "garbage"), Move(1, 1, "guest", MoveCodec.encode(Action.END_TURN)), Move(2, 0, "intruder", MoveCodec.encode(Action.ROLL)))
        a.accept(Snapshot(r, log.reversed())); b.accept(Snapshot(r, log)); assertEquals(initial, a.state!!.stateHash()); assertEquals(a.state!!.stateHash(), b.state!!.stateHash()); assertEquals(listOf(0L,1L,2L), a.rejected)
    }
    @Test fun `stale edits preserve joins and host alone can finish`() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val p = InMemoryTransport("guest", store)
        h.create(room()).join(); val before = h.listOpen().join().single()
        p.join("ROOM", 1, "Guest").join()
        assertThrows(Exception::class.java) { h.editLobby("ROOM", before.seats, before.seats).join() }
        val joined = h.listOpen().join().single(); assertEquals("guest", joined.seats[1].uid)
        h.editLobby("ROOM", joined.seats.map { if (it.uid != null) it.copy(ready = true) else it }).join()
        h.start("ROOM").join(); assertThrows(Exception::class.java) { p.finish("ROOM").join() }
        h.finish("ROOM").join(); assertThrows(Exception::class.java) { h.submit("ROOM", 0, 0, MoveCodec.encode(Action.ROLL)).join() }
    }
    @Test fun `gaps pause replay and corrected history rebuilds`() {
        val r = room().copy(status = Status.playing, seats = room().seats.toMutableList().also { it[1] = Seat("guest", "Guest") })
        val game = Game(r.config, r.seed); val first = game.legalActions().first(); game.apply(first)
        val second = game.legalActions().first(); game.apply(second)
        val m0 = Move(0, 0, "host", MoveCodec.encode(first)); val m1 = Move(1, 0, "host", MoveCodec.encode(second))
        val d = SyncDriver(); d.accept(Snapshot(r, listOf(m1))); assertEquals(Game(r.config,r.seed).state.stateHash(),d.state!!.stateHash())
        d.accept(Snapshot(r, listOf(m0,m1))); assertEquals(game.state.stateHash(), d.state!!.stateHash())
        d.accept(Snapshot(r, listOf(m0.copy(action="garbage"),m1))); assertEquals(2,d.rejected.size)
        d.accept(Snapshot(r, listOf(m0,m1))); assertEquals(game.state.stateHash(), d.state!!.stateHash()); assertTrue(d.rejected.isEmpty())
    }
    @Test fun `sparse room seats map to contiguous engine players and host submits bots`() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store)
        val r = room().copy(seats = room().seats.toMutableList().also { it[3] = Seat(bot="EASY",open=false) })
        h.create(r).join(); h.start(r.id).join(); val d = SyncDriver(); val sub = h.observe(r.id,d::accept){throw it}
        var seq = 0L
        while (d.state!!.actingPlayer == 0) h.submit(r.id,seq++,0,MoveCodec.encode(Rules.legalActions(d.state!!).first())).join()
        assertThrows(Exception::class.java) { h.submit(r.id,seq,1,MoveCodec.encode(Rules.legalActions(d.state!!).first())).join() }
        h.submit(r.id,seq,3,MoveCodec.encode(Rules.legalActions(d.state!!).first())).join(); sub.close()
    }
    @Test fun `#114 the offerer withdraws an open offer out of turn, and nobody else can`() {
        val store = MemoryStore(); val h = InMemoryTransport("host", store); val p = InMemoryTransport("guest", store)
        h.create(room().copy(seats = room().seats.toMutableList().also { it[1] = Seat("guest", "Guest", ready = true, open = false) })).join(); h.start("ROOM").join()
        val d = SyncDriver(); val sub = h.observe("ROOM", d::accept) { throw it }
        var seq = 0L
        fun s() = d.state!!
        fun by(seat: Int) = if (seat == 0) h else p
        while (!(s().phase == Phase.PLAY_TURN && s().hasRolled && s().currentPlayer == 0 && s().freeRoads == 0)) {
            val who = s().actingPlayer
            by(who).submit("ROOM", seq++, who, MoveCodec.encode(Rules.legalActions(s()).first())).join()
        }
        val ask = Action.offerTrade(IntArray(CARD_TYPES), IntArray(CARD_TYPES).also { it[Resource.WHEAT] = 1 })
        h.submit("ROOM", seq++, 0, MoveCodec.encode(ask)).join()
        assertEquals(Phase.DECIDE_TRADE, s().phase); assertEquals(1, s().actingPlayer)
        val cancel = MoveCodec.encode(Action.CANCEL_TRADE)
        assertThrows(Exception::class.java) { p.submit("ROOM", seq, 1, cancel).join() }   // not the guest's offer
        h.submit("ROOM", seq++, 0, cancel).join()                                          // the host's, while the guest is up
        assertEquals(Phase.PLAY_TURN, s().phase); assertEquals(0, s().actingPlayer)
        // A phone replaying the log agrees.
        val replay = SyncDriver(); h.observe("ROOM", replay::accept) { throw it }.close()
        assertEquals(s().stateHash(), replay.state!!.stateHash())
        sub.close()
    }
    @Test fun `online deck reconstructs after reconnect and explicit dice converge`() {
        val store = MemoryStore(); val h = InMemoryTransport("host",store)
        val r = room().copy(config = GameConfig(numPlayers=2,maxTurns=80), decks=1,
            seats=room().seats.toMutableList().also { it[3]=Seat(bot="EASY",open=false) })
        h.create(r).join(); h.start(r.id).join(); val d=SyncDriver(); var latest: Snapshot?=null
        val sub=h.observe(r.id,{latest=it;d.accept(it)}){throw it}; var seq=0L; var rolls=0
        val drawn=mutableListOf<Int>()
        while(rolls<65) {
            val s=d.state!!; val legal=Rules.legalActions(s)
            val a=if(Action.ROLL in legal) {
                val restored=SyncDriver();restored.accept(latest!!)
                val roll=restored.nextRoll(1); val card=DiceDeck.cardOf(roll.a,roll.b)
                assertTrue(DiceDeck.leftInStack(drawn,1)[card]>0);drawn.add(card);rolls++;roll
            } else if(Action.END_TURN in legal) Action.END_TURN else legal.first()
            h.submit(r.id,seq++,latest!!.room.players[s.actingPlayer],MoveCodec.encode(a)).join()
        }
        val replay=SyncDriver();replay.accept(latest!!);assertEquals(d.state!!.stateHash(),replay.state!!.stateHash());sub.close()
    }
    @Test fun `300 random games converge after every move`() {
        var actions = 0
        repeat(300) { seed ->
            val store = MemoryStore(); val h = InMemoryTransport("host", store); val p = InMemoryTransport("guest", store)
            val chivalry = seed % 2 == 1
            val count = if (chivalry) 3 + seed % 3 % 2 else 2 + seed % 3
            val r = room().copy(seed = seed.toLong(), config = GameConfig(expansion = if (chivalry) Expansion.CHIVALRY else Expansion.BASE,
                numPlayers = count, maxTurns = 120, generateTradeOffers = seed % 3 == 0), seats = room().seats.toMutableList().also {
                    it[1] = Seat("guest", "Guest", ready = true)
                    for (i in 2 until count) it[i] = Seat(bot = "GREEDY", open = false)
                })
            h.create(r).join(); h.start(r.id).join(); val a = SyncDriver(); val b = SyncDriver()
            val sa = h.observe(r.id, a::accept) { throw it }; val sb = p.observe(r.id, b::accept) { throw it }
            val random = Rng(seed.toLong()); var seq = 0L
            while (!a.state!!.isOver) {
                val state = a.state!!; val legal = Rules.legalActions(state); val move = legal[random.nextInt(legal.size)]
                val seat = state.actingPlayer; (if (seat == 1) p else h).submit(r.id, seq++, seat, MoveCodec.encode(move)).join()
                assertEquals(a.state!!.stateHash(), b.state!!.stateHash()); actions++
            }
            sa.close(); sb.close()
        }
        println("transport fuzz: 300 games, $actions moves")
    }
}
