# ETReTerraForged vs ReTerraForged Compatibility Review

Reviewed local repositories:

- `/var/home/scott/Repos/ETReTerraForged`
- `/var/home/scott/Repos/ReTerraForged`

## Executive Summary

`ETReTerraForged` does **not** currently implement everything added by the less mature `ReTerraForged` fork. ET is clearly the broader and more actively developed fork, but the shoreline/beach work in Re is a distinct subsystem rather than a differently named implementation inside ET.

The two forks are also not directly compatible artifacts:

- They target different Minecraft versions: ET targets `1.21.1`, while Re targets `1.20.1`.
- They use the same mod id and archive name, so they cannot reasonably coexist in the same modpack as separate mods.
- Their preset/config schemas diverge. Re adds a required top-level `world.beaches` object; ET does not have that field.
- Their internal cell model and terrain categories diverge around shoreline state. Re adds beach material/type/alpha fields and river/lake shore terrain categories; ET does not.

The practical conclusion is:

> ET is the better base for continued development, but Re's shoreline/beach work would need to be ported deliberately. It is not already covered by ET except for narrower island beach controls and unrelated river/lake geometry improvements.

## Version And Loader Compatibility

ET is a `1.21.1` Fabric + NeoForge project:

- [ETReTerraForged/gradle.properties](/var/home/scott/Repos/ETReTerraForged/gradle.properties:2) sets `minecraft_version=1.21.1`.
- [ETReTerraForged/settings.gradle](/var/home/scott/Repos/ETReTerraForged/settings.gradle:10) includes `common`, `fabric`, and `neoforge`.

Re is a `1.20.1` Fabric + Forge project:

- [ReTerraForged/gradle.properties](/var/home/scott/Repos/ReTerraForged/gradle.properties:2) sets `minecraft_version=1.20.1`.
- [ReTerraForged/settings.gradle](/var/home/scott/Repos/ReTerraForged/settings.gradle:10) includes `common`, `fabric`, and `forge`.

Both use the same mod id:

- [ETReTerraForged/gradle.properties](/var/home/scott/Repos/ETReTerraForged/gradle.properties:5) uses `mod_id=reterraforged`.
- [ReTerraForged/gradle.properties](/var/home/scott/Repos/ReTerraForged/gradle.properties:5) uses `mod_id=reterraforged`.

That means compatibility should be treated as source-level or feature-level compatibility only. They are not drop-in compatible binaries, and they are not safe to load together.

## What Re Adds

The less mature fork's notable new capability is a configurable shoreline/beach subsystem. It covers:

- Ocean shoreline eligibility and geometry.
- River shoreline eligibility and geometry.
- Lake shoreline eligibility and geometry.
- Surface material selection across sand, gravel, stone, mud, and red sand.
- Configurable material variance and climate bias.
- A worldgen feature that paints shoreline surface columns based on evaluated beach type/material.
- UI/translation exposure for these controls.

The schema anchor is Re's top-level `WorldSettings.Beach` config:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:13) adds `Beach.CODEC.fieldOf("beaches")`.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:140) defines `Beach`, containing `variance`, `ocean`, `river`, and `lake`.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:184) defines the material palette.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:338) defines ocean geometry controls.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:361) defines river bank geometry controls.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:381) defines lake shore geometry controls.

Re wires those defaults into every preset through `makeBeachSettings()`:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/Presets.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/Presets.java:285)

The runtime evaluator is also explicit:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:33) evaluates ocean, river, and lake shoreline alpha.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:49) classifies river/lake shore terrain.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:225) selects shoreline materials.

The material painting step is separate from biome classification:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/feature/BeachSurfaceFeature.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/feature/BeachSurfaceFeature.java:79) skips cells without a resolved beach type/material.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/feature/BeachSurfaceFeature.java:98) paints the surface and filler blocks.
- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetConfiguredFeatures.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetConfiguredFeatures.java:119) registers the configured beach surface feature.

## What ET Has Instead

ET has substantial independent development, but not the same global shoreline subsystem.

ET's `WorldSettings` has only continent, control points, and properties:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:9)

There is no top-level `world.beaches` config equivalent in ET.

ET does have island/archipelago coastline controls:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/IslandSettings.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/IslandSettings.java:17) exposes `offshoreDepth`, `beachWidth`, and `beachCoverage`.
- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/ArchipelagoPopulator.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/ArchipelagoPopulator.java:121) consumes `beachWidth` and `beachCoverage`.
- [same file](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/ArchipelagoPopulator.java:145) consumes `offshoreDepth`.
- [same file](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/ArchipelagoPopulator.java:210) classifies island terrain into shallow ocean, island beach, island mountains, and island.

That is not equivalent to Re's shoreline subsystem. ET's controls are island generation controls. They do not provide configurable ocean/river/lake surface materials, material palettes, shore eligibility gates, or shore painting depth.

ET also has much more advanced river/lake terrain shaping in places, especially in `UpliftRiverCarver`:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java:151) widens lake-like areas with shoreline warp.
- [same file](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java:180) recalculates river valley layout from those dynamic bounds.

That is better terrain geometry, but it is not the same compatibility surface as Re's exposed river/lake beach material system.

## Direct Feature Parity Matrix

| Capability from Re | Present in ET? | Notes |
| --- | --- | --- |
| Top-level `world.beaches` config | No | ET's `WorldSettings` does not include it. |
| Ocean shoreline coverage/depth/slope/height controls | No | Re has `WorldSettings.Ocean`; ET has only biome/control-point beach threshold plus island controls. |
| River shoreline coverage/depth/slope/width/bank controls | No | Re adds `WorldSettings.River` shoreline config separate from base river settings. ET has river generation settings and uplift carving, but not these beach controls. |
| Lake shoreline coverage/depth/slope/bank controls | No | Re adds `WorldSettings.Lake` shoreline config. ET has lake/river geometry, but not the painting eligibility config. |
| Ocean geometry controls: `coastBandScale`, `transitionBias`, `continuityPadding` | No | Re's `OceanGeometry` is absent from ET. |
| River/lake shore geometry controls | Partial conceptually, no config parity | ET has richer terrain shaping, but not Re's exposed `bankWidthScale`, `bankFalloffBias`, `shoreRingScale`, or `shoreFalloffBias` config surface. |
| Beach material palette: sand/gravel/stone/mud/red sand | No | Re has `MaterialPalette`; ET does not have per-shore material selection. |
| Climate/noise-driven material variance | No | Re's `BeachEvaluator.selectMaterial()` uses sediment, climate, gradient, and beach material noise. ET lacks the beach material fields. |
| Separate ocean/river/lake beach types | No | Re has `BeachType`; ET does not. |
| Dedicated surface painting feature | No | Re registers `BEACH_SURFACE`; ET has no equivalent configured feature. |
| Island beach width/coverage/offshore controls | Yes, ET-only shape | ET has archipelago island controls; Re's shoreline system is not equivalent to ET's island-specific system. |
| More mature terrain/rivers/optimization work | Yes, ET-side | ET has broader newer development, especially for 1.21.1/uplift/islands, but that does not subsume Re's shoreline config. |

## Internal Model Divergence

Re extends `Cell` with shoreline state:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:44) adds beach surface/material noise and alpha.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:47) adds ocean/river/lake shore alpha/distance fields.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:66) stores beach type and material.

ET's `Cell` does not contain those fields. It has different newer terrain-generation fields instead:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:31) adds ET-specific continent/water-table fields.
- [same file](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:54) only has `beachNoise` for the older/simpler beach behavior.

Re also adds explicit terrain categories for inland shore classification:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/TerrainType.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/TerrainType.java:17) registers `RIVER_SHORE` and `LAKE_SHORE`.

ET instead adds island-oriented terrain categories:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/TerrainType.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/TerrainType.java:29) registers `ISLAND`, `ISLAND_BEACH`, `ISLAND_MOUNTAINS`, and `MUSHROOM_FIELDS`.

This confirms the forks evolved in different directions. Their terrain state models are not just differently named versions of the same feature.

## Beach Detection Difference

ET's current beach detection is the old/simple path:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/BeachDetect.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/BeachDetect.java:20) marks coast cells as `BEACH` when they are below the beach control point and pass a gradient test.
- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/WorldLookup.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/WorldLookup.java:67) has a second simple fallback that converts low coast cells into beach terrain.

Re replaces that with evaluator-driven beach detection:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/BeachDetect.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/BeachDetect.java:21) calls `BeachEvaluator.evaluate()` for every tile cell.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/tile/filter/BeachDetect.java:34) then applies a continuity pass.
- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/WorldLookup.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/WorldLookup.java:94) performs comparable per-sample evaluation for lookup paths.

This is a substantive implementation difference, not cosmetic divergence.

## Config Compatibility

Preset/config compatibility is weak.

Re presets require or generate `world.beaches`:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:13)

ET does not read or preserve a `world.beaches` field:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:9)

The control point fields also differ:

- Re uses optional mushroom island control points: [ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:75)
- ET uses required island control points: [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java:71)

So a Re preset is not a faithful ET preset even if decoding ignores unknown fields. At minimum, Re's beach behavior would be dropped. In the other direction, ET's island settings and newer terrain controls are not available in Re.

## Compatibility Judgment

The forks are compatible only in the broad sense that they share ancestry, package naming, and many baseline concepts. They are **not functionally equivalent** for shoreline/beach behavior.

ET does not currently implement Re's less mature shoreline/beach capabilities in another form. The closest ET equivalents are:

- Island-specific `offshoreDepth`, `beachWidth`, and `beachCoverage`.
- More advanced uplift/river/lake geometry.
- Existing simple coast-to-beach classification.

Those do not cover:

- Global ocean shore material painting.
- River/lake shore painting.
- Per-shore material palette control.
- Surface depth control.
- Noise/climate material variance.
- Shore eligibility gates by slope/height/depth/bank dimensions.
- The `BeachEvaluator` continuity pass.

## Recommended Integration Path

Use ET as the base if the target is a current, more developed fork. Port Re's shoreline system as a focused feature set rather than trying to merge the fork wholesale.

The likely porting units are:

1. Add a new ET `WorldSettings` beach config object, but make it optional/defaulted to avoid breaking existing ET presets.
2. Port or adapt Re's `BeachType`, `BeachMaterial`, `ShoreGeometry`, and `BeachEvaluator`.
3. Extend ET's `Cell` with beach material/type/alpha state, taking care not to conflict with ET's water-table/uplift fields.
4. Adapt ET's river/lake generation to emit the shore alpha/depth/bank metadata Re's evaluator expects, or redesign `BeachEvaluator` to consume ET's newer river zone model.
5. Port `BeachSurfaceFeature` and register the configured/placed/biome modifier data for 1.21.1/NeoForge/Fabric.
6. Add config UI and translations only after the runtime behavior is stable.
7. Treat existing ET island beach controls as separate from the new global shoreline controls; they solve a different problem.

The biggest design decision is step 4. Re's system assumes `riverShoreAlpha`, `lakeShoreAlpha`, bank height/depth, and beach type/material state on `Cell`. ET's river work is more advanced but exposes different state. A clean port should map ET's richer river/lake geometry into a stable shoreline-evaluation interface instead of copying Re's older assumptions blindly.

## Addendum: ET Uplift Continents, 3D Rivers, And Re Shoreline Work

ET's uplift continent and 3D/elevated river work is not completely incompatible with Re's shoreline/beach work, but it is not plug-compatible either.

The main mismatch is the data contract. Re's beach system expects shoreline-specific state on `Cell`:

- `riverShoreAlpha`
- `lakeShoreAlpha`
- `riverWidth`
- `riverDepth`
- `riverBankHeight`
- `lakeDepth`
- `lakeBankHeight`
- `beachType`
- `beachMaterial`
- `beachSurfaceAlpha`

ET's uplift system instead carries a different model:

- `waterTable`
- `riverWaterLevel`
- `riverZone`
- uplift-specific continent distance/size fields

See [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:31) and [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/Cell.java:39).

That means the right integration shape is not "copy Re's `RiverCarver` into ET." ET's river model should stay authoritative for the geometry. Re's shoreline system should be adapted as a later classification and surface-material layer.

### What Conflicts

Re's shoreline evaluator assumes sea-level-relative height gates:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:203)
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:209)
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java:215)

ET's uplift rivers can have elevated local water levels:

- [ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java:136) calculates river water from `cell.waterTable`.
- [same file](/var/home/scott/Repos/ETReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java:352) tags river terrain and writes `riverWaterLevel`.

So Re's `minHeight`/`maxHeight` checks should be reinterpreted relative to the local river/lake water surface for ET uplift rivers, not blindly relative to global sea level.

Re also creates explicit shore-alpha metadata in its classic river/lake path:

- [ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/RiverCarver.java](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/RiverCarver.java:84) computes `shoreAlpha`.
- [same file](/var/home/scott/Repos/ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/RiverCarver.java:88) stores `riverShoreAlpha`.

ET's uplift carver has zones, distances, and final height shaping, but it does not currently publish equivalent `riverShoreAlpha`/`riverDepth`/`riverBankHeight` values. That is the adapter gap.

### What Is Compatible In Concept

The layers are separable:

- ET should own continent shape, water-table topology, river/wetland/lake carving, and heightfield geometry.
- Re's beach evaluator can own shoreline classification, material selection, continuity, and surface painting.

For ET, the port should derive Re-like shoreline facts from ET's river zones and local water surface:

- `riverZone == Banks` or `ValleyFloor` can become candidate shore influence.
- Distance through ET's zone radii can become `riverShoreAlpha`.
- `cell.riverWaterLevel` or `ContinentalHydrology.getWeightedWaterHeight(cell.waterTable)` should replace global sea-level assumptions for elevated river/lake shores.
- ET's lake widening/plateau logic can feed lake shore alpha rather than being replaced.

So the answer is: not completely incompatible, but the less mature fork's implementation is not directly compatible with ET's uplift/3D river internals. It needs a translation layer.

## Addendum: ET PR #77

PR reviewed: <https://github.com/ETcodehome/ReTerraForged/pull/77>

As of this review, PR #77 is open and unmerged. It changes only two files:

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java`
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/wetland/Wetland.java`

The PR is titled "River terrain transition improvements (#1)" and its body describes soft steps, terraced river-facing walls, adaptive profiles, drainage scaling, slope roughness, ledges/notches/talus aprons, asymmetric valleys, wetland transitions, and fixes for river shrinking/discontinuity.

### What PR #77 Does

In `UpliftRiverCarver`, PR #77:

- Adds slope roughness, scar, and valley-wall warp noise.
- Keeps the river channel unwarped while warping valley walls.
- Scales drainage by steepness.
- Makes narrower rivers shallower.
- Computes discrepancy from current terrain to valley floor and uses it to shape valley fadeout.
- Narrows high-mountain valley influence.
- Adds slope clipping, talus fan behavior, and landslide-scar roughness to the outer valley wall.
- Replaces hard terracing with a softer `softStep()` terrace profile.

In `Wetland`, PR #77:

- Adds bank roughness.
- Warps wetland edges.
- Uses local water surface from uplift hydrology.
- Adds slope-thresholded wetland banks.
- Tightens wetland biome assignment to the flatter interior.
- Reduces abrupt wall/basin transitions.

### Does PR #77 Accomplish Re's Shoreline/Beach Work?

Mostly no. PR #77 improves ET's uplift river and wetland **terrain geometry**. Re's less mature fork adds a configurable **shoreline classification and material-painting system**.

PR #77 does overlap with Re only at a broad goal level: both care about water-edge terrain transitions looking better. But they operate at different layers.

PR #77 does not add:

- `world.beaches` config.
- Ocean/river/lake shoreline material palettes.
- Sand/gravel/stone/mud/red-sand material selection.
- `BeachEvaluator`.
- `BeachSurfaceFeature`.
- `BeachType` or `BeachMaterial`.
- River/lake shore terrain categories.
- Configurable shore painting depth.
- Climate/noise material variance.
- UI controls for shore material spread, shore geometry, or shore material palettes.

So PR #77 should be treated as another ET-side terrain-shaping improvement, not as an implementation of the less mature fork's beach/shoreline feature set.

### How PR #77 Affects A Future Port

PR #77 would probably make Re's shoreline work harder to port by copy/paste, but easier to port cleanly.

Harder because:

- Re's classic `RiverCarver` emits shore alpha directly.
- PR #77 pushes ET farther into its own uplift river-zone model.
- Re's global sea-level assumptions become less valid for ET's elevated water surfaces.

Easier because:

- PR #77 gives ET clearer, richer river/wetland transition zones.
- Those zones can be used to derive better shore alpha than Re's older classic river model.
- If the adapter is designed around ET's local water surface and river zones, Re's beach-material layer can sit on top of better geometry.

The right conclusion is:

> PR #77 does not replace the less mature fork's shoreline/beach work. It is complementary terrain-transition work in ET's uplift river system. A future port should keep PR #77-style geometry and add Re-style material/shoreline classification on top of it.
