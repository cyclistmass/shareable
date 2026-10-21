package wgbg.net

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import wgbg.engine.*
import java.util.concurrent.CompletableFuture

class GameClientTest {
    private val r = Room("ROOM","host",GameConfig(numPlayers=2,maxTurns=40),9,
        listOf(Seat("host","Host",ready=true),Seat("guest","Guest",ready=true),Seat(open=false),Seat(open=false)))
    private class Delayed(val server: GameTransport) : GameTransport by server {
        data class Write(val seq: Long,val seat: Int,val action: String,val future: CompletableFuture<Unit>)
        val writes=mutableListOf<Write>(); val cursors=mutableListOf<Long>(); var offline=false
        var callback: ((MoveUpdate)->Unit)?=null; var latest: MoveUpdate?=null
        override fun submit(id:String,seq:Long,seat:Int,action:String):CompletableFuture<Unit> {
            val f=CompletableFuture<Unit>();writes.add(Write(seq,seat,action,f));return f
        }
        override fun observeMoves(id:String,afterSeq:Long,onUpdate:(MoveUpdate)->Unit,onError:(Throwable)->Unit):Subscription {
            cursors.add(afterSeq);callback=onUpdate
            return server.observeMoves(id,afterSeq,{latest=it;onUpdate(it.copy(fromCache=offline))},onError)
        }
        fun commit() {val w=writes.removeAt(0);server.submit("ROOM",w.seq,w.seat,w.action).whenComplete{_,e->if(e==null)w.future.complete(Unit)else w.future.completeExceptionally(e)}}
        fun reject() {writes.removeAt(0).future.completeExceptionally(IllegalStateException("Write rejected"))}
        fun metadata(cache:Boolean){offline=cache;callback!!(latest!!.copy(moves=emptyList(),fromCache=cache))}
    }
    private fun setup():Triple<InMemoryTransport,Delayed,GameClient> {
        val server=InMemoryTransport("host");server.create(r).join();server.start(r.id).join()
        val delayed=Delayed(server);val client=GameClient(delayed,r.id,"host");client.start()
        return Triple(server,delayed,client)
    }
    @Test fun `local state renders before write ack and echo does not apply twice`() {
        val (_,t,c)=setup();val before=c.view().state!!.stateHash();var observed=false
        val action=Rules.legalActions(c.view().state!!).first()
        c.watch {if(it.pendingCount==1){assertTrue(t.writes.isEmpty());observed=true}}
        val result=c.act(0,action);assertTrue(observed);assertFalse(result.isDone)
        val predicted=c.view().state!!.stateHash();assertNotEquals(before,predicted)
        t.commit();assertTrue(result.isDone);assertEquals(predicted,c.view().state!!.stateHash());assertEquals(0,c.view().pendingCount)
        t.metadata(false);assertEquals(predicted,c.view().state!!.stateHash());c.close()
    }
    @Test fun `view actions replay to the shown state, and a rollback drops the predictions`() {
        val (_,t,c)=setup()
        fun replay(v: ClientView) = Rules.newGame(r.config.copy(numPlayers = 2), r.seed).also { s -> v.actions.forEach { Rules.applyChecked(s, it) } }.stateHash()
        val a1=Rules.legalActions(c.view().state!!).first(); c.act(0,a1); t.commit()
        val a2=Rules.legalActions(c.view().state!!).first(); c.act(c.view().room!!.players[c.view().state!!.actingPlayer],a2)
        val v=c.view(); assertEquals(listOf(a1,a2),v.actions); assertEquals(v.state!!.stateHash(),replay(v))
        t.reject()
        val back=c.view(); assertEquals(listOf(a1),back.actions); assertEquals(back.state!!.stateHash(),replay(back)); c.close()
    }
    @Test fun `the rematch link on a finished room keeps its history (#72)`() {
        // Firestore sends only new moves, so a room doc change that looks like another game would leave nothing to replay:
        // the guest's board went back to the start and its "Join rematch" never showed.
        val (_,t,c)=setup()
        val a1=Rules.legalActions(c.view().state!!).first(); c.act(0,a1); t.commit()
        val before=c.view(); assertEquals(listOf(a1),before.actions)
        t.callback!!(t.latest!!.copy(room=t.latest!!.room.copy(status=Status.done,next="ROOM-2"),moves=emptyList()))
        val after=c.view(); assertEquals("ROOM-2",after.room!!.next)
        assertEquals(listOf(a1),after.actions); assertEquals(before.state!!.stateHash(),after.state!!.stateHash()); c.close()
    }
    @Test fun `denial rolls back to confirmed state and restarts from last applied sequence`() {
        val (_,t,c)=setup();val before=c.view().state!!.stateHash()
        val result=c.act(0,Rules.legalActions(c.view().state!!).first());t.reject()
        assertTrue(result.isCompletedExceptionally);assertEquals(before,c.view().state!!.stateHash());assertEquals(0,c.view().pendingCount)
        assertEquals(listOf(-1L,-1L),t.cursors);c.close()
    }
    @Test fun `multiple legal local actions render without waiting for network`() {
        val (_,t,c)=setup()
        c.act(0,Rules.legalActions(c.view().state!!).first())
        c.act(0,Rules.legalActions(c.view().state!!).first())
        assertEquals(2,c.view().pendingCount);val hash=c.view().state!!.stateHash()
        t.commit();assertEquals(hash,c.view().state!!.stateHash());t.commit();assertEquals(hash,c.view().state!!.stateHash());c.close()
    }
    @Test fun `observer initiated next action cannot overtake its predecessor write`() {
        val (_,t,c)=setup();var once=false
        val observer=c.watch { v ->
            if(!once && v.pendingCount==1) {once=true;c.act(0,Rules.legalActions(v.state!!).first())}
        }
        c.act(0,Rules.legalActions(c.view().state!!).first())
        assertEquals(listOf(0L,1L),t.writes.map{it.seq})
        val predicted=c.view().state!!.stateHash();t.commit();t.commit()
        assertEquals(predicted,c.view().state!!.stateHash());observer.close();c.close()
    }
    @Test fun `concurrent move zero loser rejects and reconciles to winner`() {
        val (server,a,ca)=setup();val b=Delayed(server);val cb=GameClient(b,r.id,"host");cb.start()
        val legal=Rules.legalActions(ca.view().state!!);val fa=ca.act(0,legal[0]);val fb=cb.act(0,legal[1])
        a.commit();b.commit();assertFalse(fa.isCompletedExceptionally);assertTrue(fb.isCompletedExceptionally)
        assertEquals(ca.view().state!!.stateHash(),cb.view().state!!.stateHash());assertEquals(0,cb.view().pendingCount);ca.close();cb.close()
    }
    @Test fun `cached state stays playable and resume requests only unapplied suffix`() {
        val (_,t,c)=setup();c.act(0,Rules.legalActions(c.view().state!!).first());t.commit()
        t.metadata(true);assertTrue(c.view().reconnecting)
        c.act(0,Rules.legalActions(c.view().state!!).first());assertEquals(1,c.view().pendingCount)
        val hash=c.view().state!!.stateHash();c.resume();assertEquals(0L,t.cursors.last());assertEquals(hash,c.view().state!!.stateHash())
        t.commit();t.metadata(false);assertFalse(c.view().reconnecting);assertEquals(hash,c.view().state!!.stateHash());c.close()
    }
    @Test fun `screen observer detach does not detach room listener and closed client cannot act`() {
        val (_,t,c)=setup();val ui=c.watch{};ui.close();assertEquals(1,t.cursors.size)
        c.act(0,Rules.legalActions(c.view().state!!).first());t.commit();assertEquals(0L,c.view().confirmedSeq)
        c.close();assertThrows(Exception::class.java){c.act(0,Action.ROLL)}
    }
    @Test fun `incremental driver buffers gaps ignores duplicates and rejects garbage`() {
        val room=r.copy(status=Status.playing);val d=SyncDriver();val g=Game(room.config,room.seed)
        val first=g.legalActions().first();g.apply(first);val second=g.legalActions().first();g.apply(second)
        val m0=Move(0,0,"host",MoveCodec.encode(first));val m1=Move(1,0,"host",MoveCodec.encode(second))
        d.acceptDelta(room,listOf(m1));assertEquals(-1L,d.confirmedSeq)
        d.acceptDelta(room,listOf(m0));assertEquals(g.state.stateHash(),d.state!!.stateHash());d.acceptDelta(room,listOf(m0,m1));assertEquals(g.state.stateHash(),d.state!!.stateHash())
        d.acceptDelta(room,listOf(Move(2,1,"guest","garbage")));assertEquals(g.state.stateHash(),d.state!!.stateHash());assertEquals(listOf(2L),d.rejected)
    }
    @Test fun `measure local apply with outstanding writes and initial replay`() {
        val samples=mutableListOf<Long>();var replayMs=0.0
        repeat(50) {
            val (_,t,c)=setup();c.act(0,Rules.legalActions(c.view().state!!).first());samples.add(c.localApplyNanos.single());t.commit();c.close()
        }
        val room=r.copy(status=Status.playing,config=r.config.copy(maxTurns=300))
        val game=Game(room.config,room.seed);val log=mutableListOf<Move>();val random=Rng(87)
        while(!game.state.isOver && log.size<1000) {
            val a=game.legalActions().let {it[random.nextInt(it.size)]};val seat=game.state.actingPlayer
            log.add(Move(log.size.toLong(),seat,room.seats[seat].uid!!,MoveCodec.encode(a)));game.apply(a)
        }
        repeat(10){SyncDriver().acceptDelta(room,log)}
        val begin=System.nanoTime();val d=SyncDriver();d.acceptDelta(room,log);replayMs=(System.nanoTime()-begin)/1e6
        assertEquals(game.state.stateHash(),d.state!!.stateHash())
        samples.sort();println("local apply n=50: median=${samples[25]/1e6} ms, p95=${samples[47]/1e6} ms, max=${samples.last()/1e6} ms; initial replay ${log.size} moves=$replayMs ms")
    }
    @Test fun `300 delayed optimistic random games converge after confirmation`() {
        var moves=0
        repeat(300) { seed ->
            val store=MemoryStore();val h=InMemoryTransport("host",store);val p=InMemoryTransport("guest",store)
            val expansion=if(seed%2==0) Expansion.BASE else Expansion.CITIES_AND_KNIGHTS
            val count=if(expansion==Expansion.BASE)2+seed%3 else 3+seed%2
            val room=r.copy(seed=seed.toLong(),config=GameConfig(expansion=expansion,numPlayers=count,maxTurns=50),
                seats=List(4){i->when(i){0->r.seats[0];1->r.seats[1];else->Seat(bot=if(i<count)"GREEDY" else null,open=false)}})
            h.create(room).join();h.start(room.id).join();val ht=Delayed(h);val pt=Delayed(p)
            val hc=GameClient(ht,room.id,"host");val pc=GameClient(pt,room.id,"guest");hc.start();pc.start()
            val random=Rng(seed.toLong())
            while(!hc.view().state!!.isOver) {
                val state=hc.view().state!!;val legal=Rules.legalActions(state);val action=legal[random.nextInt(legal.size)]
                val seat=state.actingPlayer;val client=if(seat==1)pc else hc;val other=if(seat==1)hc else pc;val transport=if(seat==1)pt else ht
                val old=other.view().state!!.stateHash();Rules.applyChecked(state,action)
                client.act(seat,action);assertEquals(state.stateHash(),client.view().state!!.stateHash());assertEquals(old,other.view().state!!.stateHash())
                transport.commit();assertEquals(hc.view().state!!.stateHash(),pc.view().state!!.stateHash());moves++
            }
            hc.close();pc.close()
        }
        println("optimistic fuzz: 300 games, $moves moves, local prediction checked before ack; clients matched after every ack")
    }
}
