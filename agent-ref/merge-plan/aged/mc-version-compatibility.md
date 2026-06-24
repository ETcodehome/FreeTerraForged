# MC Version Compatibility Analysis: Re (1.20.1) → ET (1.21.1) Beach Port

## 1. Executive Summary

**Overall difficulty: LOW-to-MEDIUM.** The Minecraft API surface that the beach subsystem actually touches changed very little between 1.20.1 and 1.21.1. The one confirmed API rename (`BootstapContext` → `BootstrapContext`) is already handled in ET — ET's codebase uses the corrected 1.21.1 name while Re's codebase still uses the typo. All Feature, FeaturePlaceContext, ChunkAccess, BlockState, Heightmap, DensityFunction, and Codec interfaces are structurally identical between the two versions. The dominant difficulty of this port comes from **differences between the two mod codebases themselves**, not from Minecraft API changes.

The beach subsystem has already been partially scaffolded into ET: `BeachDetect` exists (in a stripped-down form), `WorldFilters` already calls `BeachDetect.apply(...)`, the `Cell` class has `beachNoise`, and `ShoreGeometry` is already imported in Re's Heightmap. What's missing from ET is the full evaluator + material system + the surface feature + the wire-up of cell fields.

The hardest parts of the port are:

1. ET's `Cell` class is missing **all river/lake bank geometry fields** that `BeachEvaluator` depends on for river/lake shore detection (`riverBankAlpha`, `riverWidth`, `riverBankHeight`, `riverDepth`, `riverShoreAlpha`, `lakeShoreAlpha`, `lakeBankAlpha`, `lakeBankHeight`, `lakeDepth`, etc.)
2. ET's river system uses a completely different architecture (`RiverCarverSettings.RiverZone` enum, `UpliftRiverCarver`) which does not expose the same per-cell river geometry signals
3. ET's `TerrainCategory` and `ITerrain` lack `RIVER_SHORE`, `LAKE_SHORE`, `isRiverShore()`, `isLakeShore()`, and `isInlandShore()`
4. ET's `WorldSettings` lacks the `Beach` block entirely
5. ET's `Heightmap` record signature is completely different (shorter — no `beachSurfaceNoise`, `beachMaterialNoise`, `oceanCoastBandScale`, `oceanTransitionBias` fields)
6. ET's `ControlPoints` uses `islandInland`/`islandCoast` instead of Re's `mushroomFieldsInland`/`mushroomFieldsCoast`

## 2. Minecraft API Changes

| API | 1.20.1 | 1.21.1 | Status |
|-----|--------|--------|--------|
| `net.minecraft.data.worldgen.BootstapContext` | `BootstapContext<T>` (typo) | Renamed to `BootstrapContext<T>` (fixed) | **Breaking rename** — ET already uses the new name |
| `net.minecraft.world.level.levelgen.feature.FeaturePlaceContext` | Same interface | Same interface | No change |
| `net.minecraft.world.level.levelgen.feature.Feature<FC>` | `boolean place(FeaturePlaceContext<FC>)` | Same | No change |
| `net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration` | Same interface | Same interface | No change |
| `net.minecraft.world.level.chunk.ChunkAccess#setBlockState` | `BlockState setBlockState(BlockPos, BlockState, boolean)` | `@Nullable BlockState setBlockState(BlockPos, BlockState, @Block.UpdateFlags int flags)` | **Signature change**: bool→int. Both codebases call `setBlockState(pos, state, false)` — this compiles as `flags=0` via implicit widening and is **behaviorally compatible** |
| `net.minecraft.world.level.levelgen.RandomState` | Same public interface | Same public interface | No change |
| `net.minecraft.world.level.levelgen.DensityFunction` | `interface DensityFunction` with same method signatures | Same | No change; minor: `@Nullable` annotation source changed from `org.jetbrains.annotations` to `org.jspecify.annotations` in 1.21.1 — does not affect compilation |
| `net.minecraft.world.level.levelgen.Heightmap.Types` | Same enum | Same enum | No change |
| `net.minecraft.world.level.levelgen.feature.FeatureUtils#register(ctx, key, feature, config)` | `BootstapContext` in signature | `BootstrapContext` | Rename only, same shape |
| `net.minecraft.world.level.levelgen.placement.PlacementUtils#register` | Same | Same | No change |
| `net.minecraft.world.level.levelgen.GenerationStep.Decoration` | Same enum | Same enum | No change |
| `com.mojang.serialization.Codec` / `RecordCodecBuilder` | Same | Same | No change |
| `net.minecraft.core.registries.Registries` | Same | Same | No change |
| `net.minecraft.world.level.WorldGenLevel` | Same | Same | No change |
| `Blocks.MUD`, `Blocks.RED_SAND`, `Blocks.RED_SANDSTONE` | Present | Present | No change |

**Verdict:** No Minecraft API changes require code adaptation in the beach subsystem. The one confirmed rename (`BootstapContext` → `BootstrapContext`) is already resolved by ET's existing code.

**Methodology note:** This analysis was validated by comparing Re's beach code MC imports against ET's working 1.21.1 codebase (not decompiled MC source — the 1.21.1 decompilation did not complete). Re's beach subsystem imports exactly 11 MC classes (`BlockPos`, `Blocks`, `BlockState`, `ChunkAccess`, `ChunkPos`, `Feature`, `FeaturePlaceContext`, `FeatureConfiguration`, `Heightmap`, `RandomState`, `WorldGenLevel`), all of which ET already uses in its own 1.21.1 Feature subclasses (e.g., `SwampSurfaceFeature.java` uses the identical import set and `Feature<Config>` pattern). This means the API surface comparison is grounded in real compiled code, not training-data assumptions.

## 3. Per-File Portability Assessment

### `BeachType.java` (4 lines — pure enum)

**Portability: Trivially portable — copy as-is.**

Zero Minecraft API dependencies. The enum `{NONE, OCEAN, RIVER, LAKE}` is self-contained. Just copy into ET's `world/worldgen/cell/beach/` package.

Required ET changes: None (new file).

### `BeachMaterial.java` (10 lines — pure enum)

**Portability: Trivially portable — copy as-is.**

Zero Minecraft API dependencies. Copy into ET's `world/worldgen/cell/beach/` package.

### `ShoreGeometry.java`

**Portability: Trivially portable — copy as-is.**

Zero Minecraft API dependencies. Depends only on `ControlPoints` and `NoiseUtil`. ET already has `ShoreGeometry` imported in its own `Heightmap.java` — confirm it exists in ET or copy from Re.

### `BeachEvaluator.java`

**Portability: Requires significant adaptation (but no MC API changes).**

MC API dependencies: None directly. Depends entirely on mod-internal types.

Mod-internal dependencies that need ET adaptation:

- `Cell` fields: `beachType`, `beachMaterial`, `beachSurfaceAlpha`, `oceanShoreAlpha`, `oceanShoreDistance`, `beachSurfaceNoise`, `beachMaterialNoise`, `riverBankAlpha`, `riverWidth`, `riverBankHeight`, `riverDepth`, `riverShoreAlpha`, `lakeBankAlpha`, `lakeBankHeight`, `lakeDepth`, `lakeShoreAlpha`, `lakeAuthorityAlpha`, `sediment`, `regionTemperature`, `regionMoisture`
- `Cell.isAbsent()` — ET has this, no issue
- `TerrainType.RIVER_SHORE`, `LAKE_SHORE` — ET **does not have** these terrain types
- `ITerrain.isInlandShore()`, `isRiverShore()`, `isLakeShore()` — ET `ITerrain` **does not have** these methods
- `WorldSettings.Beach`, `WorldSettings.Ocean`, `WorldSettings.River`, `WorldSettings.Lake`, `WorldSettings.Variance`, `WorldSettings.MaterialPalette`, `WorldSettings.OceanGeometry`, `WorldSettings.RiverGeometry`, `WorldSettings.LakeGeometry` — ET `WorldSettings` **does not have** the `Beach` block
- `ControlPoints` type: Re uses `raccoonman.reterraforged.world.worldgen.cell.heightmap.ControlPoints` (a dedicated record with `coastMarker()` method); ET uses `WorldSettings.ControlPoints` directly (inlined, also has `coastMarker()` method). These are structurally compatible — the `coastMarker()` method exists on both.
- `Levels` — exists in both

The missing river/lake bank fields are the deepest problem. Re's river system computes `riverBankAlpha`, `riverShoreAlpha`, `riverWidth`, `riverBankHeight`, `riverDepth` during tile generation. ET uses a completely different river carver (`UpliftRiverCarver`) that sets `cell.riverZone` (an enum: `None`, `Banks`, `Riverbed`, `ValleyFloor`, `ValleyFadeout`) and `cell.riverWaterLevel`, not these per-cell float geometry fields. This means the river/lake shore beach detection in `BeachEvaluator` — the portions using `getRiverSurfaceAlpha()` and `getLakeSurfaceAlpha()` — **cannot function as-is** in ET without either: (a) porting Re's river bank computation into ET's river system, or (b) building bridge fields computed from ET's `riverZone`.

The ocean-shore beach detection (`getOceanSurfaceAlpha()`) does not need river fields and can function independently.

Required ET changes:

1. Add `BeachType`, `BeachMaterial`, `ShoreGeometry` to ET (new files)
2. Add `Beach` settings block to ET `WorldSettings`
3. Add `RIVER_SHORE` / `LAKE_SHORE` to ET `TerrainCategory` and `ITerrain`
4. Add missing Cell fields: `beachType`, `beachMaterial`, `beachSurfaceAlpha`, `oceanShoreAlpha`, `oceanShoreDistance`, `beachSurfaceNoise`, `beachMaterialNoise`, plus either the full river/lake bank fields or stubs that the evaluator can tolerate
5. Decide scope: port full evaluator (ocean + river + lake shores) or phase 1 ocean shore only

### `BeachSurfaceFeature.java`

**Portability: Nearly direct copy with one dependency adjustment.**

MC API dependencies: `FeaturePlaceContext`, `WorldGenLevel`, `ChunkAccess`, `Heightmap`, `RandomState`, `Feature`, `FeatureConfiguration`, `BlockPos`, `BlockState`, `ChunkPos`, `Codec`, `RecordCodecBuilder` — all unchanged between 1.20.1 and 1.21.1.

Mod-internal dependencies:

- `RTFRandomState.generatorContext()` — identical in both codebases
- `GeneratorContext.cache` (a `TileCache`) and `generatorContext.lookup` — identical in both
- `Tile.Chunk` and `tileChunk.getCell(x, z)` — needs verification in ET tile system (both have `TileCache` and `TileGenerator`, but ET's tile internals differ more)
- `Cell.beachType`, `Cell.beachMaterial`, `Cell.beachSurfaceAlpha`, `Cell.beachSurfaceNoise` — need to be added to ET Cell
- `ColumnDecorator.replaceSolid(chunk, pos, state)` — ET has identical `ColumnDecorator` with identical signature

Required ET changes: Almost none to the MC API surface. The `ColumnDecorator.replaceSolid` call uses `setBlockState(pos, state, false)` which compiles fine in 1.21.1 (the boolean overload is removed in vanilla but NeoForge keeps or the `false` promotes to `int 0`). The critical prerequisite is having the Cell fields available.

Note: `BootstapContext` vs `BootstrapContext` does not appear in this file.

### `BeachDetect.java` (filter)

**Portability: ET already has a `BeachDetect` but it is a stub that only classifies BEACH terrain type, not the full Re system.**

Re's `BeachDetect` is a `Filter` record wrapping a `BeachEvaluator`. It applies the full two-pass algorithm (per-cell evaluate + continuity pass). ET's `BeachDetect` is a simpler record with only `Levels` and `ControlPoints`, performing only the gradient-based BEACH terrain classification.

ET already has the hook in `WorldFilters.apply()` calling `this.beach.apply(...)` — the integration point exists and will remain valid. The Re `BeachDetect.make(GeneratorContext)` factory method needs `ctx.preset.world().beaches` which is a `WorldSettings.Beach` — that block doesn't exist in ET yet.

Required ET changes: Replace ET's stub `BeachDetect` with Re's full version once Cell fields and `WorldSettings.Beach` are in place. The `SnapshotBuffers` / `SnapshotNeighborhood` inner classes require only mod-internal types.

### `WorldSettings.java` (Beach config additions)

**Portability: Additive — add `Beach` inner class and update top-level codec.**

ET `WorldSettings` has 3 fields (`continent`, `controlPoints`, `properties`). Re has 4 (`continent`, `controlPoints`, `beaches`, `properties`). The `Beach` class, plus all sub-classes (`Variance`, `Ocean`, `River`, `Lake`, `OceanGeometry`, `RiverGeometry`, `LakeGeometry`, `MaterialPalette`), are pure data/codec classes with zero MC API dependencies. They use only `com.mojang.serialization.Codec` and `RecordCodecBuilder`, which are unchanged.

One structural difference: Re's `WorldSettings.ControlPoints` has `mushroomFieldsInland`/`mushroomFieldsCoast` while ET's has `islandInland`/`islandCoast`. These are semantically equivalent roles (marking mushroom island territories) but differently named. The `BeachEvaluator` uses only `controlPoints.coastMarker()`, `controlPoints.beach()`, `controlPoints.shallowOcean()` — these exist in both.

### `Presets.java` (beach defaults)

**Portability: Additive — add `makeBeachSettings()` call to each preset constructor.**

ET `Presets` has `makeRTFDefault()` but constructs `WorldSettings` with only 3 args. Adding a `Beach` arg means updating all `new WorldSettings(...)` call-sites. No MC API changes needed — pure data construction.

### `PresetConfiguredFeatures.java` (feature registration)

**Key MC API difference confirmed: `BootstapContext` → `BootstrapContext`.**

Re imports `net.minecraft.data.worldgen.BootstapContext` (typo, 1.20.1). ET already imports `net.minecraft.data.worldgen.BootstrapContext` (fixed, 1.21.1). The `bootstrap(Preset preset, BootstrapContext<ConfiguredFeature<?, ?>> ctx)` method signature changes accordingly — but ET's file already uses the correct name.

To add beach registration: add `BEACH_SURFACE` key and the `FeatureUtils.register(ctx, BEACH_SURFACE, RTFFeatures.BEACH_SURFACE, new BeachSurfaceFeature.Config(...))` call. The `FeatureUtils.register` signature is identical in 1.21.1.

### `Cell.java` (beach fields)

**Portability: Additive field additions only.**

ET's `Cell` is missing all beach-related fields present in Re's `Cell`. Fields to add:

- `public BeachType beachType` (initialized to `BeachType.NONE`)
- `public BeachMaterial beachMaterial` (initialized to `BeachMaterial.NONE`)
- `public float beachSurfaceAlpha`
- `public float oceanShoreAlpha`
- `public float oceanShoreDistance`
- `public float beachSurfaceNoise` (ET has only `float beachNoise` — these are different noises)
- `public float beachMaterialNoise`

The deeper question is whether to also add Re's river/lake bank fields:

- `float riverBankAlpha`, `riverWidth`, `riverBankWidth`, `riverBankHeight`, `riverDepth`, `riverShoreAlpha`, `riverShoreDistance`
- `float lakeShoreAlpha`, `lakeAuthorityAlpha`, `lakeDistance`, `lakeBankAlpha`, `lakeBankHeight`, `lakeDepth`

These are **not computed by ET's current river system at all**. Adding the fields but not populating them would mean all river/lake beach detection returns 0 (no river/lake shores painted). This may be acceptable for an initial port scoped to ocean beaches only.

Note: ET already has `public float sediment`, `regionTemperature`, `regionMoisture` — all used by `BeachEvaluator.selectMaterial()`. These are compatible.

### `TerrainType.java` / `TerrainCategory.java` (RIVER_SHORE, LAKE_SHORE)

**Portability: Additive enum entries only.**

ET is missing `RIVER_SHORE` and `LAKE_SHORE` terrain types. Re added them between the two codebases. These are not MC API items — they're mod-internal terrain registry entries.

ET's `ITerrain` also needs `isRiverShore()`, `isLakeShore()`, and `isInlandShore()` methods (and their `Delegate` counterparts) which are absent from ET's version. These are pure mod additions with zero MC dependency.

## 4. Registry and Data Generation Changes

| Area | Re (1.20.1) | ET (1.21.1) | Impact |
|------|-------------|-------------|--------|
| `BootstapContext` for data gen | `BootstapContext<T>` (typo) | `BootstrapContext<T>` | Name change only — ET already correct |
| `FeatureUtils.register(ctx, key, feature, config)` | Takes `BootstapContext` | Takes `BootstrapContext` | Same shape, just rename |
| `PlacementUtils.register(ctx, key, holder)` | Same | Same | No change |
| `ResourceKey.create(Registries.CONFIGURED_FEATURE, ...)` | Same | Same | No change |
| `GenerationStep.Decoration.RAW_GENERATION` | Same enum value | Same enum value | No change |
| `HolderGetter<PlacedFeature>` / `ctx.lookup(Registries.PLACED_FEATURE)` | Same | Same | No change |

The biome modifier registration pattern for `ADD_BEACH_SURFACE` (registering a `PlacedFeature` to `GenerationStep.Decoration.RAW_GENERATION` for all biomes) is identical between Re and ET's internal biome modifier system. ET's `BiomeModifiers.add(Order.APPEND, step, HolderSet)` is the same call shape. ET's `PresetBiomeModifierData` already has `ADD_SWAMP_SURFACE` as an example to follow.

## 5. Loader Differences (Forge → NeoForge)

Re targets Forge (1.20.1) + Fabric. ET targets NeoForge (1.21.1) + Fabric. The beach code is entirely in the `common` module with zero loader-specific code. Neither `BeachEvaluator`, `BeachSurfaceFeature`, `BeachDetect`, `BeachType`, `BeachMaterial`, nor `ShoreGeometry` import anything from `net.minecraftforge` or `net.neoforged`. The beach subsystem touches no NeoForge event listeners, no Forge registry events, no `@Mod` annotations, and no loader-specific biome modifier hooks.

The one loader-specific pattern that *does* matter is biome modifier registration. Re uses `ForgeBiomeModifier` (from the Forge API) in its `forge/` source tree. ET uses `ForgeBiomeModifier` (from NeoForge) in its `neoforge/` source tree. Both use the same internal abstract `BiomeModifier` abstraction in `common/`. The `ADD_BEACH_SURFACE` biome modifier entry in `PresetBiomeModifierData.java` sits entirely in `common/` and goes through this abstraction — so it ports without loader changes.

**NeoForge-specific Codec change**: In ET's `BiomeModifiersImpl.register()`, the parameter type changed from `Codec<? extends BiomeModifier>` (Forge) to `MapCodec<? extends BiomeModifier>` (NeoForge). This is already handled in ET — it applies only to the modifier type registration, not to the beach feature itself.

## 6. Risk Areas

### Risk 1 (HIGH): River/lake shore detection requires re-engineering

`BeachEvaluator.getRiverSurfaceAlpha()` and `getLakeSurfaceAlpha()` depend on 12+ per-cell float fields (`riverBankAlpha`, `riverWidth`, `riverBankHeight`, `riverDepth`, `riverShoreAlpha`, `lakeShoreAlpha`, `lakeBankAlpha`, `lakeBankHeight`, `lakeDepth`, etc.) that ET's river system (`UpliftRiverCarver`) does not compute. ET instead uses `cell.riverZone` (an enum: `None`, `Banks`, `Riverbed`, `ValleyFloor`, `ValleyFadeout`) and `cell.riverWaterLevel`. To enable river/lake shore beach painting, one of these approaches is needed:

- **Option A (Phase 1 scope reduction)**: Implement ocean-shore beaches only. Stub out `getRiverSurfaceAlpha()` and `getLakeSurfaceAlpha()` to return `0.0F`. This is safe and avoids river system changes. River/lake shore beaches can be phased in later.
- **Option B (Full port)**: Map ET's `RiverZone.Banks` to a synthetic `riverBankAlpha` and build compatible geometry signals from the UpliftRiverCarver data. This requires understanding ET's river geometry deeply.

### Risk 2 (MEDIUM): Cell field population ordering

Re's `Heightmap.applyTerrain()` initializes `beachSurfaceNoise` and `beachMaterialNoise` at the start of the terrain pass. ET's `Heightmap.applyTerrain()` signature is a different record (it stores only `beachNoise`, not the pair). Adding the new fields to ET's `Heightmap` record signature requires updating all its construction sites (`Heightmap.make(context)` in `GeneratorContext`). The `Heightmap.make()` factory in Re (line 138-142) uses `context.seed.next()` for the noise instances — this must be placed after all existing seed draws to avoid shifting seed sequences for all other noise.

### Risk 3 (MEDIUM): `BeachDetect` replacement breaks the current beach gradient filter

ET's current `BeachDetect` also sets `cell.terrain = TerrainType.BEACH` for gradient-classified coast cells. Re's `BeachEvaluator` also sets terrain to `TerrainType.BEACH` (line 45 of `BeachEvaluator`) as part of the ocean surface alpha path. If the existing ET `BeachDetect` is simply replaced, verify that beach biome classification still fires correctly — Re's evaluator only sets `BEACH` if `isBeachBiomeCandidate()` is also true (i.e., `cell.continentEdge < controlPoints.beach()`), while ET's current filter unconditionally fires for any coast cell with sufficient gradient.

### Risk 4 (LOW): ControlPoints differences

Re's `WorldSettings.ControlPoints` has `mushroomFieldsInland`/`mushroomFieldsCoast` while ET has `islandInland`/`islandCoast`. The `BeachEvaluator` uses only `controlPoints.coastMarker()`, `controlPoints.beach()`, `controlPoints.shallowOcean()` — these exist in both. However, `BeachDetect.make(GeneratorContext)` in Re calls `ControlPoints.make(ctx.preset.world().controlPoints)` which instantiates Re's dedicated `ControlPoints` record. ET does not have this intermediary record — it uses `WorldSettings.ControlPoints` directly (which also has `coastMarker()`). The `BeachEvaluator` constructor takes `ControlPoints` (the Re record type) — the port must either keep Re's `ControlPoints` record or parameterize `BeachEvaluator` with `WorldSettings.ControlPoints` instead.

### Risk 5 (LOW): Tile system differences

`BeachSurfaceFeature.place()` calls `generatorContext.cache.provideAtChunk(chunkPos.x, chunkPos.z).getChunkReader(...)`. Both ET and Re have `TileCache`, but ET's tile system has evolved (ET has `ContinentalHydrology`, `IslandBlender`, etc. in its terrain pipeline). The `TileCache` and `Tile.Chunk.getCell()` interface is used identically in both, but confirm ET's `TileCache` API hasn't changed in ways the feature relies on.
