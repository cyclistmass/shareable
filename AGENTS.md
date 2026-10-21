# Working German Board Game: guide for AI coding agents

Steering for any AI agent (Claude Code, Codex, Copilot and others) working on this repo. Human
contributors are welcome to follow it too.

Android hex-trading board game with rules compatible with the classic German settlement-building
game, plus our knights expansion, **Age of Chivalry**. Kotlin + Jetpack Compose, GPL-3.0.

| Module | What it is |
|---|---|
| `engine/` | Pure Kotlin, deterministic rules engine (base game + Age of Chivalry). No Android. |
| `ai/` | Pure Kotlin bots (Random, Easy, Greedy). |
| `net/` | Pure Kotlin transport API: optimistic client, move codec, in-memory transport. |
| `app/` | Android UI (Compose) and the Firestore transport. |
| `cli/` | Play-by-file command-line client for testing. |
| `tools/` | Emulator, CI, GIF-recording and art-import scripts. |

## Names (copyright safety, required)

Never use the original game's names in UI, art, card text or docs. Our names:

- Resources: Timber, Clay, Fleece, Grain, Stone. Pieces: Homestead, Town, road. The robber is the **Bandit**.
- Development cards are **Fortune cards**: Sentinel, Landmark, Trailblazers, Windfall, Corner the Market.
- Expansion **Age of Chivalry**: commodities Parchment, Fabric, Silver; progress decks Guilds, Academy, Crown;
  the **Raiders** (barbarians), Capital (metropolis), Ramparts (city walls).

Keep the "Not affiliated with or endorsed by Catan GmbH" line in the README. Use our names in code too
(`Chivalry.kt`, `ChivalryState`, #84), as in player-facing text. Only the wire keeps two old names, so saves and rooms
from before still read: `Expansion.wire` and the move codec's `CK_DONE`.

## Look and feel (required on every panel)

Every panel, dialog, HUD box, menu and button uses the tabletop look: **dark wood panels with old-brass
edges and brass-edged buttons**, in a **rivetless brass, steampunk master-craftsman** style (smooth hand-finished
brass, fine engraving, never rivets; a screw only where one would really be needed, otherwise a press-fit look). The
brass is always **worn and aged, never new**: burnished and worn the way brass gets on a boat, well loved but old,
with softened edges, darkened recesses, a gentle patina or verdigris in the crevices, highlights polished by years of
hands. Use the shared pieces in `app/.../Tabletop.kt` (`brassEdgedWood()`,
`brassPlate()`, `BrassButton`, `GoButton`, `Choice`, `WoodButton`) and the Alegreya SC / Alegreya Sans
fonts. No plain Material grey buttons or black boxes. Action colours: yes/confirm green, no/cancel red,
counter/primary gold.

## Graphics workflow

0. **Before making or changing any art, read `docs/design/art-spec.md`** (and for a new theme, `docs/design/theme-spec.md`) (only then; it isn't needed for
   code work). It has the house style and the file and on-board size of every object: tiles, number plate,
   bandit, pieces, knights, ports, cliffs, sea, boats, gulls, Raiders, cards, dice, buttons and icons. Key
   rule: the number plate sits **lower right** on each tile (centre +0.36, +0.30, radius 0.27) on a plain
   clearing, with nothing tall in front of it, so tilting the board never hides the number. **3D models too:** a
   rendered tile's clearing (and the plate) must land in the lower half, lower right, at every tilt step, with peaks,
   trees and buildings behind it; check every 3D render for this before it goes in, and turn the model in Blender
   rather than flipping images.
1. New or changed art is shown as options side by side for the maintainer to pick from before it goes in.
2. Approved art goes into `app/src/main/res/drawable-nodpi/` and onto the in-app **Inventory** screen
   (start screen › New game › Look & art, `drawInventory` in `MainActivity.kt`), so every object can be checked together.
3. Screenshot the Inventory screen and the board to confirm.
4. Art must be CC0 or our own. Record every source in `CREDITS.md`.

## Testing (required with every change)

Write the test plan before calling a change done, and cover all four kinds:

1. **Rules/unit tests:** each rule, card and edge case (empty bank, no pieces left, 2 players, ties).
2. **Random-play fuzz:** thousands of random legal games with invariants checked after every move
   (cards conserved, piece limits, points add up, games finish, no crashes).
3. **Break-it tests:** illegal, garbage and wrong-time actions; each must be rejected and leave the
   state unchanged.
4. **On-device:** install on an emulator, drive it, screenshot it; for UI work also run Android's
   `monkey` random-input tester and report any crash.

**Before every push: test as the user (required unless the maintainer says otherwise for that push).** Install the exact APK being released, both fresh
and as an update over the last release. Go in through the start screen with real taps, no debug
extras or hooks. Play full games to the end in both rulesets with 3 and 4 players. Close and reopen
mid-game. Host and join online, and try every changed feature. Debug hooks (`WGBG-TURN` broadcasts,
`--es start`, `--ei players`, autosetup) are for unit and automated tests only; they never count as
user testing.

Also cross-check core rules against Catanatron, test dice fairness statistically, and report test counts
honestly. Never call something done with failing or skipped tests.

## Build and run

- JDK 17 and the Android SDK (`local.properties` with `sdk.dir`, not committed).
- `./gradlew :engine:test :ai:test :net:test :cli:test :app:testDebugUnitTest :app:assembleDebug`
- Online play needs `app/google-services.json` from your own Firebase project (never committed), or the
  Firebase emulators: `firebase emulators:start --only firestore,auth --project demo-wgbg`, then launch with
  `--es firebase emu`. See `docs/online-play.md`.

### Driving the app (debug builds)

- Launch extras (`adb shell am start -n com.mauro.wgbg/.MainActivity ...`): `--es start base|chivalry`,
  `--ez watch true` (bots play), `--ez autoplay true`, `--ez autosetup true`, `--es firebase emu`,
  `--ef zoom <f>`, `--ef tilt <f>`. Add `--activity-clear-task`; `pm clear com.mauro.wgbg` drops saved rooms.
- Agent hooks: every turn logs `WGBG-TURN` lines (`turn= phase= player= prompt=".." legal=TYPE:PAYLOAD,...`);
  send a move with `adb shell am broadcast -a com.mauro.wgbg.MOVE -p com.mauro.wgbg --es move TYPE:PAYLOAD`.
  Each finished local game logs a `WGBG-GAME` line.
- Before a release: `tools/release_check.sh` runs every automated check in order (unit, rules, APK and its key, device
  scripts on both lanes, friends_sim) and prints one timed report plus the by-hand user-test list;
  `tools/emulator_setup.sh test|user` readies a test phone (Play Store and animations off) and hands it back.
- Scripts: `tools/ci_monkey.sh`, `tools/ci_selfplay.sh`, `tools/ci_trade.sh` (bank and player trades by touch), `tools/ci_withdraw.sh` (withdraw an open offer by touch), `tools/ci_online.sh`, `tools/online_sim.sh`,
  `tools/record_showcase.sh` (README GIFs and the gameplay video), `tools/record_screenshots.sh` (README gallery);
  `tools/emulator.ps1` launches a landscape emulator on Windows.

## Issues, commits and CI

- Plans and bugs are GitHub issues on `cyclistmass/shareable` (labels: v1, post-v1, art, ui, engine, ai,
  multiplayer, testing, bug, age-of-chivalry). Reference the issue in commits ("#45"); close it only when
  the work is done and verified.
- CI (`.github/workflows/wgbg-ci.yml` in the shareable repo) runs every test, builds the APK, then runs
  monkey, bot self-play and a two-client online game on an emulator. A red CI is stop-and-fix.
- Every push that changes how the game looks re-records the README GIFs (`tools/record_showcase.sh`) and, if
  the screens changed, the gallery (`tools/record_screenshots.sh`), in the same commit.
- **Each major merge** (a feature or art set landing): bump `versionName` (0.MINOR.PATCH until v1) and
  `versionCode` in `app/build.gradle.kts`, re-record the showcase (`tools/record_showcase.sh`,
  `tools/record_screenshots.sh`), push, and once CI is green run `tools/publish_release.sh` to publish
  release `wgbg-v<version>`. The README's links use `releases/latest/download/…`, so they always serve the
  newest build.
- Keep git tidy: no stray files, delete merged branches and worktrees, no machine-specific paths in build
  files or scripts.

## How to work

- **Never commit secrets** (tokens, keystores, `google-services.json`, service accounts). Humans log in to
  services themselves.
- **Don't wait blindly.** Estimate every long step (build, CI, emulator boot, install, network call). If it
  overruns, check whether it's stuck (process alive, log moving, device connected, network up, disk full,
  waiting on a prompt) and fix or report it.
- **When you find a bug, hunt its siblings** in code, scripts, CI and tests, and fix them in the same change.
- **After every build and test run, review speed and coverage** (anyone working here, human or agent): what was
  slow (build, emulator driving, waiting, CI jobs) and what the run didn't test that it should have. Small fixes go
  straight into `tools/` or the tests in the same change; bigger ones go in `docs/testing/improvements.md` (one
  line each: the idea, what it saves or catches, who noticed, date), or as a `testing` issue. Anyone may add to
  that list or pick an item up.
- Prefer doing work directly over spawning many sub-agents.

Local, private notes for a particular machine or agent go in `.claude/` (git-ignored).
