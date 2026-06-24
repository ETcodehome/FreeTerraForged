# Integration Plan: ET Beach/Shore Port

## 1. Executive Summary

Merge PR #77 into the ET base first as a standalone commit since it improves terrain geometry without touching any beach-system interfaces. Then port Re's beach subsystem on top, adapting the evaluator to derive shore-alpha metadata from ET's zone-based river model rather than copying Re's classic `RiverCarver` field-emission approach. The result is a layered architecture where ET owns all geometry (uplift, rivers, wetlands, lakes) and Re's `BeachEvaluator`/`BeachSurfaceFeature` pair sits above it as a pure classification-and-painting stage.

## 2. Merge Order

### Step 1 — Apply PR #77

PR #77 only modifies `UpliftRiverCarver.java` and `Wetland.java`. It has no dependency on any beach-system code. Apply it first so all subsequent beach work is built on the improved terrain geometry.

Rationale: PR #77's zone boundaries (`zone1Radius`, `zone2Radius`, `zone3Radius`, `zone4Radius`) and the new `warpedLinearDist`/`discrepancyFactor` variables are exactly what the shore-alpha adapter will read. Getting that geometry stable before wiring the adapter avoids a two-step churn of `UpliftRiverCarver`.

### Step 2 — Port the beach subsystem

All beach files are additive (new classes) except these existing files that need modification:

- `Cell.java`
- `TerrainCategory.java` (or `ITerrain.java`)
- `BeachDetect.java`
- `WorldLookup.java`
- `WorldSettings.java`
- `Presets.java`
- `Heightmap.java`
- `RTFFeatures.java`
- `PresetConfiguredFeatures.java`
- `PresetPlacedFeatures.java`
- `PresetBiomeModifierData.java`

The new adapter class `UpliftShoreEmitter.java` must be written as part of step 2.

## 3. PR #77 Integration

**How to apply:** Cherry-pick or manually apply the diff. The diff touches exactly two files. Conflicts with current ET base are unlikely since the PR targets ETcodehome/ReTerraForged and the RTF clone is from the same repo.

**Specific changes from PR #77:**

- Adds `slopeRoughnessNoise`, `scarNoise`, `valleyWallWarpNoise` fields and seeds them.
- Adds `steepSlope`/`gentleSlope` constants computed from `Math.tan()`.
- Introduces `warpedLinearDist` alongside unwarped `currentLinearDist` (channel uses unwarped, valley walls use warped).
- Modifies `bedWidth` minimum from `0.25F` to `1.5F`.
- Adds `depthScaling` to make narrower rivers shallower.
- Adds `discrepancyFactor`/`mountainFactor`/`heightDiscrepancy` tracking.
- Replaces hard `discrepancyScale` with slope-derived value clamped to `[1.0, 6.0]`.
- Zone 4 radius now uses `warpedLinearDist` for the early-return check.
- `carveZone2BankStep` gains a `flatnessFactor` parameter, now calls `applyTerracing` with per-zone step/edge-width/strength params.
- `carveZone4Fadeout` gains four additional parameters and implements slope clipping, talus fans, roughness, and scar features. Uses `profileProgress` with concave lower section.
- Old `applyTerracing(float, float, float, float)` replaced by `applyTerracing(float, float, float, float, float, float)` and a new `softStep(float, float, float)` helper.
- `Wetland.java`: adds `bankRoughness` noise; replaces `tStart`/`totalAlpha` with `edgeWarp`/`warpedDist`/`rawAlpha`/`internalAlpha`; adds slope threshold `thresholdHeight`; adds `bankRoughness` wall perturbation; restricts wetland biome assignment to `featureEdge > tEnd && height < localWaterSurface + 5 blocks`; removes the duplicate `terrain = WETLAND` assignment that appeared twice in the original.

**Expected conflicts:** None in `UpliftRiverCarver` or `Wetland`. The only risk is if the PR base commit differs from the RTF tip. Verify with `gh pr view 77 --repo ETcodehome/ReTerraForged --json baseRefName` and ensure the branch applies cleanly before continuing.

## 4. Beach System Port — File by File

### File A: `BeachType.java`

- **Source:** `ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachType.java`
- **Target:** `RTF/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachType.java`
- **Operation:** New file, copy verbatim. No changes needed. Package declaration matches.

### File B: `BeachMaterial.java`

- **Source:** `ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachMaterial.java`
- **Target:** `RTF/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachMaterial.java`
- **Operation:** New file, copy verbatim.

### File C: `ShoreGeometry.java`

- **Source:** `ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/ShoreGeometry.java`
- **Target:** `RTF/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/ShoreGeometry.java`
- **Operation:** New file, copy verbatim. Depends on `ControlPoints` and `NoiseUtil` both of which exist in ET at the same paths. Note: Re's `ShoreGeometry` imports `raccoonman.reterraforged.world.worldgen.cell.heightmap.ControlPoints` — this is the `ControlPoints` record class that exists in Re. ET does not have this class but does have `WorldSettings.ControlPoints` and the `ControlPoints` record in `heightmap`. Verify the Re `ControlPoints` record is ported or mapped (see Cell.java section).

### File D: `BeachEvaluator.java`

- **Source:** `ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java`
- **Target:** `RTF/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/beach/BeachEvaluator.java`
- **Operation:** New file with targeted modifications.

The evaluator requires these fields on `Cell`: `riverBankAlpha`, `riverWidth`, `riverDepth`, `riverBankHeight`, `riverShoreAlpha`, `lakeShoreAlpha`, `lakeBankAlpha`, `lakeDepth`, `lakeBankHeight`, `beachSurfaceNoise`, `beachMaterialNoise`, `beachSurfaceAlpha`, `oceanShoreAlpha`, `oceanShoreDistance`, `riverShoreDistance`, `lakeDistance`, `lakeAuthorityAlpha`.

Many of these are NOT present on ET's `Cell`. They are produced by Re's `RiverCarver`. Since ET uses `UpliftRiverCarver` instead, these must come from the new adapter (see Shore Alpha Adapter section below).

Additionally, `BeachEvaluator` uses these `Terrain` methods that do not exist in ET's `ITerrain`:

- `isInlandShore()` — checks `isRiverShore() || isLakeShore()`
- `isRiverShore()`
- `isLakeShore()`

These must be added to ET's `ITerrain` and implemented by two new `TerrainCategory` entries.

The evaluator also references `WorldSettings.Beach`, `WorldSettings.Ocean`, `WorldSettings.River`, `WorldSettings.Lake`, `WorldSettings.MaterialPalette`, `WorldSettings.Variance` — all of which need to be added to ET's `WorldSettings.java`.

The evaluator imports `raccoonman.reterraforged.world.worldgen.cell.heightmap.ControlPoints` (the Re record). ET has this at the same path — use ET's existing `ControlPoints` record at `heightmap/ControlPoints.java`. Verify `coastMarker()` is accessible: it is available on ET's `ControlPoints` record (`points.coast + (points.inland - points.coast) / 2.0F`). The evaluator calls `this.controlPoints.coastMarker()`, `this.controlPoints.beach()`, `this.controlPoints.coast()`, `this.controlPoints.shallowOcean()` — all map to fields in ET's record but the record accessor names differ. ET's record uses positional accessors: `coastMarker` is a computed field. Match field names carefully.

**Key adaptation — height range for elevated rivers:** Change the `inHeightRange` methods for `River` and `Lake` to use `cell.riverWaterLevel` as the base instead of `this.levels.water(N)` for elevated rivers. Specifically, the methods at lines 209 and 215 currently call `this.levels.water(settings.minHeight)` — this is fine for ocean (uses global sea level) but for river and lake shores in ET's uplift model, the local water surface is `cell.riverWaterLevel + levels.water`. Add a helper:

```java
private float resolveLocalBase(Cell cell) {
    return (cell.riverWaterLevel > 0.0F) ? this.levels.water + cell.riverWaterLevel : this.levels.water;
}
```

Then in `inHeightRange(Cell, River)` and `inHeightRange(Cell, Lake)`, use `resolveLocalBase(cell) + (settings.minHeight * levels.unit)` instead.

### File E: `BeachSurfaceFeature.java`

- **Source:** `ReTerraForged/common/src/main/java/raccoonman/reterraforged/world/worldgen/feature/BeachSurfaceFeature.java`
- **Target:** `RTF/common/src/main/java/raccoonman/reterraforged/world/worldgen/feature/BeachSurfaceFeature.java`
- **Operation:** New file, copy with one change.

Re uses `BootstapContext` (1.20.1 spelling). ET uses `BootstrapContext` (1.21.1 corrected spelling — visible in `PresetConfiguredFeatures.java` line 109 import `net.minecraft.data.worldgen.BootstrapContext`). The feature class itself does not import `BootstrapContext` so no change is needed there.

Re's `BeachSurfaceFeature` imports `raccoonman.reterraforged.world.worldgen.feature.BeachSurfaceFeature.Config` for the inner class — no change needed.

The `ColumnDecorator.replaceSolid()` call at line 101 references the same class that exists in both repos at `feature/ColumnDecorator.java` — no porting needed.

The `Tile.Chunk` and `GeneratorContext` references are at identical paths in ET. Copy verbatim.

### File F: New adapter `UpliftShoreEmitter.java`

No Re equivalent — must be written fresh. See section 5.

## 5. Shore Alpha Adapter Design

This is the central design challenge. Re's `RiverCarver.carve()` (lines 84–97) computes and writes `riverShoreAlpha`, `riverShoreDistance`, `riverWidth`, `riverBankWidth`, `riverDepth`, `riverBankHeight`, `riverBankAlpha` as it carves each cell. ET's `UpliftRiverCarver.carve()` does not write any of these fields.

The correct approach is to emit these fields at the end of `UpliftRiverCarver.carve()`, after zone assignment is complete but before returning.

### Zone-to-field mapping

The four zones ET assigns are `Riverbed`, `Banks`, `ValleyFloor`, `ValleyFadeout`. The `BeachEvaluator.getRiverSurfaceAlpha()` reads `cell.riverBankAlpha` as a gate (if `<= 0` skip), then reads `riverShoreAlpha`, `riverWidth`, `riverDepth`, `riverBankHeight`.

Map as follows:

**`riverBankAlpha`:** Set to a value > 0 for cells in `Banks`, `ValleyFloor`, or `ValleyFadeout` zones. Specifically:

- `Banks` zone: `riverBankAlpha = 1.0F - (currentLinearDist - zone1Radius) / (zone2Radius - zone1Radius)` (progress inverted, so 1.0 at inner edge of banks, 0.0 at outer)
- `ValleyFloor` zone: `riverBankAlpha = 0.6F` (flat floor has moderate alpha)
- `ValleyFadeout` zone: `riverBankAlpha = NoiseUtil.clamp(1.0F - (warpedLinearDist - zone3Radius) / (zone4Radius - zone3Radius), 0.0F, 0.6F)` (fades to 0 at outer edge)
- `Riverbed` zone: `riverBankAlpha = 0.0F` (below water, not a shore)

**`riverShoreAlpha`:** The outer edge of the bank step (zone2Radius boundary) is the shore transition. Compute as:

```java
float shoreProgress = (currentLinearDist - zone1Radius) / Math.max(zone2Radius - zone1Radius, 0.001F);
shoreProgress = NoiseUtil.clamp(shoreProgress, 0.0F, 1.0F);
cell.riverShoreAlpha = Math.max(cell.riverShoreAlpha, 1.0F - shoreProgress);
```

Only write if in `Banks` or `ValleyFloor` zone.

**`riverShoreDistance`:** Distance in normalized units from the bank inner edge: `Math.max(0.0F, currentLinearDist - zone1Radius)`.

**`riverWidth`:** Map `zone1Radius` (the bed radius in world units) to normalized [0..1]. Use `levels.scale(zone1Radius)` to convert to block units. Cap at a reasonable max (e.g., `riverWidth = NoiseUtil.clamp(zone1Radius / 20.0F, 0.0F, 1.0F)`).

**`riverDepth`:** The target bed depth in blocks. Already computable from `bedDepthOffset * dynamicDepthMult`: `riverDepth = Math.max(0, levels.scale(bedDepthOffset))`. Store as normalized: `cell.riverDepth = Math.max(cell.riverDepth, NoiseUtil.clamp(levels.scale(bedDepthOffset) / 20.0F, 0.0F, 1.0F))`.

**`riverBankHeight`:** Height of bank above water in blocks. From `bankHeightOffset = config.maxBankHeight - config.minBankHeight`: `cell.riverBankHeight = Math.max(cell.riverBankHeight, Math.max(0, levels.scale(bankHeightOffset)))`.

### Lake fields

The lake widen logic in `UpliftRiverCarver` (lines 151–177) runs when `shouldWidenOnPlateau()` is true. When this triggers, treat the widened zone1 as a "lake" area. After carving a lake-expanded riverbed:

- `cell.lakeShoreAlpha = widenMultiplier > 1.2F ? NoiseUtil.clamp((widenMultiplier - 1.0F) / 2.0F, 0.0F, 1.0F) : 0.0F`
- `cell.lakeBankAlpha = lakeShoreAlpha` (same gate value)
- `cell.lakeDepth = cell.riverDepth * lakeConfig.depth / 50.0F` (scaled lake depth)
- `cell.lakeBankHeight = cell.riverBankHeight`

### Where in the pipeline to write

Add a private method `emitShoreMetadata(Cell cell, float currentLinearDist, float warpedLinearDist, float zone1Radius, float zone2Radius, float zone3Radius, float zone4Radius, float bedDepthOffset, float bankHeightOffset, float widenMultiplier)` called from the bottom of `carve()`, just before the `updateValleyMask()` call. This ensures all zone assignments are finalized before reading them.

### How PR #77 helps

PR #77's softer `carveZone2BankStep` (which now has `flatnessFactor`-dependent terrace steps) and `warpedLinearDist` warping make the zone 2 boundary more organic, which in turn makes `riverShoreAlpha` vary naturally across the bank — this is a better signal than Re's classic `shoreAlpha` which used a separate hard `shoreWidth` range. No complication arises from PR #77; it strictly improves the geometry that the adapter reads.

### Height-gate adaptation

`BeachEvaluator.inHeightRange()` for river/lake uses `this.levels.water(N)` which assumes global sea level. In ET, rivers can be elevated — `cell.riverWaterLevel` holds the local water offset from the `ContinentalHydrology` model. The fix described in File D above (`resolveLocalBase`) ensures height gates are relative to the actual local water surface, not global sea level.

## 6. Cell.java Merge Strategy

### ET fields to keep (all present, no conflicts)

`waterTable`, `riverWaterLevel`, `riverZone`, `continentSizeModifier`, `continentDistance`, `terrainRegionCenterX/Z`, `macroBiomeId`, `continentX/Z` — all ET-only.

### Re fields to add (none conflict with existing ET fields)

| Field | Notes |
|-------|-------|
| `float riverWidth` | absent from ET |
| `float riverDepth` | absent |
| `float riverBankHeight` | absent |
| `float riverBankAlpha` | absent |
| `float beachSurfaceNoise` | ET has `beachNoise` (different purpose). Keep ET's `beachNoise` AND add Re's `beachSurfaceNoise` as a separate field. |
| `float beachMaterialNoise` | absent |
| `float beachSurfaceAlpha` | absent |
| `float oceanShoreAlpha` | absent |
| `float oceanShoreDistance` | absent |
| `float riverShoreAlpha` | absent |
| `float lakeShoreAlpha` | absent |
| `float lakeBankAlpha` | absent |
| `float lakeBankHeight` | absent |
| `float lakeDepth` | absent |
| `BeachType beachType` | absent (new import needed) |
| `BeachMaterial beachMaterial` | absent (new import needed) |

**Dead fields from Re — do NOT port (written but never read by evaluator or feature):**

| Field | Why dead |
|-------|----------|
| `float riverShoreDistance` | Written by Re's `RiverCarver`, never read by `BeachEvaluator` or any other consumer |
| `float riverBankWidth` | Written by Re's `RiverCarver`, never read anywhere |
| `float lakeAuthorityAlpha` | Only used as internal overlap-resolution bookkeeping in Re's `Lake.java`; ET has no standalone `Lake` objects |
| `float lakeDistance` | Same — only used by Re's `Lake.java` overlap resolution |

### Constructor changes

Add to ET's `Cell()` constructor:

```java
this.beachType = BeachType.NONE;
this.beachMaterial = BeachMaterial.NONE;
```

All float fields default to 0.0F which is appropriate for all new shore fields (0 = no shore).

**Total new fields: 16** (14 floats + 2 enums). This is practical — ET's Cell already carries ~20+ floats, and all 16 fields are functionally required because the writer (carver/heightmap) and reader (evaluator/feature) run in different pipeline stages.

### copyFrom() changes

Add all new fields to `copyFrom()` in the same pattern as existing fields.

### reset()

Uses `copyFrom(DEFAULTS)` — `DEFAULTS` is a plain `new Cell()` so it uses the constructor values. `BeachType.NONE` and `BeachMaterial.NONE` will be correctly reset.

### Re's `continentSizeModifier` absence

Re's `Cell` does not have `continentSizeModifier` or `continentDistance` or the terrain region center fields. ET does. These stay; nothing is removed.

## 7. Config/Settings Integration

ET's `WorldSettings.java` currently has 3 fields: `continent`, `controlPoints`, `properties`.

### Addition required

Add `beach` as an optional field with a default.

In `WorldSettings.CODEC`, change:

```java
Beach.CODEC.optionalFieldOf("beaches", Beach.DEFAULT).forGetter((o) -> o.beaches)
```

Define `Beach.DEFAULT` as a static constant with the `makeBeachSettings()` values from Re's `Presets.java`:

```java
public static final Beach DEFAULT = new Beach(
    new Variance(0.6F, 0.35F),
    new Ocean(0.68F, 4, 0.34F, 0, 6, new MaterialPalette(1.0F, 0.45F, 0.15F, 0.05F, 0.12F), OceanGeometry.DEFAULT.copy()),
    new River(0.34F, 2, 0.26F, 0, 5, new MaterialPalette(0.4F, 1.0F, 0.55F, 0.35F, 0.03F), 6, 24, 8, 1, 7, RiverGeometry.DEFAULT.copy()),
    new Lake(0.42F, 3, 0.24F, 0, 6, new MaterialPalette(0.75F, 0.85F, 0.35F, 0.45F, 0.05F), 10, 1, 8, LakeGeometry.DEFAULT.copy())
);
```

The `WorldSettings` constructor must be updated to accept a 4th `Beach` parameter. The existing 3-argument constructor in ET's `WorldSettings` also needs a compatibility overload or the codec must handle the optional defaulting.

### ET `ControlPoints` divergence

ET uses `islandInland`/`islandCoast` while Re uses `mushroomFieldsInland`/`mushroomFieldsCoast`. These are in `WorldSettings.ControlPoints`. They are separate config keys and no conflict exists — Re's control points are not being ported. ET's island-specific fields remain.

### Copy all inner classes from Re's `WorldSettings`

`Beach`, `Variance`, `MaterialPalette`, `Ocean`, `River` (beach version — distinct from `RiverSettings.River`), `Lake` (beach version — distinct from `RiverSettings.Lake`), `OceanGeometry`, `RiverGeometry`, `LakeGeometry`. These are all non-conflicting additions.

### `WorldSettings.copy()`

Must include `this.beaches.copy()`.

### Presets.java

Add `makeBeachSettings()` helper (copied verbatim from Re) and pass it in each `makeRTFDefault()`, `makeLegacyDefault()` etc. `WorldSettings` constructor call. The ET Presets already pass no beach arg — add it as the 4th arg after `controlPoints` and before `properties`.

## 8. Feature Registration

### `RTFFeatures.java`

Add one line:

```java
public static final Feature<BeachSurfaceFeature.Config> BEACH_SURFACE = register("beach_surface", new BeachSurfaceFeature(BeachSurfaceFeature.Config.CODEC));
```

Import: `import raccoonman.reterraforged.world.worldgen.feature.BeachSurfaceFeature;`

### `PresetConfiguredFeatures.java`

Add key:

```java
public static final ResourceKey<ConfiguredFeature<?, ?>> BEACH_SURFACE = createKey("beach_surface");
```

In `bootstrap()`, add registration (unconditional, like `SWAMP_SURFACE`):

```java
FeatureUtils.register(ctx, BEACH_SURFACE, RTFFeatures.BEACH_SURFACE, new BeachSurfaceFeature.Config(
    preset.world().beaches.ocean.surfaceDepth,
    preset.world().beaches.river.surfaceDepth,
    preset.world().beaches.lake.surfaceDepth
));
```

### `PresetPlacedFeatures.java`

Add key and registration:

```java
public static final ResourceKey<PlacedFeature> BEACH_SURFACE = createKey("beach_surface");
```

In `bootstrap()`:

```java
PlacementUtils.register(ctx, BEACH_SURFACE, features.getOrThrow(PresetConfiguredFeatures.BEACH_SURFACE));
```

Pattern is identical to how `SWAMP_SURFACE` is registered at line 97 of Re's file.

### `PresetBiomeModifierData.java`

ET already has `ADD_BEACH_SURFACE` declared at line 29 but never registers it (no call in `bootstrap()`). Add:

```java
ctx.register(ADD_BEACH_SURFACE, append(GenerationStep.Decoration.RAW_GENERATION, placedFeatures.getOrThrow(PresetPlacedFeatures.BEACH_SURFACE)));
```

Place this alongside `ADD_SWAMP_SURFACE`. Note Re uses `append(RAW_GENERATION, ...)` (no biome filter), meaning `BEACH_SURFACE` runs in every biome. This is correct — the feature checks `cell.beachType != NONE` internally before painting.

### 1.21.1 API differences vs 1.20.1

- Re uses `BootstapContext` (1.20.1 typo), ET uses `BootstrapContext` (1.21.1 corrected). All bootstrap method parameters in ET must use `BootstrapContext`.
- Re's `FeatureUtils.register` takes `BootstapContext`. ET takes `BootstrapContext`. Adjust the import.
- `net.minecraft.data.worldgen.placement.PlacementUtils.register()` signature is the same in both versions.
- No other MC API differences affect the feature registration chain.

## 9. `ITerrain.java` and `TerrainCategory.java` Changes

ET's `ITerrain` does not have `isRiverShore()`, `isLakeShore()`, or `isInlandShore()`. These are needed by `BeachEvaluator.getRiverSurfaceAlpha()` (line 143) and `getLakeSurfaceAlpha()` (line 165).

### In `ITerrain.java`

Add three default methods:

```java
default boolean isRiverShore() {
    return false;
}
default boolean isLakeShore() {
    return false;
}
default boolean isInlandShore() {
    return this.isRiverShore() || this.isLakeShore();
}
```

Also add to `ITerrain.Delegate`:

```java
default boolean isRiverShore() { return this.getDelegate().isRiverShore(); }
default boolean isLakeShore() { return this.getDelegate().isLakeShore(); }
default boolean isInlandShore() { return this.getDelegate().isInlandShore(); }
```

### In `TerrainCategory.java`

Add two new enum constants before `RIVER` (the ordinal order matters for `getDominant()`; insert after `BEACH`):

```java
RIVER_SHORE {
    @Override public boolean isRiverShore() { return true; }
    @Override public boolean isOverground() { return true; }
    @Override public boolean overridesCoast() { return true; }
},
LAKE_SHORE {
    @Override public boolean isLakeShore() { return true; }
    @Override public boolean isOverground() { return true; }
    @Override public boolean overridesCoast() { return true; }
},
```

### In `TerrainType.java`

Add the two corresponding constants after `BEACH`:

```java
public static final Terrain RIVER_SHORE = register("river_shore", TerrainCategory.RIVER_SHORE);
public static final Terrain LAKE_SHORE = register("lake_shore", TerrainCategory.LAKE_SHORE);
```

### Ordinal impact

`getDominant()` uses ordinal comparison. `RIVER_SHORE` and `LAKE_SHORE` would need ordinals between `BEACH` (4) and `RIVER` (5) in Re. In ET the static IDs are assigned sequentially at registration time so insertion position in `TerrainType.java` determines ID. Inserting them between `BEACH` and `RIVER` in `TerrainType.java` matches Re's ordering. The ordinal-based `getDominant()` in ET's `TerrainCategory` must be aware that `RIVER_SHORE` and `LAKE_SHORE` have lower ordinal than `RIVER`, `LAKE`, `WETLAND` — this is intentional; shore terrain should not dominate over actual water terrain types.

**However**, to avoid shifting existing terrain registry IDs (which could break serialized world data), consider appending `RIVER_SHORE` and `LAKE_SHORE` after `ISLAND_MOUNTAINS` at the end of the static block in `TerrainType` rather than inserting before `RIVER`.

## 10. `BeachDetect.java` Changes

ET's `BeachDetect` is a record with fields `(Levels levels, ControlPoints transition)` and performs simple gradient-based `BEACH` classification.

Replace it wholesale with the Re version, adapting the `make()` factory to use ET's `ControlPoints.make()` pattern:

```java
public static BeachDetect make(GeneratorContext ctx) {
    Levels levels = ctx.levels;
    ControlPoints controlPoints = ControlPoints.make(ctx.preset.world().controlPoints);
    return new BeachDetect(
        new BeachEvaluator(levels, controlPoints, ctx.preset.world().beaches),
        ThreadLocal.withInitial(SnapshotBuffers::new)
    );
}
```

The inner classes `SnapshotNeighborhood` and `SnapshotBuffers` are copied verbatim from Re.

The old behavior (simple COAST→BEACH if gradient < 0.275) is replaced by `BeachEvaluator.evaluate()` + `applyContinuity()`. The old simple classification is still covered by `BeachEvaluator.getOceanSurfaceAlpha()` which internally calls `isOceanBeach()` using the same `continentEdge < controlPoints.beach` and gradient checks.

**`Filterable.getBacking()`:** Re's `BeachDetect` calls `map.getBacking()` at line 24 to get the raw cell array. Verify this method exists on ET's `Filterable` interface. If absent, it must be added.

## 11. `WorldLookup.java` Changes

ET's `WorldLookup` has a simple `compute()` method that calls `heightmap.apply()` then checks `terrain == COAST && height > waterLevel && height <= beachLevel` to classify as `BEACH`.

Replace `compute()` with Re's full version including `SampledNeighborhood`, `SamplingCache`, `computeGradient()`, and `sampleCell()` overloads. The `BeachEvaluator` instance is constructed in the constructor: `new BeachEvaluator(context.levels, ControlPoints.make(context.preset.world().controlPoints), context.preset.world().beaches)`.

Remove the old fields `waterLevel` and `beachLevel` — they're no longer needed.

The `SamplingCache` inner class uses `Arrays.copyOf` — add `import java.util.Arrays` if not present.

The `applyCell` overloads remain unchanged — Re's `WorldLookup` has the same two `applyCell()` signatures.

## 12. `Heightmap.java` Changes

ET's `Heightmap` record signature ends with `Noise beachNoise`. Re's ends with `Noise beachSurfaceNoise, Noise beachMaterialNoise`.

Add `beachSurfaceNoise` and `beachMaterialNoise` to ET's record. Keep `beachNoise` — it is still used by `CellSampler.java` at line 150 (`cell.height + cell.beachNoise < levels.water(5)`). So the record needs all three noise fields.

In `applyTerrain()`, add the two new lines after the existing `beachNoise` computation:

```java
cell.beachSurfaceNoise = this.beachSurfaceNoise.compute(x, z, 0);
cell.beachMaterialNoise = this.beachMaterialNoise.compute(x, z, 0);
```

In the `create()` factory method at the bottom of Re's `Heightmap`, the two noises are seeded with `context.seed.next()`. In ET's factory (line 149–151), `beachNoise` is seeded. Add after line 150:

```java
Noise beachSurfaceNoise = Noises.perlin2(ctx.seed.next(), 48, 2);
beachSurfaceNoise = Noises.map(beachSurfaceNoise, 0.0F, 1.0F);
Noise beachMaterialNoise = Noises.perlin2(ctx.seed.next(), 96, 3);
beachMaterialNoise = Noises.map(beachMaterialNoise, 0.0F, 1.0F);
```

Then pass all five noise args to the `new Heightmap(...)` call.

## 13. Testing Strategy

### Unit-level

Create a test that instantiates `BeachEvaluator` with mock `Levels`, `ControlPoints`, and `Beach` settings. Feed it a `Cell` with: `terrain = TerrainType.COAST`, `continentEdge = 0.35F` (below coast control point), `height = levels.water(3)`, `gradient = 0.1F`. Assert `beachType == BeachType.OCEAN` and `beachSurfaceAlpha > 0`.

### Integration-level (in-game)

1. Generate a world with the RTF preset.
2. Fly to ocean coastline. Check F3 terrain tag shows `beach`. Place sand: should already be there from `BeachSurfaceFeature`.
3. Fly to a river shore in a flat region (waterTable plateau). Check `beachType == RIVER` by logging in `BeachSurfaceFeature.shouldPaint()`.
4. Check lake shorelines for `beachType == LAKE` material.
5. Verify existing island beaches (`ISLAND_BEACH` terrain) are unaffected — they do not go through `BeachEvaluator` and `ISLAND` is not a coast or overground shore candidate.

### Regression — existing ET behavior

- `CellSampler` references `cell.beachNoise` — verify it still compiles and runs (field retained, not renamed).
- `BeachDetect` previously set `terrain = BEACH` for gradient-filtered coast cells. The new evaluator does the same through `getOceanSurfaceAlpha()` → `applyResolvedShore()` → `cell.terrain = TerrainType.BEACH`. Verify the `BEACH` assignment path still fires.
- `WorldLookup.compute()` previously had a fallback `if terrain == COAST && height <= beachLevel → BEACH`. The new path handles this inside `BeachEvaluator.evaluate()`. The old fallback line is removed.

## 14. Risk Register

| Risk | Description | Mitigation |
|------|-------------|------------|
| **`Filterable.getBacking()` missing in ET** | Re's `BeachDetect` calls `map.getBacking()` to snapshot cell arrays for the continuity pass. ET's `Filterable` may not expose this method. | Check `Filterable.java` in ET. If missing, add `Cell[] getBacking();` to the interface and implement it in the concrete filterable tile class. |
| **Terrain registry ID shift** | Inserting `RIVER_SHORE` and `LAKE_SHORE` before `RIVER` in `TerrainType` static init shifts RIVER's registry ID. Could break serialized world data. | Append `RIVER_SHORE` and `LAKE_SHORE` after `ISLAND_MOUNTAINS` (at the end of the static block) rather than before `RIVER`, so existing IDs are not disturbed. |
| **`ControlPoints` class naming collision** | Re uses `heightmap.ControlPoints` as a record with `beach()`, `coast()`, `coastMarker()` accessors. ET has the same record. | These are record accessor names; they match. `controlPoints.beach()`, `controlPoints.coast()`, `controlPoints.coastMarker()` all work with ET's existing record. No collision. |
| **ET `WorldSettings.ControlPoints` uses different island fields than Re** | ET uses `islandInland`/`islandCoast` while Re uses `mushroomFieldsInland`/`mushroomFieldsCoast`. | These are separate config keys. ET's island fields remain. New beach config added with `optionalFieldOf` cannot break existing presets. |
| **`BeachEvaluator.isOceanEnvelope()` calls `cell.terrain.isInlandShore()`** | If `isInlandShore()` is not added to ET's `ITerrain`, the evaluator call fails at compile time. | Caught at build time, not runtime. Add the methods to `ITerrain`. |
| **Shore alpha fields never written for non-uplift continent types** | ET supports `MULTI_IMPROVED`, `MULTI` continent types that use the classic `RiverCarver` (not `UpliftRiverCarver`). The shore metadata adapter is only in `UpliftRiverCarver`. | Accept as phased rollout — river/lake shores only work with UPLIFT continent type in V1. Document this. |
| **`lakeShoreAlpha` emission overlap** | A cell may be processed by multiple river carvers. `lakeShoreAlpha` must use `Math.max(cell.lakeShoreAlpha, newValue)` not assignment. | Same `Math.max` pattern used for `riverShoreAlpha` in Re's `RiverCarver`. |
| **`BeachSurfaceFeature` uses `generatorContext.cache` which may be null** | The feature handles null cache via a fallback `generatorContext.lookup.applyCell()`. | After porting `WorldLookup`, the fallback path goes through the new `compute()` method which does call the evaluator. Safe. |

## 15. Critical Files Summary

Files that must be modified or created, in dependency order:

### New files (copy from Re)

1. `common/.../cell/beach/BeachType.java`
2. `common/.../cell/beach/BeachMaterial.java`
3. `common/.../cell/beach/ShoreGeometry.java`
4. `common/.../cell/beach/BeachEvaluator.java` (with `resolveLocalBase` adaptation)
5. `common/.../feature/BeachSurfaceFeature.java`

### New files (written fresh)

6. Shore metadata emission logic in `UpliftRiverCarver.java` (adapter method)

### Modified files

7. `common/.../cell/Cell.java` — add 20 beach fields
8. `common/.../cell/terrain/ITerrain.java` — add `isRiverShore()`, `isLakeShore()`, `isInlandShore()`
9. `common/.../cell/terrain/TerrainCategory.java` — add `RIVER_SHORE`, `LAKE_SHORE`
10. `common/.../cell/terrain/TerrainType.java` — register the two new types
11. `common/.../cell/rivermap/river/UpliftRiverCarver.java` — add `emitShoreMetadata()` call
12. `common/.../densityfunction/tile/filter/BeachDetect.java` — replace with evaluator-based version
13. `common/.../cell/heightmap/WorldLookup.java` — replace `compute()` with evaluator version
14. `common/.../cell/heightmap/Heightmap.java` — add two noise fields
15. `common/.../data/worldgen/preset/settings/WorldSettings.java` — add `Beach` and all inner classes
16. `common/.../data/worldgen/preset/settings/Presets.java` — add `makeBeachSettings()`
17. `common/.../RTFFeatures.java` — register `BEACH_SURFACE`
18. `common/.../data/worldgen/preset/PresetConfiguredFeatures.java` — register configured feature
19. `common/.../data/worldgen/preset/PresetPlacedFeatures.java` — register placed feature
20. `common/.../data/worldgen/preset/PresetBiomeModifierData.java` — register biome modifier
