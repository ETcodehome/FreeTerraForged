# RTF Tall-World Height Cutoff Runtime Findings

Date: 2026-06-22

## Context

The preset under test was:

`/home/scott/.var/app/com.modrinth.ModrinthApp/data/ModrinthApp/profiles/Zioncraft/config/reterraforged/exports/Modern Earthlike 3D Rivers.zip`

Runtime test inputs:

- Minecraft/Fabric: 1.21.1 dev server
- Seed: `7193149640`
- Target column: `x=3511 z=4114`
- Reported symptom: target area should contain very tall mountains, but terrain appears cut off around the old 256-scale mountain height band.

## Datapack Loading Note

The exported datapack did not load in the bare RTF Fabric dev server because its overworld surface rule references Zioncraft-provided registry entries:

- `minecraft:cinnabar`
- `minecraft:sulfur`
- `minecraft:worldgen/material_condition` type `minecraft:spatial_noise_threshold`

These entries are not vanilla 1.21.1 entries and are not registered by this RTF codebase. They are surface/material rules, not terrain density rules. For the dev-server investigation only, a throwaway datapack copy removed the unsupported sulfur-cave surface-rule branch and left the terrain density functions intact.

This sanitization should not affect the height cutoff issue because the tested path is:

`reterraforged:height -> overworld/offset -> overworld/depth -> sloped_cheese -> final_density`

Surface rules run after density has already decided solid vs air.

## Instrumentation Added

Temporary gated instrumentation was added behind:

`-Dreterraforged.debug.worldHeight=true`

Important system properties:

- `reterraforged.debug.targetX`
- `reterraforged.debug.targetZ`
- `reterraforged.debug.radius`
- `reterraforged.debug.sampleYs`

The instrumentation logs:

- preset/world height values
- target chunk RTF cell heights
- router density outputs
- selected registry density function samples for `offset`, `depth`, `sloped_cheese`, and `factor`

The relevant log marker is `RTF_WORLD_HEIGHT_*`.

### Preset Initialization Regression

During follow-up QA, the singleplayer preset appeared to be ignored after recent worldgen changes. The live preset JSON was:

`/home/scott/.var/app/com.modrinth.ModrinthApp/data/ModrinthApp/profiles/Zioncraft/config/reterraforged/presets/Modern Earthlike 3D Rivers.json`

This was not a missing-user-datapack problem. In singleplayer, the config JSON is the user input; RTF internally exports/applies the generated registry data during world creation.

The concrete code-side failure risk was in `MixinRandomState`: debug density-function sampling could be invoked during RTF initialization and resolve vanilla density-function registry keys such as `minecraft:overworld/sloped_cheese`. If a key was absent in the active patched registry state, an eager `getOrThrow`-style lookup would abort `GeneratorContext` initialization and make the selected preset behave as if it was ignored.

Fix applied:

- `MixinRandomState` now calls `WorldHeightDebug.logDensityFunctionSamples(functions, visitor)` instead of resolving sampled functions before the debug guard.
- `WorldHeightDebug.logDensityFunctionSamples(...)` returns immediately unless `-Dreterraforged.debug.worldHeight=true`.
- When debug is enabled, sampled registry functions are read with optional lookup and missing keys are logged as warnings instead of throwing.
- UI preset-apply changes were intentionally not kept; they were not the root cause of this worldgen initialization failure.

## Runtime Result Before Fix

Preset/world settings from the run:

```text
worldHeight=1024
terrainModelHeight=256
worldDepth=128
seaLevel=63
mountainBaseScale=1.5
mountainVerticalScale=3.4664948
mountainHorizontalScale=3.4664948
```

RTF cell data at the target column:

```text
x=3511 z=4114
cellHeight=1.6832778
terrainY=430
worldY=1723
terrain=mountain_chain
```

Interpretation:

- `terrainY=430` is `cellHeight * 256`.
- `worldY=1723` is `cellHeight * 1024`, but this is only informational in the pre-fix code.
- The live density surface crossed around the old 256-scale value, not the configured 1024-scale world range.

Target-column density samples before the fix:

```text
Y     offset    depth     sloped_cheese  final_density
256   2.410312  1.410312  24.986391      0.309077
320   2.410312  0.910312  16.127931      0.309077
360   2.410312  0.597812  10.591394      0.309077
384   2.410312  0.410312  7.269472       0.309077
385   2.410312  0.402500  7.131058       0.309077
400   2.410312  0.285312  5.054857       0.309077
430   2.410312  0.050937  0.902454       0.023027
448   2.410312 -0.089688 -0.397247     -0.191140
512   2.410312 -0.589688 -2.611862     -0.458333
640   2.410312 -1.589688 -7.041092     -0.458333
768   2.410312 -2.589688 -11.470321    -0.458333
896   2.410312 -3.589688 -15.899551    -0.458333
```

## Findings

1. The full-height chunk-generation bypass is not enough by itself.

   The target chunk was allowed to generate to the configured `worldHeight=1024`, but `final_density` still crossed near `Y=430-448`.

2. The density graph is receiving the RTF height value.

   `registry.offset=2.410312` matches the current formula:

   `GLOBAL_OFFSET - 0.5 + 2 * clamp_to_nearest_unit(reterraforged:height, 256)`

   So the issue is not that the RTF offset holder is ignored.

3. The active surface is still a 256-scale projection.

   The observed crossing near `Y=430-448` matches `cellHeight * 256`, not `cellHeight * 1024`.

4. `CellSampler.maxValue()` was inaccurate.

   `reterraforged:height` declared max `1.0`, but the target column produced `1.6832778`. This makes downstream density-function ranges lie about possible values. It may not be the sole cause of the cutoff, but it is a correctness risk and can affect optimizer/range-choice behavior.

5. The correct fix is not to make the whole terrain model 1024-scale.

   Earlier attempts that changed `terrainScaler()` to full `worldHeight` changed terrain layout, oceans, biome lookup, and village placement. The 256 scale is a terrain-model compatibility scale, not merely a world-height cap.

## Fix Direction

Make the 256-scale terrain model explicit, then add a separate tall-world vertical projection for block density generation.

The intended split:

- `terrainModelHeight`: stable RTF layout/model scale, capped at 256.
- `worldHeight`: actual configured Minecraft generation height.
- `router.depth`: remains on the terrain model scale to preserve climate/layout compatibility.
- `initialDensityWithoutJaggedness` and `finalDensity`: use a private terrain-density path that projects high terrain into the configured tall-world vertical range.

The projection should:

- preserve sea level and low terrain as much as possible
- avoid changing continent/ocean/biome layout
- avoid using quantized height for the extension term, because amplified 1/256 steps caused terracing in earlier offset-boost attempts
- clamp/ease into the configured world ceiling for extreme terrain

## Implemented Fix

The implementation makes the 256 terrain model explicit and separates it from block-generation height:

- `WorldSettings.Properties.MAX_TERRAIN_MODEL_HEIGHT = 256`
- `WorldSettings.Properties.terrainModelHeight()` returns `min(worldHeight, MAX_TERRAIN_MODEL_HEIGHT)`
- `terrainScaler()` remains only as a deprecated compatibility alias for `terrainModelHeight()`
- existing model/layout call sites now call `terrainModelHeight()` directly

This means previews, terrain-type ground level, surface noise scaling, climate/layout generation, and model-space chunk cell reporting are still intentionally 256-scale unless the configured world is shorter than 256.

The density fix is in `PresetNoiseRouterData`:

- registered `minecraft:overworld/offset` and `minecraft:overworld/depth` remain model-scale, preserving compatibility for climate/layout consumers
- `overworld(...)` now builds `initialDensityWithoutJaggedness` and `finalDensity` from a private tall-world terrain-depth expression when `worldHeight > terrainModelHeight`
- that private expression uses raw unquantized `reterraforged:height` as the high-terrain input, which avoids amplifying 1/256 quantization steps
- the projection preserves normal terrain exactly through `height=1.0`, then gradually expands above-model mountain heights into the tall-world range
- extreme projected terrain is clamped to the configured world-height extension range

`CellSampler` was also corrected so declared density-function ranges match the sampled field:

- most RTF fields keep `0..1`
- `HEIGHT` now declares `0..16`, which covers the observed `1.6832778` target value and avoids lying to density-function optimizers about possible terrain height values

## Mountain Shape Fix — Current Implementation State

After the tall-world density projection was corrected, visual QA found that mountains appeared as blade-like vertical walls hundreds of blocks tall rather than natural mountain shapes with gradual slopes. Multiple iterations were required.

---

### Root Causes Identified

**1. Dead `horizontalScale` parameter in `Populators.makeMountains`**

The private `makeMountains` method accepted a `horizontalScale` float parameter but never used it for `scaleH` or warp. `scaleH` was always computed from `settings.horizontalScale` alone. All mountain types had zero horizontal compensation for tall worlds.

**2. The tall-world projection amplifies height differences into proportionally steeper slopes**

When cell height differences are mapped to a larger Y range, the same horizontal noise profile produces steeper slopes. A mountain that spanned Y=100–250 in a 256-world now spans Y=100–700+, but the horizontal footprint is unchanged. The density gradient per block of Y is the same (~0.0078), but the offset difference between peak and shoulder maps to 4x more Y-blocks. This creates near-vertical walls.

**3. Worley mountain envelope was unscaled**

The worley edge noise in `Heightmap.make()` controls the macro mountain band width (where mountains exist vs terrain). This was never scaled for tall worlds, constraining all mountain features to their 256-world-width bands regardless of how much taller they became.

**4. Region-based mountains were completely unscaled**

`TerrainProvider.generateTerrain()` creates three region-based mountain types (`makeMountains` with `horizontalScale=1.0`, `makeMountains2`, `makeMountains3`). These are clustered peak types that appear within terrain regions. None of them received any horizontal scaling for tall worlds, so they remained at 256-world widths while the height projection stretched them vertically into near-vertical cones.

**5. `makeMountainChain` had a dormant 2.25x multiplier**

`makeMountainChain` multiplied `horizontalScale` by 2.25 for non-legacy mode before passing to `makeMountains`. Since `horizontalScale` was dead inside `makeMountains`, this had no effect. When the parameter was made active, this 2.25x would compound with the height-ratio boost, causing over-scaling. It was removed.

---

### Current Implementation

The final fix applies projection-matched horizontal scaling across ALL mountain layers. The first pass used `heightRatio = worldHeight / terrainModelHeight`, but visual QA showed that was still not enough for tall worlds because the concave density projection's body segment is steeper than the raw height ratio. The code now derives `tallTerrainHorizontalScale()` from the same projection constants used by the density router, so mountain footprints widen according to the strongest vertical stretch they will actually receive.

For standard 256-block worlds, `tallTerrainHorizontalScale() = 1.0` and all multiplications are identity. For the tested 1024-height preset, `heightRatio = 4.0` and the body projection slope is `6.0`, so mountain horizontal compensation uses `6.0`.

**Height projection — `PresetNoiseRouterData.tallTerrainOffset()`:**

Two-segment concave projection for heights above the terrain model ceiling:

- **Body segment** (cell height 1.0–1.3): steep slope, consumes 60% of the extension range. This fills the mountain body rapidly, placing most of the mountain mass in the lower-to-mid vertical zone.
- **Peak segment** (cell height 1.3+): gentle slope, uses the remaining 40%. This compresses the peak zone so that peaked noise ridges produce rounded summits instead of sharp cones.

```java
double shoulderHeight = 1.3;
double shoulderProjected = 1.0 + (extension - 1.0) * 0.6;
```

For a 1024-world (extension=4.0):

| Cell height | Projected | Approx Y |
|-------------|-----------|----------|
| 0.25 (sea)  | 0.25      | 63       |
| 1.0         | 1.0       | 256      |
| 1.3         | 2.8       | ~718     |
| 1.5         | 3.14      | ~805     |
| 1.7         | 3.49      | ~893     |

**Worley mountain envelope - `Heightmap.make()`:**

The worley edge noise period and warp both scale by `tallTerrainHorizontalScale()`. Legacy mountain mode now also honors `settings.mountains.horizontalScale` for this macro mountain-chain envelope. This was the key remaining cause of "horizontal scale changes do nothing" reports: in legacy mode, the ridge layer used the preset horizontal scale, but the broad mountain mask still did not.

```java
int worleyPeriod = legacyScaling
    ? Math.round(1000 * settings.mountains.horizontalScale * mountainHorizontalScale)
    : Math.round(1000 * settings.mountains.horizontalScale * 2.25F * mountainHorizontalScale);
mountainShape = Noises.warpPerlin(mountainShape, seed, Math.round(333 * mountainHorizontalScale), 2, 250.0F * mountainHorizontalScale);
```

**Ridge noise - `Populators.makeMountains()`:**

The projection compensation and preset mountain horizontal scale are combined once, then applied to the ridge period, scaler noise, warp scale, and warp strength. The ridge gain and noise shape are unchanged - the original `gain=1.15` and no pow() adjustment.

```java
float combinedHorizontalScale = settings.horizontalScale * horizontalScale;
int scaleH = period((legacyScaling ? 410.0F : MOUNTAINS_H) * combinedHorizontalScale);
// ... perlinRidge with original gain=1.15 ...
height = Noises.warpPerlin(height, seed, period(350 * combinedHorizontalScale), 1, 150.0F * combinedHorizontalScale);
```

**Region mountains - `Populators.makeMountains2()` and `makeMountains3()`:**

Both now accept `float horizontalScale` and apply the combined projection/preset scale to their worley cell periods, surface ridge periods, modulation/mask periods, and warp parameters. This is critical because these mountain types were a major source of the near-vertical cones visible in QA screenshots.

**Fancy mountain detail - `Populators.makeFancy()`:**

The optional fancy mountain erosion pass also scales its detail periods and erosion grid size. Without this, widened mountain bodies could still retain narrow, vertically exaggerated ribs from fixed-size detail noise.

**TerrainProvider — `generateTerrain()`:**

Now accepts `float mountainHorizontalScale` and passes it to all mountain populator calls.

---

### Files Changed (current uncommitted state)

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java`
  - Computes `mountainHorizontalScale = world.properties.tallTerrainHorizontalScale()`
  - Scales worley envelope period and warp by projection-matched horizontal compensation
  - Legacy mountain mode now honors `settings.mountains.horizontalScale` for the macro mountain envelope
  - Passes `mountainHorizontalScale` to `makeMountainChain` and to `TerrainProvider.generateTerrain`

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java`
  - `makeMountains`: combines preset mountain horizontal scale with projection compensation
  - `makeMountains`: ridge period, scaler period, warp scale, and warp strength all use the combined scale
  - `makeMountains`: gain and noise shape unchanged (1.15, no pow)
  - `makeMountainChain`: removed dormant 2.25x multiplier that would over-scale
  - `makeMountains2`: new `horizontalScale` parameter, applied to worley cells, warp, blur, and surface ridge
  - `makeMountains3`: new `horizontalScale` parameter, applied to worley cells, warp, blur, surface ridge, modulation, and mask
  - `makeFancy`: scales detail noise and erosion grid size to avoid narrow tall-world ribs
  - `period`: guards noise periods against zero or negative slider values

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/provider/TerrainProvider.java`
  - `generateTerrain` now accepts `float mountainHorizontalScale` parameter
  - Passes `mountainHorizontalScale` to `makeMountains`, `makeMountains2`, `makeMountains3`

- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseRouterData.java`
  - `tallTerrainOffset`: two-segment concave projection (steep body, gentle peak)
  - `tallTerrainDepth`: dispatches between standard and tall offsets
  - `terrainModelOffset`: extracted helper for standard 256-scale offset
  - `linearHeight`: helper for piecewise linear projection segments
  - `overworld()`: uses `tallTerrainDepth` for `initialDensity`, sets `slopedCheese = initialDensity`

- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java`
  - `MAX_TERRAIN_MODEL_HEIGHT = 256`
  - `TALL_TERRAIN_SHOULDER_HEIGHT` and `TALL_TERRAIN_SHOULDER_FRACTION` centralize the projection constants
  - `terrainModelHeight()` returns `min(worldHeight, 256)`
  - `terrainHeightRatio()` exposes the raw world-height/model-height ratio
  - `tallTerrainHorizontalScale()` returns the horizontal compensation required by the steepest tall-world projection segment

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/CellSampler.java`
  - `HEIGHT.maxValue()` now returns 16.0 (was 1.0)
  - `Field.minValue()` / `Field.maxValue()` overridable per field

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/GeneratorContext.java`
  - Uses `terrainModelHeight()` instead of deprecated `terrainScaler()`

- `common/src/main/java/raccoonman/reterraforged/client/gui/screen/presetconfig/Preview2D.java`
- `common/src/main/java/raccoonman/reterraforged/client/gui/screen/presetconfig/Preview3D.java`
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetSurfaceNoise.java`
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetTerrainTypeNoise.java`
  - All use `terrainModelHeight()` instead of deprecated `terrainScaler()`

- `common/src/main/java/raccoonman/reterraforged/mixin/MixinNoiseBasedChunkGenerator.java`
  - Uses `terrainModelHeight()` for tall-world bypass check
  - Includes debug instrumentation (gated behind system property)

- `common/src/main/java/raccoonman/reterraforged/mixin/MixinRandomState.java`
  - Includes debug instrumentation (gated behind system property)
  - Preset initialization path now avoids eager debug density-function lookup when debug is disabled

- `common/src/main/java/raccoonman/reterraforged/world/worldgen/debug/WorldHeightDebug.java`
  - Runtime debug utility (gated, no production impact)
  - Optional registry lookup for sampled density functions; missing debug sample targets warn instead of throwing

---

### Iteration History

1. Initial tall-world density projection (piecewise linear, conservative curve) — solved the Y=385 cutoff but created blade-like mountain walls.
2. `ratio^1.5` = 8× horizontal boost + `ridgeGain/cbrt` + `pow(height, 1/cbrt)` — targeted only chain mountains, ridge noise only. Walls improved slightly but still blade-like. Root cause: worley envelope was unscaled, region mountains had no boost at all.
3. Proportional `heightRatio` scaling to worley envelope + ridge + warp, removed gain/pow adjustments, linear height projection — wider bases but still pointed cones. Root cause: linear projection amplified peak height differences proportionally, creating steep cones.
4. Concave two-segment projection (steep body, gentle peak) — compresses peak zone to broaden summits. Region mountains still unscaled at this point.
5. Extended `heightRatio` to `TerrainProvider.generateTerrain()` -> all region mountain types (`makeMountains`, `makeMountains2`, `makeMountains3`). Improved but still too narrow in the tested preset.
6. Final fix: use projection-matched `tallTerrainHorizontalScale()`, make legacy mountain scaling honor `mountains.horizontalScale` for macro and region mountain masks, and scale fancy mountain detail. Visual QA confirmed mountain scaling now works as expected.

---

### Known Remaining Concerns

- The tested "Modern Earthlike 3D Rivers" 1024-height preset now responds correctly to mountain horizontal scaling, including with `legacyMountainScaling=true`.
- Hills, plateaus, and other non-mountain terrain types are not horizontally scaled. If they also appear too steep in tall worlds, the same pattern could be extended to them.
- The debug instrumentation in MixinNoiseBasedChunkGenerator and MixinRandomState is gated and safe when disabled, but should still be reviewed before release.
- The exported datapack must be regenerated to pick up code-side noise-router changes.
