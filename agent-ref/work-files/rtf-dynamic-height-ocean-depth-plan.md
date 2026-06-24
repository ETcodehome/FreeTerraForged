# RTF Dynamic Height And Ocean Depth Plan

## Objective

Implement real preset-driven behavior for vertical terrain scaling and ocean depth in the RTF fork. The result must survive QA by changing actual generated terrain, not only serialized JSON or GUI controls.

## Current Findings

- `MixinNoiseBasedChunkGenerator` caps generated terrain height with `cell.height * 256.0F`, causing 1024-height presets to truncate around Y 384/385 when `min_y = -128`.
- `WorldSettings.Properties.terrainScaler()` returns `Math.min(worldHeight, 256)`, so terrain shape, ocean shape, previews, and noise-router height quantization are still effectively 256-scale.
- RTF already owns overworld noise settings through the exported datapack, so stacking another deeper-oceans datapack is fragile and load-order-sensitive.

## Post Initial Ocean Fix Status

Commit `fe1ef47` added configurable ocean depth and changed real generation:

- `WorldSettings.Ocean` now has `shallowOceanDepth`, `deepOceanMinDepth`, `deepOceanMaxDepth`, and `oceanDepthNoiseScale`.
- `Populators.makeShallowOcean` uses `seaLevel - shallowOceanDepth`, clamped to the configured world bottom.
- `Populators.makeDeepOcean` maps ocean floor noise between `seaLevel - deepOceanMinDepth` and `seaLevel - deepOceanMaxDepth`, clamped to the configured world bottom.
- `OceanPopulator` no longer hard-clamps ocean floor height to `>= 0`; it clamps to the supplied `minHeight`, and deep/shallow oceans pass `levels.min`.

QA note from 2026-06-22: a deep ocean was observed down to about `Y=0`. That is deeper than the old behavior in typical generated terrain and confirms the configurable-depth work is having an effect. However, `Y=0` is not necessarily the expected maximum depth. With default `seaLevel=63` and `deepOceanMaxDepth=96`, some deep-ocean cells can theoretically go below `Y=0` down toward `Y=-33`; whether a specific sampled spot reaches that depends on the blended hills/canyons ocean-floor noise and the preset's exported values.

Follow-up QA should sample several deep-ocean points and compare observed floor Y against the exported `deepOceanMinDepth`/`deepOceanMaxDepth`. If no deep-ocean floor ever goes below `Y=0` when `seaLevel - deepOceanMaxDepth < 0`, then there is still another clamp or surface/filler behavior to find.

## Files To Change

- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/settings/WorldSettings.java`
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/GeneratorContext.java`
- `common/src/main/java/raccoonman/reterraforged/mixin/MixinNoiseBasedChunkGenerator.java`
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetNoiseRouterData.java`
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetSurfaceNoise.java`
- `common/src/main/java/raccoonman/reterraforged/data/worldgen/preset/PresetTerrainTypeNoise.java`
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/heightmap/Heightmap.java`
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java`
- `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/populator/OceanPopulator.java`
- `common/src/main/java/raccoonman/reterraforged/client/gui/screen/presetconfig/WorldSettingsPage.java`
- `common/src/main/resources/assets/reterraforged/lang/en_us.json`

## Implementation Plan

1. Preserve existing preset compatibility.
   - Add optional fields with defaults, not required fields, wherever possible.
   - Existing presets without ocean-depth settings must decode and generate.

2. Replace hardcoded 256 terrain height scaling.
   - Change `terrainScaler()` so it returns the effective preset terrain scale, likely `worldHeight`.
   - Audit callers before changing it because this impacts all terrain amplitude.
   - In `MixinNoiseBasedChunkGenerator`, replace `cell.height * 256.0F` with `cell.height * generatorContext.levels.worldHeight` or equivalent effective terrain scale.
   - Confirm `MaxHeightUtil` still receives a block-space top height, not normalized terrain height.

3. Add preset-owned ocean-depth settings.
   - Prefer adding an `OceanDepth` nested config under `WorldSettings.Properties` or under `WorldSettings.Beach.Ocean`.
   - Suggested fields:
     - `shallowOceanDepth`: default `7`
     - `deepOceanMinDepth`: default around `32`
     - `deepOceanMaxDepth`: default around `96`
     - `oceanDepthNoiseScale`: default around `150`
   - Keep values as blocks below sea level because that is what users will understand and QA can inspect.

4. Wire ocean settings into real generation.
   - Change `Heightmap.make` to pass the preset ocean-depth settings into `Populators.makeDeepOcean` and `makeShallowOcean`.
   - Change shallow ocean from `levels.water(-7)` to `levels.water(-shallowOceanDepth)`.
   - Change deep ocean to generate floor heights between `seaLevel - deepOceanMinDepth` and `seaLevel - deepOceanMaxDepth`, with noise variation and canyon noise.
   - Remove or replace `OceanPopulator`'s `Math.max(..., 0.0F)` clamp so negative normalized heights are allowed down to the configured world bottom.
   - Clamp to `levels.scale(-worldDepth)` or equivalent lower normalized bound, not to zero.

5. Update GUI and language strings.
   - Add sliders to `WorldSettingsPage` for the new ocean settings.
   - Slider ranges should be practical: shallow `1..48`, deep min `8..192`, deep max `16..worldDepth` or `16..256`.
   - Enforce `deepOceanMaxDepth >= deepOceanMinDepth`.

6. Update preset export behavior.
   - Verify generated datapack `preset.json` includes new values.
   - Verify `noise_settings/overworld.json` changes when ocean depth settings change, not only `preset.json`.

7. Build and QA.
   - Run `./gradlew build`.
   - Export two datapacks from the same seed: shallow defaults and deeper ocean settings.
   - Compare ocean-floor Y in the same deep-ocean coordinate. The deeper config must produce lower floor Y.
   - Test `worldHeight: 1024`, `worldDepth: 128`, `seaLevel: 63`; mountains must no longer cap at Y 385.
   - Test a lower-height preset such as `worldHeight: 256`, `worldDepth: 64` to avoid regressions.

## QA Acceptance Criteria

- Changing `worldHeight` changes the actual maximum generated terrain considered by dynamic height optimization.
- No flat cutoff appears at Y 385 in a `worldHeight: 1024`, `worldDepth: 128` world.
- Changing `shallowOceanDepth` changes shallow-ocean floor Y in generated chunks.
- Changing `deepOceanMinDepth` or `deepOceanMaxDepth` changes deep-ocean floor Y in generated chunks.
- Deep oceans can generate below Y 0 when configured and when `worldDepth` permits it.
- Existing presets without new ocean-depth fields still load.

## Operational Notes

- After recompiling, replace `services/minecraft-fabric/mods/reterraforged-0.0.6004-fabric-1.21.1.jar`.
- Stop the server before replacing the mod or deleting the world.
- Preserve required datapacks before deleting the world, especially `Modern Earthlike 3D Rivers.zip` and `large-ore-veins-deluxe-v1.3.0.zip`.
- Delete `world/data/DistantHorizons.sqlite*` after terrain behavior changes so stale LODs do not mask QA results.
- Set Distant Horizons to `PRE_EXISTING_ONLY` during QA to avoid extra generator threads while validating RTF behavior.
