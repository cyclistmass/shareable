# Theme spec

What a theme for the Working German Board Game must and may contain (Mauro, 2026-10-11). A theme swaps the
names and art; the rules stay the same. Geometry, file sizes and tilt steps are in [art-spec.md](art-spec.md): read
it with this page.

## Required

1. **Names** for everything a player reads:
   - the 5 resources and the 3 commodities;
   - homestead, town, road, knight (3 levels), Ramparts, the 3 Capitals, the Bandit, the Raiders;
   - the 3 progress decks and their cards, and the Fortune cards (base game).
2. **Board art**: 6 tiles (5 terrains and the desert), coast and cliffs, the sea texture, and ports showing each
   resource.
3. **Pieces**: road, homestead, town, knight levels 1 to 3 (an active knight raises its banner; see **Knight activation** in art-spec.md), Ramparts at 3 heights,
   and the 3 Capitals. Each is split into a body tinted in the player's colour and an untinted trim.
4. **The Bandit, the Raiders' ship, and the Merchant indicator** (Age of Chivalry: who holds the Merchant and on which
   tile; a coin-like token in the holder's colour with the theme's trade symbol on it).
5. **Cards**: the 5 resources and the 3 commodities (commodities framed differently from resources), a background
   per progress deck tied to its commodity, and the Fortune cards.
6. **Dice**: the two number dice and the event die faces.

## Recommended sizes

How big each object ends up on the board, in **hex units** (1 = a tile hex's side; a tile is 2 tall and 1.732
wide). Make a theme's objects at these sizes so they sit with the shipped ones; "frame" is the square image or model
bounding box, "body" the visible object inside it. These are the sizes the game draws today (scale factors already
applied); the art spec gives the base frames and file sizes they come from.

| Object | Recommended size on the board | Where it sits |
|---|---|---|
| Tile | 1 hex, edge to edge | Number clearing kept plain: radius 0.31 at (+0.36, +0.30) |
| Number plate | radius 0.27 | Lower right of the tile, (+0.36, +0.30) from its centre |
| Homestead | body about **0.45 wide × 0.27 deep** (frame 2.4) | On a corner, door to the viewer |
| Town | body about **0.81 × 0.45**, about twice a homestead's mass | On a corner |
| Road | **0.53 × 0.15**, two pieces per edge, centred ±0.20 from the edge's middle | Along an edge |
| Ramparts (Age of Chivalry) | ring about **0.64** across; the town on top at 0.88 of its size | Under a town or Capital |
| Capital (Age of Chivalry) | a town plus a central keep, about **0.85** across, dome top highest | Replaces a town |
| Knight (Age of Chivalry) | frame **1.30** on the flat board, **0.64** on the 3D board (0.49 of flat) | Stands on a corner, base at the lower centre |
| Bandit | frame **2.65** | Feet on the number plate; its base hides the plate |
| Merchant (Age of Chivalry) | a standing pawn about **0.5** tall, in the holder's colour | Stands on its tile at (−0.36, +0.30), lower LEFT, mirroring the number plate, so it never covers the number and shows on a tilted board. Each theme may replace the pawn |
| Port buoy | radius about **0.39** | 0.80 out to sea from the middle of its coast edge |
| Cliff strip | **1.16** along a coast edge × **0.55** deep | Along each coast edge |
| Boat (sloop) | frame **1.08**, hull about 0.76 long | On the sea |
| Raiders' ship | frame **1.40**, hull about 1.0 long | On the sea, larger than any boat |
| Gull | frame **0.32**, wingspan about 0.25 | Over the sea |
| Whale (optional) | frame up to **5.3**, body well inside it | On the open sea, clear of the coast |

Rules of thumb: a piece's footprint stays inside the corner's share of the tiles around it (no wider than about 0.85),
nothing on a tile reaches the number plate, and sea life and boats stay smaller than a tile (the whale aside).

## Performance budgets (3D board)

A theme gets **one triangle budget for the whole board**, not a cage per object (Mauro, 2026-10-13: "I am a fan of
people making magic"). The table gives each object type a default share. A theme may spend more on one type and less
on another, as long as the **worst-case board** stays under the ceiling. Write the theme's ledger (its own column
of this table) in the theme's notes.

**Ceiling: 300,000 triangles drawn per frame** at LOD0 on the worst-case board: 4 players, Age of Chivalry, late game,
everything on the table at once. For 5 to 6 players (30 tiles) the ceiling is 420,000. Also:

- about **150 draw calls** at most, since each GLB group is one draw;
- **no textures in the GLBs**: colour lives in the vertex colours, as in every shipped GLB. A theme that needs one
  can ship one shared atlas of at most 2048 × 2048;
- **no baked shadows**, and transparent surfaces kept small: glass domes, spouts and mist cost more per pixel
  than any triangle.

| Object | Default LOD0 | LOD1 | Most on the worst board | Worst subtotal |
|---|---:|---:|---:|---:|
| Terrain tile (with its props) | 7,500 | 1,900 | 19 | 142,500 |
| Number plate | 150 | — | 18 | 2,700 |
| Road | 400 | — | 60 (15 × 4) | 24,000 |
| Homestead | 500 | — | 20 (5 × 4) | 10,000 |
| Town | 1,000 | — | 16 (4 × 4) | 16,000 |
| Ramparts | 800 | — | 12 (3 × 4) | 9,600 |
| Capital | 1,500 | — | 3 | 4,500 |
| Knight L1 / L2 / L3 | 2,000 / 2,500 / 3,000 | — | 24 (2 of each level × 4) | 60,000 |
| Bandit | 4,000 | — | 1 | 4,000 |
| Raiders' ship | 3,000 | — | 1 | 3,000 |
| Merchant | 600 | — | 1 | 600 |
| Port (buoy and dock) | 800 | — | 9 | 7,200 |
| Boat | 600 | — | 6 | 3,600 |
| Critter (whale, space whale, …) | 4,000 | 1,000 | 3 | 12,000 |
| **Board** | | | | **299,700** |

The LOD1 column is the player's low-detail setting (Detail: LOD1): with LOD1 tiles the worst board drops to about
193,000.

**Where the default board stands today:**

- The shipped tiles peak at 7,253 triangles. The buildings reach 1,422, which is over the homestead and town shares.
- The measured full board is 121k triangles (default) and 140k (pirate) without knights, in 83 to 85 draws.
- The space pieces come in well under (knights 2,440 to 2,682).
- The knight exporter's current ceilings (5,000 / 6,500 / 8,000) are over the table. Knight exports move to
  2,000 / 2,500 / 3,000 with the v4 knights (Crest), and the default theme's ledger is written then.

**What the totals do on a phone.** These are rough figures for simple vertex-colour shading like ours. They are not
measured yet on a phone: the only measurements so far are from a desktop browser, which held 60 fps at 140k.
Profiling the 3D board on a real mid-range phone replaces these guesses.

| Triangles drawn | What to expect |
|---|---|
| up to ~300k | Smooth at 60 fps on phones from about 2019 on. The target. |
| 300k – 600k | Recent phones stay smooth. Older mid-range phones drop frames when the board is tilted with every piece out, and run warm in a long game. |
| 600k – 1M | Only flagships hold 60 fps; battery drain is noticeable over a game. |
| over 1M | Stutters on most phones. Don't ship. |

Triangles are rarely the first thing to run out. Before the triangle count bites, these do: too many draw calls
(many small separate meshes), overdraw from transparent layers, and big textures.

**Where to spend for magic.**

- Spend on **silhouette at phone size**: a piece is about 40 px tall on a phone. Detail that doesn't change its
  outline at that size is wasted. Check it in the review sheets' black silhouette row.
- **Few-on-the-board pieces can be lavish.** The Bandit, the Capitals, the Raiders' ship and the Merchant appear once
  or three times, so a few thousand extra there costs little.
- **Many-on-the-board pieces must be lean.** Every triangle on a road costs 60, on a knight 24, on a homestead 20.
- **Free magic:**
  - vertex-colour gradients and baked ambient occlusion;
  - emissive glow;
  - motion: banners, critters, a turning windmill, which move existing triangles and add none.

## Optional

Critters: boats, birds, a whale or other sea life, clouds, each a module with a model and a movement spec (see [critter-spec.md](critter-spec.md)). A music set list and sounds (dice, Raiders), an intro fly-over.

**Idle animations (3D board only).** Any tile or token may carry a small idle animation clip in its GLB. Examples:
a windmill turns, grain sways, smoke rises from a town, a knight's banner stirs, the Bandit shifts its weight, the
Merchant's coin glints. Mauro chose the 3D board only (2026-10-13); the flat board stays still.

- **Small.** Movement stays inside the object's own footprint and never crosses a number plate or a neighbour's
  corner.
- **Calm.** A loop of 4 s or longer, or a short `once` played every so often, each object at a random phase so the
  board never moves in step.
- **Moves existing triangles only.** No extra triangles, no particles in the count. The triangle budget above still
  holds.
- **Never changes how state reads.** A raised or lowered banner, a piece's colour and its outline still read in
  every frame of the clip.
- **Can be turned off.** A player setting must be able to stop it (the setting comes with the first idle clip).

## Rules every theme passes

- **The number is always readable.** The plate sits in the **lower third** of its tile, on a plain clearing. At a 45°
  tilt nothing covers it: not the tile's own features (peaks, trees, buildings), and not the tile in front. Tall
  things go in the upper part of the tile and to the sides. 3D models are turned in Blender to meet this, never
  flipped as images, and checked at every tilt step before they ship.
- **Pieces stand out.** Every piece in all four player colours reads clearly on every tile. The game adds
  saturation and brightness to pieces and takes a little off tiles, so the art should not fight that.
- **Sizes and formats** follow the art spec: 8 tilt steps for everything on the board, 16 headings for anything
  that sails or flies, the frame sizes and contact points given there.
- **Own names only.** Never the original game's names, in any language.
- **Licences.** Art is CC0 or our own, and every source is listed in `CREDITS.md`.
- **Costs stay the same.** A theme renames; the recipes (a road is one of the first resource and one of the second,
  and so on) are unchanged unless the theme declares its own cost table through issue #79.

## Themes

| Theme | Status | Names |
|---|---|---|
| Default | shipped | Timber, Clay, Fleece, Grain, Stone; Parchment, Fabric, Silver |
| Pirate | shipped (art only) | default names |
| Edo fishing village (#76) | planned | Bamboo, Kawara, Silk, Rice, Platinum (or Copper); commodities proposed Washi, Brocade, Koban; Knights are Samurai. Other piece names proposed on #76 |
