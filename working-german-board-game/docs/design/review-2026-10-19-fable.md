# Design review, release 0.3.1 (Fable, 2026-10-19)

A read-only pass over the look and the player's path, from AGENTS.md, `docs/design/art-spec.md`,
`docs/design/theme-spec.md`, the README gallery (`docs/screenshots/*.jpg`), ten frames from the gameplay video
(`base_01..05`, `chiv_01..05`) and the Compose UI in `app/src/main/java/com/mauro/wgbg/`. No code was changed.
Line numbers are from commit 91d935e (0.3.1).

Short verdict: the **board** is the best thing in the app and already looks like a premium illustrated game. The
**panels** are 80% there: one shared wood-and-brass vocabulary (`Tabletop.kt`) is used almost everywhere, but about a
third of the text on screen bypasses it (Material default font, hard-coded colours), the game-over and confirm boxes
mix three button systems, and the Settings panel is a 25-item scroll that hides the three things people change. The
**workflow** has two real dead ends (a "Friend" seat that silently becomes a bot; Host/Join disabled with the reason a
screen away) and one risky dialog (three red buttons side by side, one of which deletes the game).

---

## 1. Theming and visual design pass

### 1.1 What is right (keep it)

- `Tabletop.kt` is a real design system in 210 lines: `Wood` tokens, `brassEdgedWood()`, `brassPlate()`, the 9-slice
  `Plate` buttons (BRASS / GO / STOP / GOLD), `Choice`, `WoodButton`. The splash, New game, Lobby, Join, About and
  Feedback screens are built only from these and read as one object (`Splash.kt`, `NewGameScreen.kt`, `Lobby.kt`,
  `OnlineLobby.kt:412-431`, `Feedback.kt:145-212`).
- The brass is honestly worn: `old_brass` texture + a dark wash + one lit top edge (`Tabletop.kt:126-135`). The
  `btn_*` plates are rivetless. The `chest()` corner plates are press-fit, as the rule asks (`TradePanel.kt:186-192`).
- Action colours are consistent where the shared buttons are used: GoButton green, StopButton/ColorButton red, gold
  = "lit" state on `IconPlateButton` (Trade waiting on you) and the selected `Choice`.
- Board art: tiles, cliffs, buoys, the Bandit, the sea and the rendered pieces all share one light (upper left) and
  one scale. Number plates sit lower right and stay readable at the README's tilt (`board-tilted.jpg`,
  `chivalry-endgame.jpg`). The hand row, mini roll cards and the dice-on-pads are small details done well.
- Side panel: the player's colour dot carrying the score, the gold squiggle/shield for Highway/Watch, and the red
  card count over the limit (`BoardHud.kt:343-372`) pack a lot of state into 360 dp without a table.

### 1.2 Problems, with fixes

Ordered roughly by how often a player sees them.

**T1. Two type systems on every game screen.** `Wood.sans()`/`Wood.title()` set Alegreya; everything that calls
`Text(..., fontSize = .., color = ..)` without a `style` falls back to Material's default sans (Roboto). That is the
whole in-game HUD: side panel news (`Hud.kt:101,108-128`), player strip (`BoardHud.kt:322-367`), turn prompt
(`BoardHud.kt:616`), hand hints and the card detail (`BoardHud.kt:508-516`), every chest sheet (`TradePanel.kt:440-449,
478-483, 540, 548`; `Play.kt:203, 278-298, 328-330, 348-358, 409-432, 543-545`), the whole Settings panel
(`EnvSettings.kt:244-319, 453-492`), the card guide (`Guide.kt:69, 79`), the in-game menu (`MainActivity.kt:1393,
1420-1422` even use `MaterialTheme.typography`), the exit confirm (`MainActivity.kt:1481-1485`) and the Congrats
standings (`Congrats.kt:511-528`; only the title at `:508` sets `ALEGREYA_SC`). Visible in every screenshot: compare
the Alegreya "Hide"/"Menu" plate with the Roboto "Trade, buy a Fortune card, or end your turn" beside it
(`trade-chest.jpg`), or the New game screen's Alegreya against the Settings panel's Roboto (`settings.jpg`).
*Fix:* wrap the app content in a `MaterialTheme(typography = Typography(defaultFontFamily-equivalent))` whose every
style uses `ALEGREYA_SANS` (`MainActivity.kt:183`), and set `LocalTextStyle provides Wood.sans(13.sp)` there. Every
unstyled `Text` then inherits the house font in one change; no need to touch 50 call sites. Then grep
`Text\([^)]*fontSize` (about 48 hits in 10 files) and migrate opportunistically to `Wood.sans`/`Wood.title`.

**T2. Hard-coded colours instead of tokens.** `Color(0xFFEDE6D8)`, `0xFFF1D58A`, `0xFFD9B865`, `0xFFC9A04A`,
`0xFFC0392B`, `0xFF3E9B4F` are typed out by hand in Hud, BoardHud, TradePanel, Play, EnvSettings, Guide, Congrats and
MainActivity (TradePanel even redeclares `WOOD_DARK`, `BRASS`, `INK`, `YES`, `NO` as private vals at `:160-168`, and
`CHEST_*` again at `:522-526`). They happen to match `Wood` today; the first retune of the palette will miss some.
*Fix:* add `Wood.NO = Color(0xFFC0392B)` and `Wood.GOLD = Color(0xFFE0B23A)` (the two missing ones), delete the
private copies, and replace the literals with `Wood.INK`, `Wood.BRASS_LIGHT`, `Wood.BRASS_EDGE`, `Wood.GO`, `Wood.NO`.

**T3. Three button systems in one dialog.** The exit confirm (`MainActivity.kt:1486-1495`) mixes `WoodButton` (Keep
playing) with up to three `ColorButton`s that are all red: "Leave for now", "Quit game" / "Quit", "Delete game". Red is
supposed to mean "no/cancel"; here it also means "the safe option" and "destroy the game". Same mix in
`EndGameConfirm` (`Splash.kt:405-408`) and in the chest sheets, where `ChestButton` (`TradePanel.kt:532-541`) is a
flat rounded rect with a 2 dp brass border, not the 9-slice plate the rest of the app uses, so "Offer"/"Close" in
`trade-chest.jpg` look like a different app from "Roll" in `your-pieces.jpg`.
*Fix:* (a) in the exit confirm, make "Keep playing" the green GoButton, "Leave for now" a BrassButton (neutral: it
keeps the game) and only the destructive one red; put the destructive one on its own line under a short
"This can't be undone" caption. (b) Reimplement `ChestButton` on `PlateBox(Plate.GO/STOP/GOLD/BRASS)` with the same
disabled dimming it has today; the chest's cards and the buttons then share one material.

**T4. The Settings panel is the least themed panel and the one people open most.** `EnvSettings.kt:237-323`: 13
section headers in Roboto 13 sp brass, Material `Slider`s (`:258, 329, 350`; thumb/track recoloured but still a
Material 3 slider with its halo), a Reset icon at the top with no confirmation, and 25 controls in one 360 dp scroll.
The screenshot `settings.jpg` cuts the panel mid-"Theme". Also the section labels are sentences ("Animations (off, or
Density at 0 in the game Menu: nothing moves)", "Zoom, Tilt and Turn sliders on the game screen (pinch, drag and
twist work without them)"): the explanation is in the header, so the header is three lines long.
*Fix:* group into 4 collapsible sections with `Wood.title(15.sp)` headers and one-word labels, help text in
`Wood.sans(12.sp, INK_SOFT)` under the control: **Sound** (music, sounds, songs), **Board** (tiles, tile edges, 3D
board, side panel, view sliders), **Scenery** (the four sliders; Density belongs here too, see W9), **Play** (AI pace,
animations, Report button). Replace `Slider` with a brass groove + brass thumb drawn like `BrassScrollBar`
(`Hud.kt:138-160`) so the three sliders and the scroll bar share one look; the phonograph thumb already does this
for volume, so reuse `VolumeSlider`'s approach with a plain brass knob.

**T5. "Theme" is not a theme.** theme-spec.md defines a theme as names + board art + pieces + cards + dice. The in-app
picker (`EnvSettings.kt:271-275`, labels "Normal" / "Pirate themed") swaps knights, buildings, dice and the event die
only; tiles, cards, resource names and the Bandit stay. The event-die choice (`:276-286`) is a second theme-ish picker
that silently flips the theme to Pirate when tapped (`:281`), which surprises. And `Tiles: Painted / 3D (preview)`
and `3D board (test)` are two more "which board" switches on two different panels (Settings vs the in-game Menu,
`MainActivity.kt:1410`).
*Fix:* one **Look** card on Look & art: a row of theme tiles (Default, Pirate, more later) each showing its knight,
town and die; under it two toggles, "Board: painted / 3D tiles / 3D board (test)" as one three-way `Choice` row, and
"Event die style" only when Pirate is picked. Rename "Normal" to "Default" (the spec's word). Remove the 3D switch from
the in-game Menu once it lives on Look & art, or keep it there but call it the same thing in both places.

**T6. Black backgrounds where the rule says wood.** The Look & art / Inventory page (`inventory.jpg`) sits on
`Color.Black` (`MainActivity.kt:466-473`), as does the whole game surface before the sea draws. New game uses
`Wood.NIGHT` flat (`NewGameScreen.kt:103`) while the Lobby, Join and About use `tableTop()` (wood grain under a
stain). AGENTS.md: "No plain Material grey buttons or black boxes".
*Fix:* `Modifier.tableTop()` on the New game Row and on the inventory canvas (draw the wood before `drawInventory`);
the game surface can stay black behind the sea since the sea covers it.

**T7. Panel text contrast is fine; small text is not.** The Alegreya ink (`#EDE6D8`) on the wood wash passes
easily. What does not: progress-card small print at 6.5 sp (`BoardHud.kt:463`), card names at 8 sp (`BoardHud.kt:461`,
`TradePanel.kt:440, 507`), the "+1"/"−1" mini badges at 8 sp (`BoardHud.kt:675`), the trade "have 0" at 9 sp
(`TradePanel.kt:515`). On a 6-inch phone these are under 2 mm. In `chivalry-endgame.jpg` the improvement bars are 4 dp
tall and read as texture, not as 3 tracks × 4 levels.
*Fix:* floor at 10 sp for anything a player has to read; drop the small print on in-hand progress cards entirely (the
long press already shows it large) and let the card name use the freed space. Make the improvement bars 6 dp with a
1 dp gap and put the track's initial (G/A/C) left of each row.

**T8. Side-panel news lines are machine-made.** `Hud.kt:108-128` prints one line per event, so a 4-player roll gives
"Red +1 Clay / Blue +2 Clay / Orange +2 Stone / White +1 Stone" (board-tilted.jpg) and a 7 gives "Bandit! Players over
the limit must" cut by `maxLines = 1`, then "Blue discarded", "Blue discarded", "Blue discarded 1 Fleece"
(chivalry-endgame.jpg; the engine discards one card per move, `TurnReport.kt:136`, and the first two lines came out
empty). The turn header "Blue's turn · turn 1" repeats the word.
*Fix:* collapse one player's consecutive discards into one line ("Blue discarded 2 Fleece, 1 Grain") in
`TurnTracker` when the previous entry is the same player's DISCARD; raise `maxLines` to 2 on the bandit line; header
as "Turn 1 · Blue rolled 9". Consider showing production as the mini cards only (they are already beside each player)
and keeping the text for events.

**T9. Dice pads before the first roll.** `your-pieces.jpg`: on the first turn after setup ("Your turn: roll the
dice") two empty red rounded squares sit where the dice will land, with no label. They read as broken buttons.
*Fix:* `dicePads = !inSetup(state)` hides them during setup (`MainActivity.kt:1286`), but `Hud.kt:95-99` draws the
pad whenever `dicePads` is true and only the face when `report.rolled`; draw the pad too only once `report.rolled`,
or show two face-down dice on it.

**T10. Buttons that are not buttons.** "Exit" in the in-game Menu is 12 sp text in a 1 dp rounded border
(`MainActivity.kt:1397-1399`); "End saved game" on the splash and "ⓘ About and credits" are bare links
(`Splash.kt:413-418`); the `−` take-back under trade cards is a glyph (`TradePanel.kt:454, 513`). None has the
plate or the wood rim, so nothing tells the thumb they are tappable.
*Fix:* "Exit" becomes a `StopButton` at the bottom of the menu (where leaving belongs, see W7); the two splash links
become `WoodButton`s at 36 dp; the `−` becomes a 22 dp round brass stud.

**T11. Confirm box uses the chest, not the panel.** The "Build a homestead here? ✓ ✗" box (`MainActivity.kt:1360-1376`)
is drawn with `chest()` (planks + corner plates), the same material as the trade and discard sheets, so a confirm
looks like a trade opening. The hit notice ("Blue moved the bandit... You lost 1 Timber") also uses `chest()`.
*Fix:* reserve `chest()` for sheets that hold cards (trade, discard, pick, science bonus) and use a small
`brassEdgedWood(edge = 3.dp)` for yes/no boxes and notices. Material then carries meaning: chest = cards are moving.

**T12. The Congrats chest.** `Congrats.kt:501-536`: confetti and a chest, title in Alegreya SC, then Roboto standings
with "VP" (the only place the app says "VP"; everywhere else it says "points"), and three buttons of three kinds (Brass
"Play again", Wood "See the board", Wood "Exit").
*Fix:* "points" not "VP"; `Wood.sans` throughout; one green GoButton "Play again", one BrassButton "See the board",
one StopButton "Exit"; show the winner's piece (town sprite in their colour) beside the title so the end screen has
some of the board's art in it.

**T13. Small things.** The splash title is 34 sp Alegreya SC with a drop shadow over a plain radial gradient sea
(`Splash.kt:311, 338`): it is the only sea in the app without waves, so the first screen is the least finished one;
draw the real sea (`Sea` with density 0 is cheap) or at least `ocean_tile`. `Choice` uses solid `Wood.BRASS` fill for
the selected state (`Tabletop.kt:156`), flat against the textured plates around it: use `brassPlate()` for the
selected state (the lobby's `BotPicker` already does, `Lobby.kt:206`). The gear icon turns 60° on open
(`EnvSettings.kt:338`), nice; the Reset arrow beside the Settings title has no confirm and also resets sound and the
theme (`EnvSettings.kt:177, 245`), which a tap aimed at the title can trigger.

### 1.3 Theme system notes

- theme-spec.md is good and honest about status (Pirate "shipped (art only)"). The gap is in the app: `pirateTheme`
  is a Boolean (`EnvSettings.kt:154-159`) and the knight/building/dice lookups branch on it (`MainActivity.kt:655,
  663, 1223`). A third theme means touching every branch. *Fix:* an `enum class Theme(val label, val knights,
  val buildings, val dice, val eventStyles, val names: Names)` with DEFAULT and PIRATE today, read through one
  `ThemeArt` object like `GuideArt` already does for cards. Store the ordinal in the same pref key.
- Names are not themeable yet (`RESOURCE_NAMES`, `FORTUNE`, `PROGRESS_NAMES` are top-level vals). Edo (#76) needs
  them; route them through the same `Theme` so the first renamed theme is a data change.
- The Inventory (`inventory.jpg`) is the right checking tool but mixes art the player will see (tiles, pieces, cards)
  with debug rows ("L1 on", "Cliff", "Gull") in Roboto 9 sp labels on black. For the player-facing Look & art page,
  show tiles, pieces in the four colours, cards and dice on wood, with Alegreya labels; keep the full sheet under a
  debug-only "Inventory" button.

---

## 2. Game workflow pass

Walked as a new player with the code as the map. Each step: what happens, what is unclear, a fix.

### 2.1 Start screen (`Splash.kt`)

Buttons: Resume game (if saved), Resume online game CODE (if left), New game, Log in / Account, Settings; links About
and credits, version, Support (hidden until a Ko-fi name exists). Good: the first green button is the one most players
want next.

- **W1. Two Settings.** Settings here opens the same `SettingsPanel` as the in-game gear, including "AI pace" and
  "Report button on the game screen", before a game exists; and the in-game Menu has Sound, 3D board and Density
  again (`MainActivity.kt:1407-1411`). A player who changes Density in the Menu then looks for it under Settings >
  Scenery and does not find it. *Fix:* one Settings panel, same contents in both places (see T4), opened from the
  splash and from the gear; the in-game Menu keeps only game actions (W7).
- **W2. "End saved game" link under Resume** is good, but the confirm says "End this game for good? It can't be
  resumed after this" with red "End game" and wood "Keep it": the safe option should be the green one.

### 2.2 New game (`NewGameScreen.kt`)

Left panel: name and photo, three seat cards (Friend / Bot / Empty with Easy / Greedy / Strong), ruleset, points,
Report / Intro / Look & art. Right panel: dice (True random / Card deck, deck size), Account, Play, Resume, Host a
room, Join a room, version.

- **W3. A "Friend" seat in a local game is a Greedy bot, silently.** The seat card says "Invite from the lobby, or
  pass this phone" (`NewGameScreen.kt:226`), but `WatchGameScreen` gives every non-you seat a bot
  (`MainActivity.kt:577-581`), and there is no pass-the-phone mode. The default setup has seat 1 as FRIEND
  (`GameSetup.kt:350-352`), so a new player who taps Play plays against a bot named "Blue" that they thought was a
  friend. This is the biggest dead end in the app. *Fix:* until hot-seat exists, drop FRIEND from local seats: the
  seat card offers Bot / Empty, and the lobby (which already has its own seat model) is where friends come in. Change
  the default to Bot, Bot, Bot. When hot-seat lands, bring FRIEND back with the text "Pass this phone on their turn".
- **W4. "Strong" plays as Greedy** (`GameSetup.kt:331`, `MainActivity.kt:580`). Label a level the engine does not have
  and the player blames the game when Strong loses. *Fix:* hide STRONG until #6 lands.
- **W5. Host / Join are disabled with the reason elsewhere.** They render as dimmed `Choice`s (`:181, 185`) while the
  name is "You", with a yellow hint line above (`:170`). The hint is good; the dead buttons still read as "online is
  broken" (the comment at `:169` says that was the report). *Fix:* keep the buttons live; a tap with no name focuses
  the name field and shows the hint beside it. Also: the right panel has Play, then Resume, then Host/Join. Resume
  belongs above Play (it is the more likely next tap when it exists) or, better, only on the splash.
- **W6. The dice setting is the biggest control on the screen** (two 46 dp rows + deck-size row + a note) for a
  choice most players never make; the ruleset, which decides everything, is one 40 dp row. *Fix:* collapse dice to one
  row "Dice: True random ▾" that opens the choice; give the ruleset two cards with a line each ("Base game · 10 points ·
  2-4 players", "Age of Chivalry · 13 points · 3-4 players · knights, Raiders, progress cards").
- Points +/− with a 70 dp label is fine. "Tap photo to pick one" could be the token's own caption.

Sketch (landscape, two panels kept):

```
┌ Players ──────────────────────────────┐ ┌ Game ─────────────────────┐
│ (●) Mauro            [photo]          │ │ ▣ Base game   10 pts 2-4  │
│ Seat 2 [Bot ▾ Greedy] Seat 3 [Bot]    │ │ ▢ Age of Chivalry 13 3-4  │
│ Seat 4 [Empty]                        │ │ Points  − 10 +            │
│                                       │ │ Dice: True random ▾       │
│ [Look & art] [Intro] [Report]         │ │ ▌▌▌▌▌▌▌ Play ▌▌▌▌▌▌▌ (green)│
└───────────────────────────────────────┘ │ [Host a room] [Join a room]│
                                          │ Guest on this phone · Sign in│
                                          └───────────────────────────┘
```

### 2.3 Look & art (`MainActivity.kt:1248-1272`, `Screen.LOOK`)

Opens the full game screen in inventory mode with Done, Card guide and the Menu/gear. The theme switch lives inside
Settings behind the gear; a player looking for "how do I get the pirate knights" has to open Menu > gear > scroll to
Theme. *Fix:* see T5: the theme row on the page itself, with the pieces redrawing as you tap.

### 2.4 First placement

Prompt "Tap a glowing corner to place a homestead", glowing targets, a confirm chest with ✓/✗ (`Play.kt:443-446`,
`MainActivity.kt:1360-1376`), and the build menu shows the piece due (homestead or road). Good. Two notes:

- The confirm box is placed top or bottom to avoid covering the spot (`Play.kt:482`); it still covers the prompt
  when it goes top. *Fix:* when the box goes to the top, hide the prompt (the box's own title says what is asked).
- Your unplaced pieces wait on the water (`your-pieces.jpg`); a new player does not know they are theirs. *Fix:* a
  one-time caption "Your pieces" above them on the first turn, in the side panel's style.

### 2.5 A normal turn

Roll (brass "Roll" plate bottom right), production shows in the side panel and as mini cards; then the build menu
down the right edge with only what you can afford, Trade and End turn (hourglass) bottom right, Fortune cards as
worded WoodButtons above them (`Play.kt:236-251`).

- **W8. End turn is an icon only** (`BrassPlate.kt:157-169`, hourglass). It is the most-pressed button and the one
  a new player hunts for. Trade's two arrows are learnable; the hourglass is not obvious. *Fix:* keep the icon and add
  a 10 sp "End turn" under it; the plate is 66 × 54 dp, there is room.
- Fortune cards: hints are good ("1 Sentinel: Move the bandit..."). They are plain `WoodButton`s with text while the
  build menu beside them is icon plates; two visual systems for "things I can do". *Fix:* a Fortune card in hand is a
  card: show it as a `ProgressCardFace` in the hand row (the Age of Chivalry progress cards already work this way)
  and tap it to play. The hand row then holds everything you own.
- Bandit on a 7: "You rolled 7: move the bandit first, then you can play cards" is clear. The confirm's "Rob Blue (4
  cards)" is clear. The discard sheet with "Tap a card to add it · hold to take it back" is clear and the Clear /
  Random buttons are a good safety net.
- Trade chest: "Tap top to ask · bottom to offer" is a clever single-row design and the caption says it; the Harbour
  tab shows rates under the cards. One gap: after a bank trade nothing confirms it except a side-panel line; the
  `tradeSummary` ("Now: 4 Grain (+1)") exists (`TradePanel.kt:562`) but is easy to miss. *Fix:* flash it in the hand
  row for 3 s in gold.
- Bots' offers: the prompt says "Blue offers 1 Clay for 1 Grain: Counter with what you want, or No" and the chest
  auto-opens; the 5 s auto-No for offers you can't pay (`AUTO_NO_SECONDS`) is a nice touch but has no visible
  countdown. *Fix:* a thin brass bar draining across the chest top.

### 2.6 Age of Chivalry extras

Raiders track with "5 vs 3" and the 3-step warning (`BoardHud.kt:321-335`), improvement bars, progress cards in hand
dimmed when unplayable with a reason on tap (`BoardHud.kt:542-547, 560-566`), Knight actions in the build menu
(Raise / Supply / Promote / Ramparts), card pickers as cards (#123). This is the most carefully explained part of the
app. Notes:

- "Supply a knight" (ACTIVATE) and "Raise a knight" (BUILD) are both verbs a new player will mix up; the icons (shield
  + pennant vs shield + bar) are close. *Fix:* "New knight" and "Supply knight" (and "Promote knight"), and give New
  knight a plain shield, Supply a raised banner (that is the art spec's own activation signal).
- The event die's three gate faces have no legend; the side panel shows the die but not what the colour meant. *Fix:*
  the roll line reads "Blue rolled 8 · Academy gate" when the event is a gate.
- "Improve your towns" (IMPROVE_CITY) is a worded WoodButton among icons (`Play.kt:241-245`). *Fix:* three small
  plates in the three track colours in the build menu, enabled when affordable, with the level dots on them.

### 2.7 In-game Menu, Settings, Leave / Quit / Resume, End saved game

Menu (brass "Menu" → "Hide" top right) opens a 340 dp wood panel: gear, "Turn N · vX", Exit; Card guide; Report a
bug or idea; Online room; Sound and songs; 3D board; Density; Fit / 3D collection / Dice; player panel; Wins; log
(`MainActivity.kt:1380-1424`). The gear opens Settings instead, with Pause / speed / Watch bots at the top.

- **W7. The menu is a drawer of everything.** Game actions (Exit, Fit, Card guide, Report) are mixed with settings
  (Sound, 3D, Density, Dice) and debug (Wins, log, 3D collection, Pause/speed/Watch bots under the gear). *Fix:*
  Menu = five buttons: **Card guide**, **Fit the board**, **Settings** (gear), **Report a bug or idea**, and at the
  bottom **Leave game** (StopButton). Everything else moves into Settings (W1, T4) or behind a debug flag. The "Turn
  N · vX" line becomes the panel title.
- **W9. Density sits in the Menu "not front page" while the four scenery sliders sit in Settings.** They are one
  control. *Fix:* Density is the first slider of the Scenery group in Settings; the Menu loses it.
- **W10. The leave dialog has three red buttons** (`MainActivity.kt:1486-1495`): "Leave for now", "Quit game" (local)
  or "Quit" (online guest) or "Delete game" (online host), plus wood "Keep playing". The explanation is one 13 sp
  paragraph. A thumb that wanted "Leave for now" and hits "Quit game" gets a second confirm (good), but "Delete game"
  for the host ends it for everyone with that one confirm. *Fix:* see T3, plus:

```
┌ Leave this game? ───────────────────────────┐
│ You can come back to it from the start      │
│ screen any time.                            │
│ [ Keep playing ] (green)  [ Leave for now ] (brass)
│ ····································· │
│ End it for good   [ Quit game ] (red, asks again)
└─────────────────────────────────────────────┘
```

  Online host: the red one reads "Delete game for everyone" and the second confirm names the other players.
- Resume works from the splash and from New game (two places, W5). After Resume, the game screen gives no sign it
  is a resumed game. *Fix:* the turn prompt's first line "Welcome back · turn 23 · your turn" for 4 s.
- "End saved game" on the splash and "Quit game" in the game do the same thing with two names. *Fix:* one name,
  "End game", in both places.

### 2.8 Online: Host and Join (`Lobby.kt`, `OnlineLobby.kt`)

Host: New game → Host a room → lobby with room code on a brass plate, Copy link / Share / Show QR, four seat rows
with bot pickers, Start game, "New game settings…" (which leaves the room, `MainActivity.kt:460`). Join: Join a room →
name + code with live validation ("Room ROVER-42" / "That doesn't look like a room code") → first open seat. This
flow is good; the QR on a parchment card is lovely.

- "New game settings…" silently deletes the room and returns to New game; guests see the room vanish. *Fix:* label
  it "Close room and change settings" or let the host edit ruleset/points in the lobby (the right card already shows
  them).
- The lobby has no Leave button for a guest (Back does it, `:456`). *Fix:* a small StopButton "Leave room" under the
  QR card.
- `OnlineConnectionStatus` ("Not synced: 2 moves waiting for the server") appears top centre over the board. Good.
  When it clears there is no "Synced" flash, so a player who saw it does not know it resolved. *Fix:* a 1.5 s green
  "Synced" fade.

### 2.9 Game over (`Congrats.kt`)

Confetti in the winner's colour, standings, Highway / Watch, Play again (local) / See the board / Exit; "Results"
returns from the board. Good shape. Besides T12: there is no line about *why* (the winning build), and online games
offer no Play again, so the host must go New game → Host → re-invite. *Fix:* a one-line "Won with a town at the 9
Fields" is cheap from the last move; online "Play again" = host creates a new room with the same seats and the
guests get a "Blue invites you to a rematch" GoButton on their end screen.

---

## 3. Top 10, impact versus effort

Each a one-line change Mauro can pick. "Effort" is a guess for one agent session.

| # | Change | Impact | Effort |
|---|---|---|---|
| 1 | Set Alegreya as the app-wide default text style in `MaterialTheme`/`LocalTextStyle` at `MainActivity.kt:183`, so every unstyled `Text` is in the house font (T1) | High: fixes the single most visible inconsistency on every screen | Small (1 line + a look at line heights) |
| 2 | Drop FRIEND from local seats and default seats 2-4 to Bot; bring it back with real hot-seat (W3) | High: removes the "my friend is a bot" dead end | Small |
| 3 | Rebuild the leave dialog: green Keep playing, brass Leave for now, one red destructive button on its own line (W10, T3) | High: safety, and the first dialog most players meet | Small |
| 4 | Rebuild `ChestButton` on `PlateBox` so chest sheets use the same brass plates as the rest (T3) | Medium-high: trade, discard and pick sheets look native | Small-medium |
| 5 | Add "End turn" caption under the hourglass; "New / Supply / Promote knight" wording and distinct icons (W8, 2.6) | Medium-high: the two most hunted-for buttons | Small |
| 6 | One Settings panel in 4 collapsible groups (Sound, Board, Scenery incl. Density, Play) with brass sliders; in-game Menu shrinks to Card guide / Fit / Settings / Report / Leave (W1, W7, W9, T4) | High: settings findable, menu readable | Medium |
| 7 | Theme row on Look & art (Default / Pirate tiles that show knight + town + die), with Board and Event-die style under it; `pirateTheme` Boolean → `Theme` enum (T5, 1.3) | Medium: makes the theme system real for #76 | Medium |
| 8 | Merge consecutive discards into one side-panel line, 2-line bandit message, "Turn 3 · Blue rolled 9" header (T8) | Medium: the side panel is read all game | Small |
| 9 | Fortune cards as card faces in the hand row (tap to play) instead of worded buttons (2.5) | Medium: one place for everything you hold | Medium |
| 10 | `tableTop()` wood under New game and the Look & art page; real sea behind the splash; 10 sp text floor (T6, T7, T13) | Medium: polish on the first three screens | Small-medium |

Nice-to-have after these: "Synced" flash and lobby Leave button (2.8), rematch from the end screen (2.9), hide
"Strong" until #6 (W4), replace the `Wood` colour literals with tokens (T2), a one-time "Your pieces" caption (2.4).
