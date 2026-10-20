# Online play transport (#7)

## Test plan

Exercise lifecycle, occupied/duplicate/reserved seats, authorization, stale lobby
edits, concurrent seat/move claims, sparse room-to-engine seat mapping, invalid,
wrong-turn and forged moves, missing/corrected history, and every base and Age of
Chivalry move kind. Replay 300 random legal games through two clients, comparing
full engine hashes after every move. Run existing engine invariant/break-it/dice
tests, app JVM tests, assembleDebug, and every rules allow/deny in Firestore's
emulator. On-device installation/screenshots/monkey are skipped because this task
forbids Android emulator and adb use.

Latency revision tests additionally exercise optimistic apply before a delayed
write starts, multiple pending legal actions, denial rollback, conflicting echoes,
cached/offline play, resume cursors, UI observer detach while room listeners stay
alive, incremental gaps/duplicates, and 300 delayed optimistic games. Exercise
observer-triggered moves to ensure their writes cannot overtake predecessors.
Measure local apply through observer publication and write-to-independent-listener delivery.
Validate CI YAML and build an isolated copy without Firebase configuration.

## Data model

`:net` is Kotlin/JVM, depends only on `:engine`, and exposes GameTransport,
InMemoryTransport and SyncDriver. Methods return CompletableFuture; observations
return closeable Subscription. Share one MemoryStore between fake clients.

`games/{code}` stores version=1, host uid, status (`lobby`, `playing`, `done`),
seed (decimal string preserving all 64 bits), options (every GameConfig field),
decks (0=random; 1–4 stacked decks), createdAt (server timestamp), nextSeq,
lastMove, and four seats keyed `0`–`3`. Seats contain uid/null, name, bot/null
(EASY/GREEDY/STRONG), open and ready. Reserved bots have open=false. Open bots
fill seats at start if nobody joins. Active seats map to contiguous engine player
indices in ascending seat order. The host submits bot actions.

`games/{code}/moves/{0000000000}` stores seq, seat (string), uid, action and prev
(the predecessor's padded ID, empty for sequence zero).
The v1 codec is `1:TYPE_NAME:DECIMAL_PAYLOAD`; all move kinds round-trip without
floating-point conversion or new Gradle dependencies. Config, seed and accepted
moves determine state. SyncDriver waits for gaps and consumes invalid entries
without changing state. It exposes rejected sequences and returns state copies.

Appending uses one create-only-by-rules `set()` with no reads or transaction.
Rules require the immediate predecessor to already exist with the matching seq,
the correct padded ID and seat ownership. Immutable unique IDs prevent duplicate
sequence writes, and predecessor checks prevent gaps. Room nextSeq/lastMove are
legacy initial fields and no longer updated during play; observers derive nextSeq
from the contiguous move stream.
Existing moves cannot be updated/deleted. Host edits lobby seats, with narrow
exceptions for self-claim/leave/readiness. Seats/options freeze during play.
Host-only finish marks done; seated players retain read access. Host departure
and mid-game departure/replacement are unsupported.

## AppRoot wiring (owned by the other session)

1. Remember `LobbyRepository.create(context)` once. It chooses Firestore when a
   FirebaseApp initializes from packaged resources, otherwise a shared local
   InMemoryTransport. Conditional Google Services plugin behavior is preserved;
   no configuration file contents are read.
2. Host with `LobbySession.host(transport, setup, seed)`. Browse using
   `transport.signIn().thenCompose { transport.listOpen() }`. Join using
   `LobbySession.join(transport, setup, normalizedCode, seatIndex)`. Route future
   completions to UI state on the main thread and surface errors/conflicts.
3. Render `OnlineLobbyScreen(session, onPlaying, onSettings, onError)`. It wires
   the existing LobbyScreen/onChange seam. The session starts GameClient at lobby
   entry and owns the room listener across screens; the composable closes only
   its UI observer. onPlaying now receives ClientView, not Snapshot. Make navigation
   idempotent. Original
   LobbyScreen + Lobby.host/sample still work for the local UI.
4. Keep the SAME session and session.client during the game. Subscribe using
   `session.client.watch { view -> ... }` and render view.state. Act through
   `session.client.act(myRoomSeat, action)`: it validates, applies and publishes
   synchronously before starting the background write. Do not wait for its future
   to render. Confirmed echoes never double-apply; rejection restores confirmed
   state, drops dependent predictions and reattaches from the confirmed cursor.
   room.players maps seats to engine indices. Only the host runs bots.
5. Production dice use `session.client.nextRoll()` and submit its
   explicit ROLL action. Stacked decks reconstruct from accepted history after
   reconnect; do not keep independent per-phone decks. Plain Action.ROLL remains
   supported for deterministic simulations. When the engine ends the host calls
   `transport.finish(id)`. Close each UI observer when leaving its screen, but
   call session.close() only when leaving the ROOM. Call session.resume() on app
   resume or connectivity restoration. Display OnlineConnectionStatus(view) in
   the existing game HUD; reconnecting is a small label and does not disable moves.
   Guests leave lobbies with `transport.leave(id)`.

The fallback is local to the process, not LAN transport. The invite base remains
the placeholder `https://cyclistmass.github.io/shareable/join/#`; App Links and join navigation belong
to AppRoot/product integration.

## Rules testing and deployment

Install using `npm ci` in firebase/rules-test. From repo root run:

```
firebase emulators:exec --only firestore --project demo-wgbg "npm --prefix firebase/rules-test test"
```

Or from firebase/rules-test:

```
firebase emulators:exec --config ../../firebase.json --only firestore --project demo-wgbg "npm test"
```

CLI 15 requires Java 21+ for emulators; Gradle uses JDK 17. On restricted Windows,
npm.cmd/firebase.cmd avoid PowerShell script-policy restrictions. Testing used an
isolated CLI config, npm cache and downloaded Java 21 JRE in the writable user cache.

After reviewing the rules, deploy them yourself from repo root:

```
firebase deploy --only firestore:rules --project workinggermangame
```

No deployment, git commit/push/config change or Android device access was performed.

## Limits and open decisions

Warm authentication begins in LobbyRepository.create. Firebase persistent local
cache is explicitly enabled. Move queries use orderBy(seq) and seq > confirmed
cursor, processing only documentChanges with metadata included. Pending writes
are ignored until confirmed; committed cache entries are replayed offline, while
fromCache drives the reconnecting indicator. No move polling or full-log fetch
exists on the submit/update path. Legacy observe returns aggregated snapshots
for compatibility; use GameClient/observeMoves for gameplay.

Seated clients receive full deterministic state, hidden cards and seed. This is
for trusted friends, not cheat-resistant competitive play. Rules cannot validate
Kotlin engine legality: replay ignores illegal entries and normal clients validate
before appending through GameClient. Transport.submit itself is now a thin write
adapter; rules cannot enforce engine legality. A fresh client still downloads the
initial historical documents once. No state checkpoint document was added: the
measured 487-move replay was 1.7–3.34 ms across runs. Persisted checkpoints remain an option
if measured late-join download time, rather than CPU replay, becomes significant;
they need versioning and trust/validation rules. Decide the invite
domain/App Link, host cancellation/transfer and disconnect UX separately.

## Verification results

- Engine: 114 tests passed, including 2,000 base and 1,000 Age of Chivalry
  invariant-fuzz games (5,560,965 and 7,211,120 actions respectively).
- Net: 19 tests passed; transport fuzz covered 300 games, 514,720 moves, both
  rulesets, 2–4 players and bots, comparing both clients after every move. These
  games use a 120-turn draw safeguard; the engine's existing fuzz uses longer games.
- Optimistic fuzz: 300 additional games and 47,772 moves, checking the prediction
  before each delayed acknowledgement and both clients after every acknowledgement.
- AI: 13 tests passed. App: 84 JVM tests passed, including the codec/lobby-seam
  tests. An early shared-checkout run hit a temporary HUD compile error, a sea
  assertion while its source was changing, and concurrent test-output interference.
  The latest source passed all tests in isolated build outputs; no protected
  source was edited by this transport task.
- Firestore rules/push: 18 emulator tests passed, including single-write races,
  metadata confirmation, offline echo and suffix resume.
- `:engine:test :net:test :app:testDebugUnitTest :app:assembleDebug` succeeded
  using JDK 17. Engine tests were explicitly rerun once rather than relying only
  on the prior cached results. Rules tests used Java 21 and demo-wgbg.
- A separate source copy under the writable cache, excluding google-services.json,
  successfully ran engine/AI/net/app tests and built the signed debug APK. Only
  that temporary copy's signing-file path was overridden to reuse the existing
  debug key; no key contents were copied or printed. Kotlin compilation used
  in-process mode to avoid the restricted default daemon directory.
- The specified shareable workflow now runs :net:test and has a separate Node 24 /
  Java 21 Firestore rules job using pinned CLI 15.33.0 and demo-wgbg. YAML parsing
  verified the job and step; remote CI was not triggered (no push).
- Timing (local workstation, concurrent builds): local apply/publication n=50,
  median 0.0317 ms, p95 0.3344 ms, max 12.7999 ms (includes cold-start outlier);
  487-move replay 1.6987 ms. Remote emulator delivery n=30: median 8.975 ms,
  p95 13.458 ms, max 16.580 ms. Sample seq-zero ROLL payload is 63 JSON bytes with
  the short test uid; actual Firestore wire overhead and real uids add bytes.
- Repeated emulator run under concurrent build load: median 14.160 ms, p95
  29.058 ms, max 37.542 ms (30 writes). These numbers vary with workstation load.
- Final configured-run local apply/publication n=50: median 0.0277 ms, p95
  0.0991 ms, max 5.3645 ms; 487-move replay 3.3423 ms. The final configured
  build and all test tasks passed, after the shared-checkout transient failures.
- On-device/monkey and live Firebase smoke tests were not run, as requested.
  No rules deployment was performed. These are workstation/emulator timings, not
  production network or Android rendering measurements.

## In the app (wired 2026-10-09)

Start screen: **Host a room** (opens a room, shows its code, link and QR) or **Join a room** (type
the code; any case, spaces or no dash; takes the first open seat). Online needs a real name, not the
default "You". Everyone marks ready, the host starts, and every phone plays the same game: your moves go
through `GameClient.act`, dice through `nextRoll()`, and the host's phone plays the room's bots.
`ClientView.actions` lets the game screen replay each new move for its turn panel and dice; a changed
history (a rejected prediction) rebuilds the screen from the room.

### Two phones against the Firebase emulators (no deploy needed)

```
firebase emulators:start --only firestore,auth --project demo-wgbg   # Java 21+ on PATH; no google-services.json needed
powershell -NoProfile -ExecutionPolicy Bypass -File tools/emulator.ps1 -Lane 1   # and -Lane 2
ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug   # and 5556
tools/online_sim.sh base        # or chivalry
```

Debug extras: `--es firebase emu` (talk to the emulators at 10.0.2.2:8080/9099; a debug-only network
config allows plain HTTP to that address) and `--ez autoplay true` (a bot plays your seat). The script
hosts on lane 1, joins on lane 2, plays a whole game and fails unless both phones log the same
end-of-game fingerprint (`WGBG-ONLINE` in logcat). First pass: room RAVEN-60, 489 moves, same winner
and state hash on both phones, no crash. CI for this is #35.

Found on the first live run: creating a room was always denied, because its transaction reads the
not-yet-existing room and the read rule needed `resource.data`. Fixed (`resource == null ||`), with a
rules test.
