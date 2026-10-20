# Art spec: style, sizes and rules for every object

Read this when you make or change art. It says how the game looks and how big every object is, so parts
from any artist or AI drop in without rework. Sizes are measured from the shipped files and the drawing code
(`MainActivity.kt`, `Sea.kt`, `WoodPieces.kt`, `Hud.kt`); `TileSpecTest` checks the number plate rules.

**Units:** board sizes are in **hex units**: 1 = the side of a tile hex (a pointy-top hex is 2 tall and
1.732 wide). On screen a hex unit is about 60–200 px depending on zoom. UI sizes are in dp.

## House style: rivetless brass, steampunk master craftsman

- A tabletop built by a master craftsman: **dark, warm-stained wood** and **smooth old brass**, hand-finished.
- **Brass is rivetless:** seamless cast or hand-beaten brass with soft polished highlights, fine engraved
  lines, bevelled or rolled edges and inlay. **Never rivets.** A screw only where one would really be needed;
  otherwise parts look press-fit, with no bolts or plate seams. Steampunk in
  craft and materials (gears, filigree, engraved scrollwork, lenses where they fit), never in industrial
  hardware.
- **Numbers in the lower third, readable at 45°** (Mauro, 2026-10-11; was "lower half"): every tile's number clearing sits lower
  right, in the lower third, and nothing (the tile's own peaks, trees or buildings, or the tile in front) covers the
  plate at a 45° tilt, so a tilted board never hides it. Themes follow the same rule (theme-spec.md). 3D renders are checked
  for this at every tilt step before they ship; a model whose clearing faces another way is turned in Blender
  and re-rendered, never flipped as a 2D image (that breaks the light and perspective).
- **Brass is worn and aged, never new** (Mauro, 2026-10-11): burnished and worn the way brass gets on a boat, "well loved
  but old". Any honest ageing works, such as softened and rubbed edges,
  darkened recesses, warm patina, a touch of verdigris in crevices, highlights polished bright only where hands
  would touch. No mirror-bright, factory-fresh brass anywhere.
- **Approved exception:** the Brass cage pirate event die (`dice_pe3_*`, `dice_tumble_pe3_*`) keeps its rivets.
  Mauro, 2026-10-10: "is perfect". Don't "fix" it.
- Wood: rich grain, hand-rubbed finish, crisp joinery; colour like `wood_grain.png` stained dark (#4A3020).
- Painted art (tiles, cards) is detailed and warm, like a premium illustrated board game.
- Light comes from the theme's sun, by default the **upper left** (shadows to the lower right); see **Shadows and perspective**.
- No text, numbers or borders painted into art: the game draws numbers, labels and frames.
- Our names only (see `AGENTS.md`); art must be our own or CC0, recorded in `CREDITS.md`.

## Board objects

| Object | Files | File size (px) | On the board | Anchor and notes |
|---|---|---|---|---|
| Terrain tile, painted | `tile_<terrain>.webp`, `_framed` variant | **443 × 512**, the exact box around a pointy-top hex (side 256 px) | 1 hex, edge to edge | Hex fills the file; terrain keys timber, clay, fleece, grain, stone, desert |
| Terrain tile, 3D render | `tile3d_*_{flat,mid,max}` | 512 × 512, orthographic scale 2.65 | 1 hex | Hex centred; one render per tilt step |
| Number plate | drawn by the game | — | radius **0.27**, centre **(+0.36, +0.30)**: **lower right** | Keep a plain clearing of radius 0.31 there; see "Tilt rule" |
| Bandit | `bandit_tilt_0..7` | 384 × 384 (512 at source) | **2.65** hex units square, standing on the plate | Foot anchor at the image centre; opaque base seal radius 0.278 hides the plate; 8 tilt steps, tilt = 0.96·i/7 |
| Homestead / town / road | drawn by the game (`WoodPieces.kt`) today | — | homestead about 0.25 wide, town 0.45, road 0.66 × 0.19 | Stained in the player's colour at runtime; see **Buildings and pieces** for the rendered spec |
| Knight token (Age of Chivalry) | `knight_{normal,pirate}_l{1,2,3}_{active,inactive}` | 512 × 512 (256 until re-rendered) | **1.30** hex units (3× the first 0.62, then 30% smaller), on a ring in the owner's colour | Figure stands on the corner; base at the lower centre |
| Merchant (Age of Chivalry) | drawn by the game today (`MerchantToken.kt`); a theme may supply `merchant_tilt_0..7` | 256 × 256 | a standing pawn about **0.5** hex units tall, base about 0.3 wide, body in the holder's colour | Its base stands at (−0.36, +0.30) on its tile, lower LEFT, mirroring the number plate (#112). Default look: a pawn with a worn-brass coin (coin purse engraved) on its chest; a theme replaces the whole pawn (space: a figure with a platinum coin and cargo crate), drawn upright, base at the bottom centre of the image. Its shadow follows **Shadows and perspective** |
| Port token | drawn by the game | — | radius 0.30 | 2:1 tokens show their terrain art |
| Cliff strip | `cliff_top_1..3` | 768 × 174 | 1.16 along a coast edge × 0.55 deep | Top-down; grass lip at the top edge, surf at the bottom |
| Ocean | `ocean_tile.webp` | 768 × 768 | tiled under the board | Seamless on all four sides |
| Boat (sloop) | `boat_<colour>` | 192 × 192 | **0.95** hex units | Top-down, bow pointing right; sail in a player colour |
| Boat wake | `boat_wake` | 192 × 96 | 0.7 × 0.3 of the boat | Faint, drawn behind the stern |
| Raiders ship | `raiders_ship` | 256 × 256 | **1.15** hex units | Top-down, bow pointing right |
| Gull | `gull_glide`, `gull_flap` (16 frames), `gull_shadow` | 128 × 128 per frame | **0.6** hex units (bird about 45% of its frame) | Top-down, head pointing right; `gull_flap` is a strip of square frames, count = width ÷ height |

### Tilt rule (tiles)

The board tilts up to 55°, so anything in front of (below) the number plate can cover it. Keep the lower part
of each tile low: grass, paths, low walls, small animals. Tall things (trees, peaks, kilns, mine towers,
stooks) go in the upper half and the left side. The plate's clearing (radius 0.31 at +0.36, +0.30) stays plain
ground on every tile, desert included.

## Buildings and pieces: rendered sprites (#41, shipped 2026-10-10)

Homesteads, towns, roads, Ramparts and Capitals are rendered sprites (`bld_*`, listed in the generated
`BuildingArt.kt`, imported by `tools/import_buildings.py`); `WoodPieces.kt` remains the fallback. Two themes:
**Default** (plain town, octagonal Ramparts, small-dome Capitals) and **Pirate** (three-building town about twice
the homestead's mass, stone-and-timber wall with gatehouse, brass-dome keep with deck-colour banners). Homestead
and roads are shared. Sizes below are Codex's final measurements.

**Frame and scale (shared with the bandit and 3D tiles):** 193 px per hex unit, orthographic, upper-left
light, transparent PNG. Pieces use a **256 × 256** frame (1.325 hex units across) with the **anchor at the
frame centre (128, 128)**: the point where the piece touches the board. **8 tilt steps**, tilt = 0.96·i/7, like
the bandit; files `piece_<name>_tilt_0..7.png`.

**Player colour is applied at runtime, so there is one render set per piece, not one per colour.** Each piece has
two layers per tilt step:
- `piece_<name>_tilt_i.png`: the body in **pale, unstained light wood** (grain visible, mid-grey value), which the
  game multiplies by the player's colour, as it stains the wood today.
- `piece_<name>_trim_tilt_i.png`: untinted details drawn on top (brass fittings, windows, flags' poles, the
  Capital's dome), in the rivetless-brass style.

| Piece | Sits on | Footprint on the board | Height | Silhouette |
|---|---|---|---|---|
| Homestead | a corner | **0.25 × 0.15** | roof 0.20, chimney 0.23 | one cottage: pitched roof, chimney, door facing the viewer |
| Town | a corner | **0.45 × 0.25** | tower roof 0.37 | Pirate: back-right hall, front-right workshop, front-left tower (about 2× the homestead's mass) |
| Road | an edge | **0.66 × 0.19**, raised 0.045 | low | 3 directions (vertical, +30°, −30°); a unit edge takes **two sprites centred ±0.17** from its middle, overlapping |
| Ramparts (Age of Chivalry) | under a town | Pirate: square ring **0.55 × 0.55** with a gatehouse, total depth 0.58 | curtain 0.078, gatehouse 0.169 | drawn before (under) the town or Capital |
| Capital (Age of Chivalry) | replaces a town | town cluster plus a central keep 0.215 × 0.195, dome about 0.24 across | dome top 0.475, finial 0.50 | Guilds gold (trade), Academy green (science), Crown blue (politics); never drawn on top of a town |
| Knight token | a corner | 1.30 (3× the first 0.62, then 30% smaller) | — | keep `knight_*`; they already follow this frame idea. Every knight carries a banner: see **Knight activation** |

**Knight activation (standard for every theme, Mauro 2026-10-13):** an active knight is shown by its **banner being raised**.
Every knight model, at every level (L1 included) and in every theme, carries a banner on a pole held by the figure.
Active: the banner is raised high and flies open, in the owner's colour, above the figure's head. Inactive: the same
model with the banner lowered (pole down or banner furled against it). The banner's position is the signal; it
must read at phone size without colour, so the inactive state is never only a greyed-out copy. The `_active` and
`_inactive` file names stay as they are.

**Shadows and perspective (Mauro 2026-10-13: the shadow must make sense from where it is viewed):**
- **The sun can be anywhere and any colour** (a theme may pick a red dwarf low on the horizon or a blue-white star
  overhead), as long as the board stays readable: numbers, pieces and player colours clear, no shadow hiding a
  number plate. What is fixed is that there is **one sun per board**, and every tile, piece, token and shadow
  agrees with it. The default is upper left, about 50° up, so shadows fall to the lower right at about 0.85 × the
  object's height. The shadow always lies **on the surface the object stands on** and is seen with
  that surface's perspective, never with the figure's.
- **On the board:** the ground is the board, so a shadow is foreshortened with the board's tilt (its depth shrinks by
  cos tilt, like the tiles). Sprites rendered per tilt step get their shadow from a ground catcher at that same
  tilt. It starts at the base contact point (no gap, so nothing floats) and is a soft transparent darkening (about
  30% at the foot, fading with distance), never a grey blob, and it never takes the player colour.
- **A turned sprite never turns its shadow.** A piece turned on screen (supply pieces facing their owner, roads
  along a row) needs its shadow drawn separately in board space, or a render made at that heading. Rotating a
  baked-in shadow points it at a second sun.
- **3D board (GLB):** models carry no baked shadow; the renderer's one directional light is the board's sun.
- **Build panel tokens:** a token on a build-menu plate is viewed straight on, not on the tilted board. Render it
  from the fixed preview view (tilt 0.55, the Look & art view) with the theme's sun, standing on an implied floor at
  the plate's lower edge: a short shadow (at most half the token's height) falling lower right, foreshortened as
  a floor seen from that view, baked into the token image and kept inside the plate. Every token in the panel uses
  the same view, sun and floor line, so a row of tokens reads as standing on one shelf.

**Draw order (front-most last):** tiles → number plates → roads → Ramparts → homesteads, towns, Capitals, knights
(back rows first, so nearer pieces overlap farther ones) → the bandit. Corner pieces never reach the number plate
(its nearest corner is about 0.54 hex units from the plate's centre).

**Checks before they count as done:** every piece on the Inventory screen in all six player colours, and on the
board flat and fully tilted, at full view and zoomed in. Roads must meet their corners without gaps at every tilt.

## Cards, dice and UI

| Object | Files | File size (px) | Shown at | Notes |
|---|---|---|---|---|
| Resource card | `card_<resource>` | about **278 × 390** (portrait, 5:7) | 50 × 70 dp in hand, 76 × 110 dp in Inventory, 21 × 30 dp minis | Title banner with our name at the top, resource icon at the bottom |
| Knight card | `card_knight_{normal,pirate}_1..3` | 256 × 360 | 76 × 110 dp | Same 5:7 portrait shape |
| Die face | `dice_<set>_1..6` | **192 × 192**, cropped to the die body | 36 dp on the turn panel | Sets: nr/ni Normal red/ivory, pr/pb Pirate, ae/pe event dice, pe1–pe3 event styles |
| Die tumble | `dice_tumble_<set>_1..10` | **256 × 256** | thrown across the board | 10 frames that loop as one spin |
| Button plate | `btn_{brass,gold,go,stop}` + `_pressed` | 384 × 128 (3:1) | stretched to the button | Rivetless brass; go = green, stop = red, gold = primary |
| Icons | `settings_gear`, `reset_arrow` + `_pressed` | 256 × 256 / 192 × 192 | about 40 dp | Brass, centred, transparent |
| Panels | `wood_grain.png` + `old_brass.jpg` via `brassEdgedWood()` | textures | any | Never plain grey or black boxes |

## Files and checking

- Transparent PNG or WebP (lossless or quality 90), object centred with a little padding, in
  `app/src/main/res/drawable-nodpi/`, lower-case names with the patterns above.
- Animated sprites: frames side by side in one row, square, same anchor in every frame.
- Before art counts as done: show it on the in-app **Inventory** screen and check it on the board flat and
  fully tilted (the Tilt bar on the right edge), at full view and zoomed in.
