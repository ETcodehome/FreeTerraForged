# RTF World Height Cutoff Investigation

## Status As Of 2026-06-22

The strata and ocean-depth work is functioning:

- Strata layer thickness/weighting works in-game.
- Oceans are deeper with the new preset-owned depth settings.
- The prior implementation work was committed and pushed as `fe1ef47 Add configurable ocean depth and strata`.

The remaining issue is world-height handling for tall presets. The original hard cutoff around Y 384/385 is not solved by the committed changes. Eight approaches have been tried. One (the dynamic density scaler, attempt 5) DID successfully push terrain higher — but it exaggerated all terrain relief indiscriminately, forcing everything toward Y 1024. The OFFSET boost approaches (attempts 7-8) introduced visible terracing (proving the boost reached the density function) but failed to push terrain surfaces above Y 385. The cell-level approach (attempt 6) saturated the density function and made everything flat.

## User Evidence

The user tested after the local noise-router density-scaler candidate and provided screenshots:

- `/tmp/codex-clipboard-QKlW0r.png`: ordinary hills become exaggerated vertically.
- `/tmp/codex-clipboard-q9V7jk.png`: mountains become extremely exaggerated and appear to be driven toward the configured vertical limit.

Interpretation: the latest candidate likely removed or loosened the previous ceiling, but it did so by scaling the density field too broadly. Terrain relief is now amplified instead of preserving the intended RTF shape and only allowing tall terrain where the existing height field calls for it.

## What Was Tried

### 1. Full `terrainScaler()` = `worldHeight`

Changing `WorldSettings.Properties.terrainScaler()` from:

```java
Math.min(this.worldHeight, 256)
```

to full `worldHeight` was too broad.

Observed/expected effects:

- It changed normalized terrain-space calculations, not only vertical block placement.
- It caused biome/ocean layout drift.
- User observed a seed that was originally plains became ocean, a village placed in ocean, and located biomes no longer matched expectation.

Conclusion: `terrainScaler()` must remain capped for 2D RTF layout stability unless a larger redesign intentionally retunes all terrain/biome noise.

### 2. Reverted `terrainScaler()` To The Old Cap

The cap was restored:

```java
public int terrainScaler() {
    return Math.min(this.worldHeight, 256);
}
```

This protects:

- Continent/ocean layout.
- Biome climate lookup behavior.
- Preview/heightmap behavior that expects RTF's classic 256-scale normalized terrain field.

Conclusion: this was the correct follow-up for biome/ocean drift.

### 3. Dynamic Chunk Max Height Uses Full `worldHeight`

`MixinNoiseBasedChunkGenerator` was changed so dynamic max-height optimization uses the preset `worldHeight` instead of hardcoded `256`.

This fixes one real bug: tall terrain should not be skipped merely because the optimizer sampled `cell.height * 256`.

However, the user still saw cutoff around Y 384/385 afterward.

Conclusion: the optimizer was a contributing bug, but not the only source of the ceiling.

### 4. Full-Height Optimization Bypass For Tall Worlds

A local uncommitted candidate set max height to full preset `worldHeight` whenever `worldHeight > terrainScaler()`.

Reasoning:

- If section pruning was still the cause, forcing full chunk height should eliminate the cutoff.

Result:

- Build passed.
- User still reported height problems.

Conclusion: chunk-section pruning is not the whole issue. If density produces air above the old ceiling, generating more sections cannot create mountain mass there.

### 5. Dynamic Noise Router Density Scale

A local uncommitted candidate changed `PresetNoiseRouterData` from a hardcoded density scale:

```java
private static final float SCALER = 128.0F;
private static final float UNIT = 1.0F / SCALER;
```

to a scale derived from `worldHeight`:

```java
private static final float DEFAULT_SCALER = 128.0F;

private static float densityScaler(WorldSettings.Properties properties) {
    return Math.max(DEFAULT_SCALER, properties.worldHeight / 2.0F);
}
```

This was applied to:

- `NoiseRouterData.DEPTH` y-gradient range.
- Initial density slide constants.
- Cave/sloped-cheese slide constants.

Build result:

- `./gradlew build` passed with existing warnings only.

User result:

- Hills became too vertically exaggerated.
- Mountains appeared forced up toward the configured Y limit.

Conclusion: this is too broad. The hardcoded `128` is suspicious and likely related to the old ceiling, but scaling the whole density-router vertical gradient by `worldHeight / 2` changes terrain relief too much.

### 6. Cell-Level Height Extension (Populator/Heightmap Multiplier)

Applied a `heightExtensionFactor` (= `worldHeight / terrainScaler()` = 4.0 for 1024 world) as a multiplier to the terrain noise amplitude in the cell generation pipeline:

- `TerrainProvider`: graduated scaling — `mountainScale = globalVerticalScale * heightExtension`, `hillScale = globalVerticalScale * sqrt(heightExtension)`, plains unchanged.
- `Heightmap`: mountain chain vertical scale multiplied by `heightExtension`.
- `VolcanoPopulator`: stored and applied `heightExtension` to final height.
- `MixinNoiseBasedChunkGenerator`: uses `terrainScaler()` for cell-to-block conversion (not `worldHeight`).

Build result: passed.

User result:

- **All terrain completely flat at Y 384.** No elevation variation whatsoever — plains, hills, mountains all at the same level.
- Cause: the extension multiplied ON TOP of the user's existing per-terrain `verticalScale` settings. The preset had `mountains.verticalScale = 3.4664948` (already cranked to push past the old ceiling). Combined total mountain multiplier: `noise * 1.3 * 4.0 * 3.47 = noise * 18.03`, producing cell heights of ~12.86 (theoretical Y = 3293). This made the density OFFSET so large that the DEPTH function was positive at ALL Y levels within the world, making every column entirely solid with no surface variation.
- The "flat at 384" appearance was because the world was solid from bedrock to ceiling, and Y 384 was likely where the player spawned or where a cave layer created the first visible surface.

Conclusion: **Cell-level scaling is fundamentally wrong for this problem.** It compounds unpredictably with user-configured per-terrain verticalScale settings. Even with graduated scaling (different factors for plains vs mountains), high-verticalScale presets overflow the density system.

**Reverted all Populator/Heightmap/TerrainProvider/VolcanoPopulator changes.**

### 7. Density-Level OFFSET Extension (Linear Boost Above Sea Level)

Instead of changing cell heights, added a boost term directly to the OFFSET density function that stretches only terrain above sea level:

```java
// Formula: offset = standardOffset + max(0, clampedHeight - ground) * 2.0 * (extension - 1.0)
float ground = (float) properties.seaLevel / terrainScaler;  // ~0.246
DensityFunction aboveGround = add(clampedHeight, constant(-ground)).clamp(0, 16);
DensityFunction extensionBoost = mul(aboveGround, constant(2.0 * (heightExtension - 1.0)));
DensityFunction offsetDf = add(standardOffset, extensionBoost);
```

Also updated `MixinNoiseBasedChunkGenerator` to compute surface Y consistently with the density formula.

Build result: passed.

User result:

- **Severe terracing** — multi-block stepped terrain (3-4 block steps) everywhere. Caused by `clampToNearestUnit` quantizing cell height to 1/256 resolution; each quantum step produced a boost of `6.0/256 = 0.0234` in offset units, mapping to `0.0234 * 128 ≈ 3-4 blocks` in Y.
- **Spawn at Y 384 in non-mountainous terrain.** The linear extension stretched ALL above-sea-level terrain by 4x. A cell height of 0.56 (originally Y 143, moderate hills) mapped to Y 384 with the extension.
- **World height still capped at ~385.** Despite the boost, terrain did not appear to extend above Y 385.

Conclusion: linear extension is too aggressive for low/moderate terrain. Also, the quantization step amplification causes unacceptable terracing. The "height cap at 385" persistence was unexpected — the math predicts terrain at Y 800+ for mountains, but the user saw 385.

### 8. Density-Level OFFSET Extension (Quadratic Boost Above Sea Level)

Refined approach: used a **quadratic** boost curve so low terrain barely moves while mountains get full extension. Also used **raw (unquantized) height** for the boost to avoid terracing:

```java
// Formula: boost = max(0, rawHeight - ground)^2 * extensionScale
// extensionScale = 2.0 * (extension - 1.0) / (1.0 - ground)  ≈ 7.96
float ground = (float) properties.seaLevel / terrainScaler;
float extensionScale = 2.0F * (heightExtension - 1.0F) / (1.0F - ground);
DensityFunction aboveGround = add(height, constant(-ground)).clamp(0, 16);  // raw height, not clampedHeight
DensityFunction extensionBoost = mul(mul(aboveGround, aboveGround), constant(extensionScale));
DensityFunction offsetDf = add(standardOffset, extensionBoost);
```

Expected behavior (mathematically):

| Terrain | Cell height | Standard Y | Quadratic extension Y |
|---------|------------|------------|----------------------|
| Sea level | 0.246 | 63 | 63 (unchanged) |
| Plains | 0.27 | 69 | 70 (+1) |
| Low hills | 0.5 | 128 | 194 (+66) |
| Mountains | 1.0 | 256 | 835 (+579) |
| Extreme mountains | 1.5 | 384 | 1024 (ceiling) |

Build result: passed.

User result:

- **Terracing reduced for plains** (quadratic barely affects low terrain) **but same severity for hills/mountains** — the quadratic boost still amplifies quantization at higher elevations.
- **World height still capped at ~385.** Despite the quadratic boost predicting Y 835+ for mountains, actual terrain did not extend above 385.
- **Biome distribution changed** for same preset and same seed. Likely caused by the changed OFFSET affecting the `depth` field in the NoiseRouter, which feeds into Minecraft's surface rule system and may influence the spawn location, making the player see a different part of the map.

Conclusion: the quadratic curve correctly dampens low-terrain inflation (less terracing at plains), but the core mystery remains — **the OFFSET boost does not appear to push terrain surfaces above Y 385 in practice**, despite the math predicting it should. Additionally, modifying the OFFSET has the side effect of changing biome-related density fields.

**Reverted all changes.**

## Analysis: Why Does Y 385 Persist?

The Y 384/385 ceiling (= 1.5 × 256) corresponds to the maximum cell height the user's preset produces: with `mountains.verticalScale = 3.47`, peak cell heights reach ~1.5, which maps to `1.5 × 256 = 384` in the standard density-to-block mapping.

The OFFSET boost should shift terrain surfaces higher. The mystery is why it doesn't. Possible explanations:

### Theory A: `rangeChoice` Forces Underground Path

When the OFFSET boost makes `slopedCheese` very large (>> `cheeseCaveDepthOffset ≈ 1.5625`), the `rangeChoice` ALWAYS takes the underground branch. The underground function is cave-based and does not create terrain surfaces — it produces a solid world with cave voids. The visible "surface" at Y 385 may be the boundary of a cave layer, not a terrain surface.

### Theory B: Density Function Graph Resolution

The OFFSET is registered via `NoiseRouterData.registerAndWrap()`, which returns a holder reference. `SLOPED_CHEESE` is a vanilla-registered function that references `DEPTH` by holder. If the holder resolution doesn't properly pick up RTF's overridden OFFSET (e.g., if vanilla's bootstrap runs after RTF's, or if the holder binding is snapshot-based rather than lazy), the extension boost would be silently ignored.

### Theory C: `CellSampler.maxValue()` = 1.0

CellSampler declares `maxValue() = 1.0`, but actual cell heights can reach 1.5+. This causes the density function framework to compute incorrect declared ranges for all compound functions downstream. While this shouldn't clip actual values (compute() returns the real value), it may affect `rangeChoice` branch optimization — if the declared range says `slopedCheese` can never exceed a threshold, the rangeChoice may statically eliminate one branch during graph compilation.

### Recommended Investigation: Runtime Instrumentation

Code analysis has exhausted its utility. The next step should be **runtime debugging** — adding temporary logging to sample actual density values at specific blocks:

1. Pick a known mountain column (XZ coordinates where cell height ≈ 1.5).
2. Log the registered OFFSET density function's `compute()` result at that column.
3. Log the DEPTH value at Y=384, Y=500, Y=700 for that column.
4. Log the `slopedCheese` value at those Y levels.
5. Log the `finalDensity` value at those Y levels.
6. Compare WITH and WITHOUT the extension boost code.

This will reveal exactly WHERE the density chain breaks — whether the boost takes effect in the OFFSET but is swallowed by slopedCheese/rangeChoice, or whether the boost never reaches the OFFSET at all.

## Current Working Theory

There are at least two separate concepts that must remain separate:

1. RTF terrain layout scale.
   - Drives normalized terrain cells, continents, oceans, biome lookup, and previews.
   - Should remain capped at 256 for compatibility with existing preset behavior.

2. Minecraft chunk/noise vertical generation range.
   - Controls how high the density function is evaluated and how much chunk generation is skipped.
   - Must support full `worldHeight` for tall worlds.

All eight attempts have tried to bridge these by either (a) amplifying cell heights, (b) boosting the density OFFSET, or (c) changing the density scaler globally. Results split into two categories:

**Approaches that DID push terrain higher but broke it:**
- Attempt 5 (dynamic density scaler): terrain reached Y 1024 but ALL terrain was exaggerated — hills became mountains, everything was forced upward.
- Attempt 6 (cell-level extension): cell heights overflowed the density system, making the entire column solid (flat world).

**Approaches that preserved terrain shape but failed to raise the ceiling:**
- Attempts 7-8 (OFFSET boost): terracing proved the boost was modifying the density function, but terrain surfaces stayed at ~Y 385. The boost may have been swallowed by `slopedCheese`/`rangeChoice` downstream.

Key insight: attempt 5 is the only one that actually moved the surface upward. It changed the SCALER (which affects the gradient slope) rather than just the OFFSET. This suggests the problem may not be in the OFFSET at all — the gradient slope (controlled by SCALER and `yGradientRange`) may be the actual bottleneck. Boosting the OFFSET alone doesn't help if the gradient is too steep: the density transition from solid to air still happens at the same Y range because the gradient dominates.

The fundamental challenge: RTF's cell heights are produced by a fixed-amplitude noise system calibrated for 256-scale worlds. The density pipeline converts these heights to block Y via `OFFSET → DEPTH → initialDensity → slopedCheese → rangeChoice → finalDensity`. The SCALER-based gradient determines how quickly density transitions from solid to air per block of Y. The OFFSET shifts WHERE that transition happens, but if the OFFSET boost is large enough to make `slopedCheese` exceed the `rangeChoice` threshold everywhere, the terrain surface disappears entirely — replaced by the cave-only underground path.

## Known Good Results

- New ocean settings load from old presets via optional fields.
- Shallow/deep ocean generation now responds to preset-owned depth values.
- Ocean floor can go below Y 0 when `worldDepth` permits it.
- Strata settings load from old presets via optional `miscellaneous.strata`.
- Rock strata layer counts affect band thickness.
- Rock strata material weights affect stone/granite/andesite/diorite frequency.
- Build passes after the committed work.

## Known Bad Or Incomplete Results

- The world-height cutoff at Y 384/385 is not solved in the committed code.
- Of eight approaches, only the dynamic density scaler (attempt 5) raised the ceiling — but it exaggerated all terrain. OFFSET-only approaches (7-8) failed to move the surface above Y 385.
- Modifying the OFFSET density function affects biome distribution (surface rules / spawn location).
- `CellSampler.maxValue()` declares 1.0 but actual heights can reach 1.5+ — this is a pre-existing metadata bug.
- The committed mixin has a tall-world bypass that generates all sections for tall worlds (wasteful but functional):
  ```java
  if(dynamicHeightScale > terrainScaler()) {
      rtfChunk.setMaxHeight(dynamicHeightScale);
      return;
  }
  ```

## Files Most Relevant To Continue

- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java`
  - Keep `terrainScaler()` capped unless intentionally retuning layout.
- `common/src/main/java/raccoonman/reterraforged/mixin/MixinNoiseBasedChunkGenerator.java`
  - Dynamic max-height optimization path.
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/MaxHeightUtil.java`
  - Converts sampled terrain/structure height into generated chunk section height.
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseRouterData.java`
  - Current prime suspect for the density-side ceiling.
  - Contains the OFFSET, DEPTH, and overworld() density chain.
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/CellSampler.java`
  - `maxValue()` returns 1.0 — may need updating if declared ranges affect optimization.
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/densityfunction/ClampToNearestUnit.java`
  - Quantizes cell height to 1/terrainScaler resolution. Boost amplification of quanta causes terracing.
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseGeneratorSettings.java`
  - Confirms dimension noise settings use `-worldDepth` and `worldDepth + worldHeight`.
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetDimensionTypes.java`
  - Confirms dimension min/height/logical height are based on `worldDepth + worldHeight`.

## Recommended Next Steps

### Option A: Runtime Instrumentation

Add temporary logging to trace actual density values through the chain:

1. `CellSampler.compute()` — log actual cell height values for HEIGHT field.
2. `PresetNoiseRouterData` — wrap the OFFSET in a debug density function that logs its value.
3. The `overworld()` method — wrap `slopedCheese` and `finalDensity` in debug loggers.

Sample at a fixed XZ (e.g., 0,0) at Y=63, Y=200, Y=384, Y=500, Y=700. Run with and without the OFFSET boost. This will conclusively show whether:
- The boost reaches the OFFSET compute path.
- The boost propagates through DEPTH to slopedCheese.
- The rangeChoice branch selection changes.
- The finalDensity transitions from positive to negative at the expected Y.

### Option B: Revisit the Density Scaler With Selective Application

Attempt 5 (dynamic density scaler) is the ONLY approach that successfully raised terrain above Y 385. It changed the SCALER constant (which controls the gradient slope), not just the OFFSET. This suggests the gradient slope is the actual bottleneck.

The problem with attempt 5 was that it applied the scaler change globally — affecting ALL terrain including low terrain. A refined version could:

1. Keep the original SCALER/gradient for the standard density path (preserves terrain relief shape).
2. Only apply the dynamic scaler to the `yClampedGradient` range in DEPTH (makes the gradient shallower for tall worlds, spreading terrain over more Y range).
3. Compensate the OFFSET constant to keep sea level anchored (shift the gradient zero-crossing).

The key difference from attempts 7-8: changing the gradient slope affects HOW the density transitions from solid to air per block, not just WHERE. This is why the SCALER approach moved terrain while the OFFSET approach didn't — the gradient is the primary control of terrain surface placement.

### Option C: Hybrid Approach

Combine a modest gradient change with the OFFSET boost:

1. Make the gradient slightly shallower (e.g., 2x instead of 4x) to extend the usable Y range.
2. Add a small OFFSET boost for above-ground terrain to shift mountain peaks into the extended range.
3. Compensate sea level so it stays at Y 63.

This splits the extension work between gradient and offset, avoiding the extreme values that either approach hits alone.
