# Test speed and coverage: ideas

Reviewed after every build and test run (see AGENTS.md, "How to work"). Anyone may add a line or pick one up;
move it to **Done** with the commit when it lands.

## Open

- **Stall watchdog in the remaining device scripts** (`ci_touchplay.sh`, `ci_selfplay.sh`, `ci_online.sh`,
  `ci_monkey.sh`): source `tools/lib/turnstream.sh` like the trade scripts. (Bosun, 2026-10-13)
- **A long Chivalry game in the report-size test**: reports are capped at 190 000 characters; check a real
  300-turn replay fits whole. (Bosun, 2026-10-13)
- emulator-5556 wedges after installs (am start -W never returns, blank screen, no focus); a reboot fixes it for a while.
  Every `am start -W` in tools/ now has a 60 s timeout so a script fails fast instead of hanging. Bosun, 2026-10-13 (#134?).
- **userplay.sh online on a local build needs `app/google-services.json`**: a worktree without it builds an offline-only
  APK whose "online" rooms live in one phone's memory, so the joiner gets "No room with that code". Have userplay.sh
  (host/join) say so up front, or test online on the CI-built APK. Bosun, 2026-10-13.
- **Stopping userplay.sh**: killing its parent left the driver running (it kept tapping the next test's phone) and its
  run.pid lock. A `trap` that kills the process group and removes run.pid on exit, plus a `tools/userplay_stop.sh`.
  Bosun, 2026-10-14.

## Done

- The app reads its own screen (debug builds, `UiLine.kt` + `tools/lib/uiread.sh`): about 0.2 s a look instead of
  uiautomator's 2-15 s. On lane 2: ci_reach 405 s to 99 s; ci_trade 632 s to 471 s (games to a two-lot turn vary);
  ci_withdraw 114 s for both rulesets. All touch and check scripts use it except ci_layout (layout_check.py needs the
  nested tree) and the record_* scripts (after the 0.3.4 re-record). (36, 2026-10-21)
- Two flaky CI checks fixed in the tests (#138): `ci_withdraw.sh` takes a missing "withdrew" line as a lost race
  (every bot said no before the tap; a refusal adds no log line) and offers again, up to 3 times; LiveBoardDeviceTest
  reads off-screen spots (negative pixels) and drags one into view, and waits 30 s for the first frame report.
  54a77a3, and the LiveBoardDeviceTest commit after it. Main went green on all six jobs and 0.3.0 was released from 16505be.
- Card tester (debug builds, #133): 86 card-by-mode scenarios set up on a board, played by taps, pass/fail with
  expected vs actual, sent as a report. 7977aad.
- CI device tests split into four shards (monkey, play, trade, online) on a cached emulator snapshot, with
  `ci_trade.sh`, `ci_withdraw.sh` and a game to the end screen (`play_to_end.sh chivalry 4`) added. sharable
  `wgbg/ci-parallel`, 0.3.0. On its first run the trade shard caught a real bug: on CI's 640 dp screen the Age of Chivalry
  trade chest ran its Trade button off the edge (930889b). Run device scripts at `wm size 640x320` + `wm density 160`
  before pushing; that is what CI sees.
- `ci_withdraw.sh` prints its last 25 steps when a ruleset fails and still runs the next ruleset, so one CI log says why.
- Hook-driven scripts react to log lines instead of polling, with a stall watchdog (#139): `tools/lib/turnstream.sh`
  used by `seek_spot.sh`, `ci_trade.sh`, `ci_withdraw.sh`. seek_spot: 117 turns in 5 min (was 76 in 9); a killed app
  is reported in 15-45 s with a screenshot instead of at the time limit.
- Local `--ez autoplay true` (a bot plays your seat offline too): a full base game in ~2.5 min. 7254728.
- `tap()` helpers no longer let `adb shell` eat stdin inside `while read` loops (only the first tap happened).
  7254728, found by Crest.
- 2026-10-18, Codex (#88): measure model primitive counts as well as triangles, including two road instances
  per edge and animated critter materials; Space's unbatched bound was 797 draws despite only 199,090
  triangles. Keep the new batch-budget test and profile its eventual consumer on a mid-range device.
- 2026-10-18, Codex (#88): preconfigure a writable Gradle cache, signing-key copy and in-process Kotlin
  compiler for restricted Windows sessions; avoids wrapper/signing lock failures and repeated daemon fallbacks.
- 2026-10-19, bugs (#111): profile the Space and sea sprite decodes that still happen inside draw (SpaceSprites.image,
  SeaSprites3d.frame cache misses on a tilt-step change or a new boat or critter frame) with a frame trace on a lane. If
  they cost frames, decode them in the background with a redraw trigger and a board-layer key, so pieces don't blink in.
- 2026-10-19, bugs: worktrees with a Gradle build tree can't be removed by `git worktree remove` on Windows (Filename
  too long). Use `cmd /c rmdir /s /q "\?\C:\path"`, then `git worktree prune`.
- 2026-10-19, bugs: every cold start skips about 43 frames (~0.7 s) on the main thread while the start screen first
  draws, before any game opens (Pixel6 API34, `Choreographer: Skipped 43 frames`). Profile it with a frame or method trace
  (Splash, fonts, wood textures, Firebase setup) and move what it finds off the main thread; saves a visible stall on launch.
- 2026-10-20, bugs: checking the knight pick on a device took about an hour of driven Age of Chivalry games (bots usually
  win before seat 0 has an active knight with a free corner to move to). A debug-only launch extra that loads a stored
  position (a seed plus a move list, as the save store already records) would reach any mid-game state in seconds for
  on-device checks of knights, Raiders, cards and ports.
- 2026-10-21, bugs: tools/ci_layout.sh's full matrix (68 screens) takes about 12 min on a fresh Pixel 8 emulator, not
  the 5 aimed for; the cutout pass (32 screens, a fresh stress game per cutout and turn) is two thirds of it. Starting
  the stress game once per cutout with both turns read from one launch, or a smaller CUTOUT_SIZES on CI, would halve it.
- 2026-10-21, bugs: the local API 36 guest hangs (system_server dies, uiautomator "null root node") about 15 min into
  repeated wm size changes up to 1440x3200 at 560 dpi, sooner with a second emulator on the same integrated GPU. A cold
  restart fixes it. Chunk long device runs, or give the AVD more than 2.5 GB.
- 2026-10-21, bugs: unit tests can't see a VerifyError in the huge WatchGameScreen lambda (ART rejected it after three
  more inline LocalDensity reads); only a launch on a device does. Splitting that lambda into smaller composables would
  remove the risk; until then every HUD change needs one on-device launch before it is called done.
- 2026-10-21, WGBG: six-seat games need two more seat colours. The seven in SeatColours.kt have no clean 6-set (no two
  under 30 CIELAB dE in any sight); only two clean 5-sets exist. The code already takes any seat count (resolve, draw,
  the picker); choosing the extra colours is Mauro's call (Bosun, 0.3.4), then add them to SeatColour and pin the new
  clash pairs in SeatColoursTest.
- 2026-10-21, WGBG: the 2D sea boats (GameArt.BOATS drawables) keep fixed sail colours, Orange included; only the 3D
  boats take the game's seat colours. Fine as scenery; tint the 2D sails too if testers read them as players.
- 2026-10-21, WGBG: two files held literal backspace bytes where `\b` was meant (SeatNamesTest's card-tester name check
  and tools/userplay.sh's menu-build check), so neither check could ever fire. Cause: `\b` passed through a non-raw
  string (Python, printf) in an edit script. Write edit scripts to a file and grep for [\x00-\x08] after scripted edits.
- 2026-10-21, Bosun: the in-memory transport the unit tests use had its own copy of every room change, and it drifted
  from Firestore's: a guest quitting a running game was refused in memory but handed to a bot on the server, and
  TransportTest pinned the wrong behaviour. Both transports now apply net's RoomOps, and AppWritesTest records the
  app's real Firestore writes into firebase/rules-test/app-writes.json, which the rules tests replay (also with 20
  spare terms against the 1000-expression budget). A rules or app change that would refuse a real write now fails CI.
- 2026-10-21, Bosun: slow local device runs move to CI shards (the repo is public, so Actions minutes are free):
  friends_sim on two copies of one AVD (tools/ci_friends.sh), ci_layout split three ways, names_check, colours_check;
  :cli:test and the tools' Python tests (25, never run before) join the unit-tests job.
- 2026-10-21, mauro-1d (Fable's study, Phase A): `ScriptedDice` (engine Dice.kt, tests and debug only); test rolls
  carry their dice in the move (`T.roll`, `CK.roll`) instead of brute-forcing a seed, and the seed riggers are gone.
  #55's regression stack (RegressionStackTest, 0.1 s): six fixed games whose scripted dice reach the rare states,
  pinned by state hash in regression-stack.tsv. Both fuzzers now assert rare states, not just action kinds
  (Coverage.kt: empty bank, odd hands halved, turn-limit draws, every Raiders outcome). Found: random play never
  empties a progress deck (0 in 1000 games); only ChivalryRulesTest's stacked deck does. Next (Phase B): `--el seed`
  and `--es dice` debug extras so ci_trade, ci_withdraw and reach-end run on fixed dice.
- layout-cutout needs a google_apis image shard (aosp_atd has no cutout overlays; it was a false green), 2026-10-21, mauro-1d. Drafted 2026-10-23 (Helm): the shard names `"target":"google_apis"` and wgbg-device.yml boots that image; first green run still to come
- Local device checks should run the APK CI built (gh run download), not a local build without the Firebase config: names and colours passed on the config-less build and failed on the config'd one (run 39682685361). Catches flavour-only layout changes; 2026-10-21, catan-android-36
- A lane's Pixel Launcher ANR-ing after hours covers the screen and fails any script; restart the emulator before a local run of names/colours/reach (CI's aosp_atd has no launcher to hang). Saves a misread failure per run; 2026-10-21, catan-android-36
- A whole game by taps (release hand test): a 3-seat base game takes about 20 min median (about 8.5 s a turn by tap driver; headless Greedy games: median 83-89 turns, p99 160), so budget 30 min and stop at 45. The driver should build when it can: a seat that only rolls and ends turn stretches the tail (one game in 300 ran 724 turns). Chivalry and 4 seats not yet timed; 2026-10-21, mauro-1d
- The app's screen read (UiLine.kt) marks disabled controls clickable="true" (Compose keeps OnClick on `clickable(enabled = false)`); add uiautomator's `enabled="false"` so scripts can skip engraved menu lines without knowing the rules. touchplay picked a knight's disabled Promote/Move/Chase over and over and stalled both chivalry nightly games (0.3.5-pre2); the script now maps legal moves to lines, which a new line would silently miss. 2026-10-23, Helm
- A stalled touchplay game left one log line; it now saves the screen, screen read, log and its last moves tried (touchplay-*.png/xml/logcat.txt/trace.txt). Do the same for play_to_end.sh and ci_selfplay.sh stalls. 2026-10-23, Helm
