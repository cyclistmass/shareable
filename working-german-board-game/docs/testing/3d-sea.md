# 3D sea: test plan

Codex's 3D sea (#63), ported into 0.3.4: the animated water, coast surf, sloops and their wakes, gulls and their
shadows, wavelets and the Raiders' ship on the 3D board, all driven by the shared `Sea` simulation. The harbours stay
the projected ones (#104); the whale, clouds and splashes are the 2D engines drawn over the 3D water. A Space board
keeps its own starfield and critters.

1. Run `:engine:test :ai:test :net:test :app:testDebugUnitTest :app:assembleDebug`.
   `LiveSeaTest` checks every coast edge, closed boundary endpoints, outward axes,
   full-orbit water tap inversion, simulation
   pose mapping, density zero, cloud gestures, signed coast distances, packed distance
   texture precision and bounded wave heights. Existing rules, dice distribution,
   independent reference fixtures and invalid-action tests must pass.
2. Force a fresh run of the existing 2,000 base and 1,000 Age of Chivalry invariant
   games; report wins and turn-limit draws separately. Transport tests also exercise
   delayed optimistic moves and confirmation. Do not substitute short device games
   for these invariant checks.
3. Test invalid poses and settings, renderer/theme/detail switches, density zero,
   animation pause, cloud poke/drag/flick, background/foreground, and illegal or
   wrong-time actions. Rejected actions must leave game state unchanged. Failure
   during loading/rendering must return to the sprite renderer.
4. Use emulator lane 2 only when it is available. Install the exact debug APK, freeze
   a real bot-played late-game board, and capture flat/live pairs at equal tilt and
   zoom, plus full orbits and Inventory. Inspect number plates and all wall feet.
   Run `tools/ci_monkey.sh` with `LIVE_BOARD=1`, `START=chivalry`, and an external
   `OUTPUT_DIR`, followed by `tools/ci_selfplay.sh` with the same live setting.

Measure successful Filament frame intervals, CPU preparation and CPU submission;
they include scheduling/backpressure and are not GPU times. Capture SurfaceFlinger
presentation samples, PSS and exact APK bytes. Report before/after scene counts and
hashes so differing random boards or renderer surfaces are not hidden. Exclude
asset-loading and warm-up windows. Keep artifacts outside the tracked source tree.

Hooks are automation, never user acceptance testing.

`tools/filament/check_sea_assets.py` verifies exported hashes, coast top heights,
glTF axes and fade materials. `LiveSeaPortTest` checks the port: water and surf on the
painted board only, sail tints, a pose fuzz over every slider setting, and the 3D-mode wiring.

## Reproduction

Export with Blender 4.5 and the existing authored source directory:

```sh
blender -b --factory-startup --python-exit-code 1 \
  -P tools/blender/realtime/export_sea.py -- --source "$WGBG_MODEL_SOURCE" --out "$WGBG_ART_OUTPUT"
MATC=/path/to/filament-1.66.1/bin/matc tools/filament/compile_water.sh
python tools/filament/check_sea_assets.py --assets app/src/main/assets/filament \
  --manifests "$WGBG_ART_OUTPUT/manifests" --output "$WGBG_ART_OUTPUT/model-checks.json"
```

The shader adapts the MIT Stylized Water and Boujie code references credited in
`CREDITS.md`. Licences also ship as APK assets. All models, the ocean art and the
coast-distance texture are our own. Foam follows the actual boundary segments;
there is no board shadow, depth-buffer lookup or reflection pass. Refraction
distorts the underwater colour texture. Three bounded Gerstner components deform
the dense playing area of one water mesh, with a coarse far rim.

Addendum 6 uses a new 24-key phase table (eight keys beyond a 16-key loop),
playing forward for 12 seconds and backward for 12 seconds. The earlier shader
was continuous and had no frame count. Cosine phase keys with cubic interpolation give zero speed
at each reversal; all water UV flows, Gerstner waves and surf use this phase.
Boat heights use the same phase, while shared boat motion, gulls and clouds keep
their original forward-running simulation. Tests check symmetry, wraparound and endpoint continuity.

Sea colour now uses main's `BOARD_SATURATION = 0.85` and `BOARD_WARMTH = 0.03`.
The shader applies the same luminance weights and red/blue scaling in sRGB;
uniforms come from the shared Kotlin constants. The sprite ocean adopts main's
existing `BOARD_FILTER` treatment so comparisons show the current sea palette.

Consume the initial cloud/supply grab and suppress board double-tap zoom on a
cloud: rapid pokes must only push/poof it. Check five consecutive real taps at a
tilted view; the camera and game hash must stay unchanged. Recheck drag release.
Test background/foreground and repeated renderer switches.
