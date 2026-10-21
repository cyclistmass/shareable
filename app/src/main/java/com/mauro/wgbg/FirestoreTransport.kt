package com.mauro.wgbg

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import java.util.concurrent.CompletableFuture
import wgbg.engine.*
import wgbg.net.*

private fun <T> Task<T>.future(): CompletableFuture<T> = CompletableFuture<T>().also { f ->
    addOnSuccessListener { f.complete(it) }; addOnFailureListener { f.completeExceptionally(it) }
}

/** No default Firebase app means a fully local transport; no configuration contents are inspected. */
object LobbyRepository {
    private val localStore = MemoryStore()
    private var transport: GameTransport? = null
    /**
     * Debug builds only (`--es firebase emu`): talk to the Firebase emulators on the dev PC (10.0.2.2 from an Android
     * emulator) instead of the live project, so two emulators can play online against our real firestore.rules without
     * deploying anything. Set at launch, before anything creates the transport: the splash, account and crash reporter
     * create it too, and the first one made is the one every caller gets.
     */
    @Volatile var emulatorHost: String? = null
    /**
     * Debug builds only (`--ez fakegoogle true`), and only against the emulators: this phone counts as signed in with
     * Google, so two-phone sims can reach the friends list, invites and rematch (#72) without real Google accounts.
     */
    @Volatile var fakeGoogle: Boolean = false
    /** The emulators' Firestore and Auth ports (debug `--ei fsport` / `--ei authport`: a second set beside another one). */
    @Volatile var emulatorPorts: Pair<Int, Int> = 8080 to 9099
    /** The emulators' demo project: no google-services.json needed, so any checkout and CI can play online. */
    const val EMULATOR_PROJECT = "demo-wgbg"
    internal val emulatorOptions: FirebaseOptions get() = FirebaseOptions.Builder().setProjectId(EMULATOR_PROJECT)
        .setApplicationId("1:000000000000:android:0000000000000000").setApiKey("demo-api-key-for-the-emulators").build()

    @Volatile private var cleaned = false
    /** Once per launch, off the main thread: this player's expired rooms ([Expired]). Failures only log. */
    fun cleanExpiredOnce(context: Context) {
        if (cleaned) return
        cleaned = true
        runCatching { create(context).cleanExpired() }.getOrNull()?.whenComplete { ids, e ->
            runCatching { if (e != null) android.util.Log.w("WGBG-ONLINE", "expired rooms not cleared", e)
                else if (ids.isNotEmpty()) android.util.Log.i("WGBG-ONLINE", "expired rooms cleared: ${ids.joinToString()}") }
        }
    }
    @Synchronized fun create(context: Context): GameTransport {
        transport?.let { return it }
        val emulatorHost = emulatorHost
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
            ?: emulatorHost?.let { FirebaseApp.initializeApp(context, emulatorOptions) }
        val result = if (app == null) InMemoryTransport("local-host", localStore) else {
            val db = FirebaseFirestore.getInstance(app)
            val auth = FirebaseAuth.getInstance(app)
            if (emulatorHost != null) {
                db.useEmulator(emulatorHost, emulatorPorts.first)
                auth.useEmulator(emulatorHost, emulatorPorts.second)
            } else db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build()).build()
            FirestoreTransport(auth, db, fakeGoogle = emulatorHost != null && fakeGoogle)
        }
        transport = result
        result.signIn() // warm anonymous authentication at entry, before a room is selected
        return result
    }
}

class FirestoreTransport(private val auth: FirebaseAuth, private val db: FirebaseFirestore,
                         val fakeGoogle: Boolean = false) : GameTransport {
    private var signingIn: CompletableFuture<String>? = null
    private val games get() = db.collection("games")
    private val uid get() = auth.currentUser?.uid ?: error("Sign in first")
    /** The account calls for Settings' Google sign-in (#83). */
    val account: GoogleAccount.Ops get() = GoogleAccount.FirebaseOps(auth, fakeGoogle)
    override val google: Boolean get() = account.isGoogle
    /** After a Google sign-in or sign-out: forget the cached sign-in so the next call uses the new user. */
    @Synchronized fun accountChanged() { signingIn = null }
    @Synchronized override fun signIn(): CompletableFuture<String> {
        signingIn?.takeUnless { it.isCompletedExceptionally }?.let { return it }
        val fresh = { auth.signInAnonymously().future().thenApply { it.user!!.uid } }
        // A remembered sign-in can be dead (its account deleted, or made against the debug emulators):
        // check its token first, and sign in afresh if the server refuses it.
        val saved = auth.currentUser
        val signedIn = if (saved == null) fresh() else saved.getIdToken(true).future() // true: ask the server, a cached token proves nothing
            .thenApply { CompletableFuture.completedFuture(saved.uid) }
            .exceptionally { auth.signOut(); fresh() }
            .thenCompose { it }
        // Outside the if: a first sign-in (no saved user, as on every fresh sim phone) must be linked too.
        val result = signedIn.thenCompose { uid -> if (fakeGoogle) linkFakeGoogle(uid) else CompletableFuture.completedFuture(uid) }
        return result.also { signingIn = it }
    }
    /**
     * Debug sims against the emulators only ([fakeGoogle]): link this guest to a made-up Google account, which only the
     * Auth emulator accepts (as a plain JSON token). The rules let a seat say "Google" only for a real Google sign-in,
     * so a guest merely counted as one would be refused.
     */
    private fun linkFakeGoogle(uid: String): CompletableFuture<String> {
        val user = auth.currentUser ?: return CompletableFuture.completedFuture(uid)
        if (user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }) return CompletableFuture.completedFuture(uid)
        val token = """{"sub":"fake-$uid","email":"${uid.lowercase()}@example.com","email_verified":true}"""
        return user.linkWithCredential(GoogleAuthProvider.getCredential(token, null)).future()
            .thenCompose { user.getIdToken(true).future() }.thenApply { uid }
    }
    override fun create(room: Room): CompletableFuture<Room> {
        require(room.host == uid)
        val ref = games.document(room.id)
        return db.runTransaction { t ->
            check(!t.get(ref).exists()) { "Room code already used" }
            t.set(ref, room.wire() + mapOf("createdAt" to FieldValue.serverTimestamp(), "lastMove" to "", "expireAt" to expiresIn(LOBBY_DAYS))); room
        }.future()
    }
    /** Adds one crash report to `crashes` (write-only for players; see firestore.rules). */
    /**
     * A report, then its screenshots (#128) as base64 JPEGs in `shots`, each pointing at the report. The report counts
     * as sent even if a shot is refused; the result says how many shots made it.
     */
    fun sendCrash(report: String, shots: List<String> = emptyList()): CompletableFuture<Int> = signIn().thenCompose { uid ->
        db.collection("crashes").add(mapOf(
            "uid" to uid, "report" to report.take(Feedback.UPLOAD_MAX), "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )).future().thenCompose { ref ->
            val sent = shots.mapIndexed { i, img ->
                db.collection("shots").add(mapOf("uid" to uid, "crash" to ref.id, "n" to i.toLong(), "image" to img,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())).future().handle { _, e -> if (e == null) 1 else 0 }
            }
            CompletableFuture.allOf(*sent.toTypedArray()).thenApply { sent.sumOf { it.join() } }
        }
    }

    override fun listOpen() = games.whereEqualTo("status", "lobby").get().future().thenApply { q -> q.documents.mapNotNull { runCatching { room(it.id, it.data!!) }.getOrNull() } }   // another version's rooms are left out, not an error
    /** [extra]: fields beside the room's own, such as a new expiry. */
    private fun change(id: String, extra: Map<String, Any> = emptyMap(), f: (Room) -> Room): CompletableFuture<Unit> {
        val ref = games.document(id)
        return db.runTransaction { t ->
            val old = room(id, t.get(ref).data ?: error("Unknown room")); val next = f(old)
            t.update(ref, next.wire() + extra); Unit
        }.future()
    }
    override fun join(id: String, seat: Int, name: String) = change(id) { r ->
        require(r.status == Status.lobby && seat in 1..3 && name.isNotBlank())
        require(r.seats.none { it.uid == uid } && r.seats[seat].uid == null && r.seats[seat].open)
        r.copy(seats = r.seats.toMutableList().also { it[seat] = it[seat].copy(uid = uid, name = uniqueSeatName(name, r.seats.filter { it.uid != null }.map { it.name }), open = false, ready = false, google = google) })
    }
    override fun leave(id: String) = change(id) { r ->
        require((r.status == Status.lobby || r.status == Status.playing) && uid != r.host)
        val i = r.seats.indexOfFirst { it.uid == uid }; require(i > 0)
        r.copy(seats = r.seats.toMutableList().also {
            // In the lobby the seat reopens; in a running game a bot takes it over (the host's phone plays it).
            it[i] = if (r.status == Status.lobby) it[i].copy(uid = null, name = "", ready = false, open = true, google = false)
                else it[i].copy(uid = null, name = "", ready = false, open = false, bot = it[i].bot ?: BotLevel.GREEDY.name, google = false)
        })
    }
    override fun editLobby(id: String, seats: List<Seat>, expectedSeats: List<Seat>?) = change(id) { r ->
        require(r.status == Status.lobby)
        require(expectedSeats == null || expectedSeats == r.seats) { "Lobby changed; try again" }
        r.copy(seats = seats)
    }
    override fun start(id: String) = change(id, mapOf("expireAt" to expiresIn(GAME_DAYS))) { r ->
        require(r.host == uid && r.status == Status.lobby && r.seats.all { it.uid == null || it.ready })
        r.copy(config = r.config.copy(numPlayers = r.players.size), status = Status.playing)
    }
    override fun submit(id: String, seq: Long, seat: Int, action: String): CompletableFuture<Unit> {
        require(seq in 0..9999999999L && seat in 0..3 && MoveCodec.decode(action) != null)
        // No reads, transaction, room-counter write, or local-log replay on the write path.
        return games.document(id).collection("moves").document(moveId(seq)).set(mapOf(
            "seq" to seq, "seat" to seat.toString(), "uid" to uid, "action" to action,
            "prev" to if (seq == 0L) "" else moveId(seq - 1), "expireAt" to expiresIn(GAME_DAYS)
        )).future().thenApply<Unit> { Unit }
    }
    override fun finish(id: String) = change(id, mapOf("expireAt" to expiresIn(DONE_DAYS))) { r ->
        require(r.host == uid && r.status == Status.playing); r.copy(status = Status.done)
    }
    override fun invite(id: String, uids: List<String>) = change(id) { r ->
        require(r.host == uid && r.status == Status.lobby && uids.size <= 3 && uid !in uids); r.copy(invited = uids)
    }
    override fun setNext(id: String, next: String) = change(id) { r ->
        require(r.host == uid && r.status == Status.done && r.next == null); r.copy(next = next)
    }
    override fun observeInvites(onRooms: (List<Room>) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val lock = Any()
        var closed = false
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        signIn().whenComplete { me, e ->
            synchronized(lock) {
                if (closed) return@whenComplete
                if (e != null) { onError(e); return@whenComplete }
                // Any state may come back (the rules let the invited read it): only rooms still taking players count.
                listener = games.whereArrayContains("invited", me).addSnapshotListener { q, failure ->
                    if (failure != null) onError(failure)
                    else onRooms(q!!.documents.mapNotNull { runCatching { room(it.id, it.data!!) }.getOrNull() }.filter { it.status == Status.lobby })
                }
            }
        }
        return Subscription { synchronized(lock) { closed = true; listener?.remove() } }
    }
    private fun met() = db.collection("users").document(uid).collection("met")
    override fun friends(): CompletableFuture<List<Friend>> = signIn().thenCompose { met().get().future() }.thenApply { q ->
        q.documents.mapNotNull { d -> runCatching {
            Friend(d.id, d.getString("name")!!, d.getTimestamp("lastPlayed")!!.toDate().time, d.getLong("games")!!.toInt(), d.getBoolean("starred")!!)
        }.getOrNull() }
    }
    override fun putFriend(f: Friend): CompletableFuture<Unit> = signIn().thenCompose {
        met().document(f.uid).set(mapOf("name" to f.name.take(80), "lastPlayed" to com.google.firebase.Timestamp(java.util.Date(f.lastPlayed)),
            "games" to f.games.toLong(), "starred" to f.starred)).future()
    }.thenApply { }
    override fun forgetFriend(uid: String): CompletableFuture<Unit> = signIn().thenCompose { met().document(uid).delete().future() }.thenApply { }
    /**
     * Only for a player already signed in: it never signs anyone in. The phone's clock picks the rooms; a clock that runs
     * ahead only gets a refusal from the rules (request.time), logged and tried again next launch.
     */
    override fun cleanExpired(max: Int): CompletableFuture<List<String>> {
        val me = auth.currentUser?.uid ?: return CompletableFuture.completedFuture(emptyList())
        return games.whereEqualTo("host", me).get().future().thenCompose { q ->
            val now = System.currentTimeMillis()
            val due = q.documents.mapNotNull { d -> d.getTimestamp("expireAt")?.toDate()?.time?.takeIf { it < now }?.let { d to it } }
            // A game still being played lives as long as its last move (Expired.latest): look that move up before deleting.
            val checked = due.map { (d, at) ->
                if (d.getString("status") != Status.playing.name) CompletableFuture.completedFuture<Pair<String, Long?>>(d.id to at)
                else d.reference.collection("moves").orderBy("seq", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(1)
                    .get().future().thenApply { m -> d.id to Expired.latest(at, m.documents.firstOrNull()?.getTimestamp("expireAt")?.toDate()?.time) }
            }
            CompletableFuture.allOf(*checked.toTypedArray()).thenCompose {
                val ids = Expired.pick(checked.map { it.join() }, now, max)
                ids.fold(CompletableFuture.completedFuture(Unit)) { done, id -> done.thenCompose { deleteRoom(id) } }.thenApply { ids }
            }
        }
    }
    /** Moves first, a page at a time, then the room: a move's delete rule reads its room, and a cleanup cut short resumes. */
    private fun deleteRoom(id: String): CompletableFuture<Unit> {
        val ref = games.document(id)
        fun page(): CompletableFuture<Unit> = ref.collection("moves").limit(100).get().future().thenCompose { q ->
            if (q.isEmpty) ref.delete().future().thenApply { }
            else CompletableFuture.allOf(*q.documents.map { it.reference.delete().future() }.toTypedArray()).thenCompose { page() }
        }
        return page()
    }
    override fun observe(id: String, onSnapshot: (Snapshot) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val log = java.util.TreeMap<Long, Move>()
        return observeMoves(id, -1, { update ->
            update.moves.forEach { log[it.seq] = it }
            onSnapshot(Snapshot(update.room, log.values.toList()))
        }, onError)
    }
    override fun observeMoves(id: String, afterSeq: Long, onUpdate: (MoveUpdate) -> Unit, onError: (Throwable) -> Unit): Subscription {
        val lock = Any()
        var current: Room? = null
        var cached = true
        var closed = false
        var last = afterSeq
        val waiting = java.util.TreeMap<Long, Move>()
        fun emit() {
            val r = current ?: return
            val delta = mutableListOf<Move>()
            while (true) { val m = waiting.remove(last + 1) ?: break; delta.add(m); last++ }
            if (!closed) onUpdate(MoveUpdate(r.copy(nextSeq = last + 1), delta, cached))
        }
        val ref = games.document(id)
        val a = ref.addSnapshotListener(MetadataChanges.INCLUDE) { doc, failure -> synchronized(lock) {
            if (closed) return@synchronized
            if (failure != null) onError(failure) else runCatching {
                current = room(id, doc?.data ?: error("Unknown room")); emit()
            }.onFailure(onError)
        } }
        val b = ref.collection("moves").orderBy("seq").whereGreaterThan("seq", afterSeq)
            .addSnapshotListener(MetadataChanges.INCLUDE) { docs, failure -> synchronized(lock) {
                if (closed) return@synchronized
                if (failure != null) onError(failure) else runCatching {
                    cached = docs!!.metadata.isFromCache
                    // Pending local echoes are predictions, not confirmation. INCLUDE supplies the
                    // metadata-only change when the same document becomes server-confirmed.
                    for (change in docs.getDocumentChanges(MetadataChanges.INCLUDE)) {
                        val d = change.document
                        if (change.type != DocumentChange.Type.REMOVED && !d.metadata.hasPendingWrites()) {
                            val m = Move(d.getLong("seq")!!, d.getString("seat")!!.toInt(), d.getString("uid")!!, d.getString("action")!!)
                            if (m.seq > last) waiting[m.seq] = m
                        }
                    }
                    emit()
                }.onFailure(onError)
            } }
        return Subscription { synchronized(lock) { closed = true; a.remove(); b.remove() } }
    }
}

/** Rooms and moves expire this many days on (`expireAt`; their host's app deletes them, [Expired], #72): a lobby nobody
 *  started, a game since its start (its moves each since themselves: [Expired.latest]), a finished game. */
internal const val LOBBY_DAYS = 1
internal const val GAME_DAYS = 30
internal const val DONE_DAYS = 7
private fun expiresIn(days: Int) = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() + days * 24L * 60 * 60 * 1000))

internal fun Room.wire(): Map<String, Any?> = mapOf(
    "host" to host, "status" to status.name, "seed" to seed.toString(), "nextSeq" to nextSeq, "decks" to decks,
    "seats" to seats.mapIndexed { i, s -> i.toString() to mapOf("uid" to s.uid, "name" to s.name, "bot" to s.bot, "open" to s.open, "ready" to s.ready, "google" to s.google) }.toMap(),
    "options" to mapOf("expansion" to config.expansion.name, "numPlayers" to config.numPlayers, "vpToWin" to config.vpToWin,
        "layout" to config.layout.name, "avoidAdjacentRedNumbers" to config.avoidAdjacentRedNumbers, "discardLimit" to config.discardLimit,
        "maxTurns" to config.maxTurns, "randomDiscard" to config.randomDiscard, "generateTradeOffers" to config.generateTradeOffers,
        "randomFirstPlayer" to config.randomFirstPlayer) + (if (colours.isEmpty()) emptyMap() else mapOf("colours" to colours)),
    "version" to ROOM_VERSION, "invited" to invited,
) + listOfNotNull(rematchOf?.let { "rematchOf" to it }, next?.let { "next" to it })

/**
 * The room format's version. 2 from 0.2.10 (Mauro, 2026-10-13): a random first player (#126) plays differently, so a
 * 0.2.9 phone (which only reads 1) is kept out of 0.2.10 rooms and this one out of theirs. 3 from 0.3.4: invites, the
 * rematch link and the Google mark on seats (#72), which an older phone would drop when it rewrites the room. Bump it,
 * and let the rules' `valid()` accept it, whenever two versions of the app would play the same room differently.
 */
internal const val ROOM_VERSION = 3

/** A room made by a different version of the app; its message is what the player sees. */
internal class RoomVersionException(val version: Int) :
    IllegalStateException("That room was made with ${if (version < ROOM_VERSION) "an older" else "a newer"} version of the game. Everyone needs the same version: update from the release page.")

@Suppress("UNCHECKED_CAST")
internal fun room(id: String, d: Map<String, Any?>): Room {
    val version = (d["version"] as? Number)?.toInt() ?: 0
    if (version != ROOM_VERSION) throw RoomVersionException(version)
    val o = d["options"] as Map<String, Any>; fun n(k: String) = (o[k] as Number).toInt()
    val seats = d["seats"] as Map<String, Map<String, Any?>>
    return Room(id, d["host"] as String,
        GameConfig(Expansion.valueOf(o["expansion"] as String), n("numPlayers"), n("vpToWin"), BoardLayout.valueOf(o["layout"] as String),
            o["avoidAdjacentRedNumbers"] as Boolean, n("discardLimit"), n("maxTurns"), o["randomDiscard"] as Boolean, o["generateTradeOffers"] as Boolean,
            o["randomFirstPlayer"] as Boolean? ?: false),   // rooms made before #126: seat 0 starts
        (d["seed"] as String).toLong(), List(4) { i -> val s = seats[i.toString()]!!
            Seat(s["uid"] as String?, s["name"] as String, s["bot"] as String?, s["open"] as Boolean, s["ready"] as Boolean, s["google"] as Boolean? ?: false) },
        Status.valueOf(d["status"] as String), (d["nextSeq"] as Number).toLong(), (d["decks"] as Number).toInt(),
        (d["invited"] as List<String>?) ?: emptyList(), d["rematchOf"] as String?, d["next"] as String?,
        (o["colours"] as? List<*>)?.map { it as? String ?: "" } ?: emptyList())
}

