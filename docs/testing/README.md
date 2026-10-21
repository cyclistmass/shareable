# Tests: what we have and what is missing

Every test and test script in the project, what it checks, whether CI runs it, and the gaps we know about
(Mauro, 2026-10-12: "an inventory of the test scripts in case there's gaps"). Counts are tests run, from the
JUnit results on 0.2.8. Keep this page current when a test or script is added, moved or retired.

## Unit tests: 396, all run by CI

`./gradlew :engine:test :ai:test :net:test :app:testDebugUnitTest`

| Module | Tests | What they cover |
|---|---|---|
| engine | 118 | Rules of both rulesets: setup, turns, building, trading, counter-offers, Fortune cards, Grand Highway, Age of Chivalry (29: commodities, Ramparts, Raiders, progress cards, Capitals, 13 points), hidden information, determinism and replay. **Fuzz:** 2,000 random base games and 1,000 Age of Chivalry games with invariants after every move. **Break-it:** junk and wrong-time actions in both rulesets. **Dice:** fairness and chi-square tests (a loaded die is caught), deck dice. **Every card:** each Age of Chivalry progress card and each Fortune card played in 40 real positions, checking its effect and that the turn goes on. **Catanatron:** id mapping to Catanatron's board, and 200 random base games written as traces for the cross-check below |
| ai | 14 | Bots finish games in both rulesets and trade sensibly without creating or losing cards |
| net | 24 | Optimistic client, rollback, resume, reconnect, move codec, a hostile log, 300 delayed random online games that must agree |
| app | 240 | Start-screen setup logic, lobby and rejoin, invite links, local save (#82), Report contents, Google sign-in logic (#83), settings and Pace, trade panel, build menu, every legal move reachable by a tap, 3D board math against 200 Catanatron reference positions, piece lift, Turn bar mapping, sea, birds, dice art and timing, HUD and hints |

## Rules cross-check against Catanatron (#12), run by CI

`python tools/catanatron/diff.py engine/build/catanatron-traces` (after the engine tests; needs `pip install catanatron==3.2.1`).
[Catanatron](https://github.com/bcollazo/catanatron) is an independent open-source engine for the base game. Our
engine plays 200 random 4-player games; Catanatron replays each one move by move and must agree on whose move it is,
the full list of legal moves, and after every move each player's points, hand, Fortune cards, knights and pieces
left, Grand Highway and Strongest Watch, the bank, the Bandit and the winner. Last run: 200 games, 145,544 moves, 0
differences; every game finished, with 290 Grand Highway and 245 Strongest Watch changes.

Every difference found was a simplification or slip in Catanatron, not in our rules. `diff.py` allows for these:

1. Catanatron lets you play a Fortune card bought this turn (the rules don't).
2. Before the roll it offers only the Sentinel; we also offer the other Fortune cards.
3. Its free roads (Trailblazers) need timber and clay in hand, and it stops early when one road can't be placed.
4. Its longest road drops the last segment when a road ends at an opponent's building.
5. It discards in a different order (we compare the whole discard together).
6. When the bank is short and only one player is owed, it pays nothing; the rules pay that player what's left.
7. It offers a one-card Windfall whenever one resource runs out.

Trades between players aren't compared (Catanatron has none); discards are random on both sides.

## Device tests (emulator), run by CI

One CI shard per row (`device` job matrix), all running side by side; CI runs on pushes to main and on pull
requests, and skips changes that only touch Markdown or `docs/`.

| Shard: test or script | What it does | Fails on |
|---|---|---|
| monkey: `tools/ci_monkey.sh` (twice: plain, and in an Age of Chivalry game) | Android `monkey` random taps | crash, ANR, monkey abort |
| selfplay: `tools/ci_selfplay.sh 120 3` | bots play back-to-back games at Max speed in both rulesets, stopping once 3 finish | crash, ANR, fewer than 3 games finished per ruleset in 120 s |
| touch-base, touch-chivalry: `tools/ci_touchplay.sh 100 10` | a human seat played with taps, 3 and 4 players, one shard per ruleset | crash, ANR, stuck, fewer than 10 moves |
| play-to-end: `tools/play_to_end.sh chivalry 4 900` | a whole 4-player Age of Chivalry game to the end screen | stuck for 60 s, no end in 900 s |
| trade: `tools/ci_trade.sh` | bank and player trades by touch | a trade not taken |
| withdraw: `tools/ci_withdraw.sh` | withdrawing an open offer by touch, both rulesets | the offer stays open |
| trade: `tools/ci_keyboard.sh` (after the trade script) | Back and the keyboard on the game-start name, Join name and room code, and Report note: keyboard up + Back + Back, typed + Enter + Back | Back leaves with the keyboard up, Enter or Back leaves the keyboard up, Back doesn't leave once the keyboard is away (no extra Back, #146) |
| reach: `tools/ci_reach.sh`; play-to-end: `tools/ci_reach.sh end` (after play_to_end) | Every button outside the board by touch on CI's 640x320 screen, scrolling like a player: start menu (fresh and with a saved game), Settings, game-start, Report, Look & art, Join, the lobby, the game Menu, card guide and in-game Report (about 8 min). `tools/ci_reach.sh end` after play_to_end (play-to-end shard): the game-over panel and the dice histogram with the event die. A taller device is switched to 320x640 @160 for the run | A button cut off or squashed to a sliver (under 10 dp) where no scroll reaches it (0.3.2: Points, Report, Intro, Settings), a crash |
| online: `tools/ci_online.sh`: `OnlineTwoClientTest` | two clients host and join a room on the Firebase emulators; bots play a whole game | the two end states differ |
| online: `tools/ci_online.sh`: `LiveBoardDeviceTest` | real touch on the 3D board: twist, pinch, tilt, pan, place a homestead and a road | gesture or placement not taken |
| `firebase/rules-test` | Firestore security rules and push delivery | a rule lets the wrong player write |

## Scripts not run by CI

| Script | Use |
|---|---|
| `tools/userplay.sh` | The only full game from the start screen with real taps (local, host, join). Run by hand before a release |
| `tools/online_sim.sh` | Two emulators play one online game (autoplay) and compare end states |
| `tools/names_check.sh` | Bot names on CI's 640x320 screen by taps: a theme and a name per seat, kept after closing the app before Play, shown in the game and after resuming it, remembered by the next New game, then monkey on New game. Screenshots for review |
| `tools/test_perf_framestats.sh` | Checks the frame-stats parser |
| `tools/record_showcase.sh`, `tools/record_screenshots.sh` | README media; they assert nothing, but they break when buttons move, so they double as a UI smoke check |
| `tools/perf_ab.sh`, `tools/publish_release.sh` | Performance A/B, release (refuses unless CI is green; publishes the release APK, never a debuggable one; then closes the issues it shipped) |
| `tools/ci/apk_check.sh` | Every APK has the stable key; the release one is not debuggable (no test hooks, #147) and, with `--firebase`, carries the Firebase config (`google_app_id`; local builds never do). Run by CI's builds and by publish_release |
| `tools/close_shipped.sh` | Run by the release: closes each issue a commit since the last release marks `Fixes #N` / `Closes #N`, with a "Shipped in X" note. A bare `#N` is progress and stays open (listed for review). `DRY_RUN=1` lists only. |

## Gaps

Known places where nothing, or only logic, is tested:

1. **No CI device step starts at the start screen or finishes a game by tapping.** Touch-play stops after 100 s
   and uses the move hook for discards, card picks and steals; self-play and monkey launch with debug extras.
   `userplay.sh` does the real thing but only by hand.
2. **Screens with no UI test:** start screen, Look & art, the gear panel (Pause, speed, Watch bots, Pace), the
   Report panel, the account row, the intro.
3. **Resume after closing the app (#82):** saving and loading are tested; closing and reopening on a device is not.
4. **Google sign-in:** the link/switch/sign-out logic is tested with a fake; the real account picker only by hand.
5. **3D board:** math and gestures are tested; how it looks is not (no screenshot comparison).
6. **Online edge cases:** reconnect and quit-to-bot are tested as logic; deleting a room, a player leaving
   mid-game and two phones on real Firebase are not in CI.
7. **Trades by tapping:** the trade panel's logic is tested; touch-play leaves out trade offers.
8. **Pirate theme, sounds, music and haptics:** art tables only; nothing checks them on a device.
9. **5 and 6 players:** the engine allows 2 to 4 (base) and 3 to 4 (Age of Chivalry); only the piece-supply layout
   is tested for more.
10. **Scripts match screen text literally.** A renamed or moved button breaks them (it happened twice on
    2026-10-12). uiautomator writes `&` as `&amp;`, so a label with `&` must be written that way.
