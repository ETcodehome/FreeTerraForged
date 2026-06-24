# Beach/Shoreline Port — Status & Future Work

## What Was Done

### New Files Created
- `cell/beach/BeachType.java` — Enum: NONE, OCEAN, RIVER, LAKE
- `cell/beach/BeachMaterial.java` — Enum: NONE, SAND, GRAVEL, STONE, MUD, RED_SAND
- `cell/beach/ShoreGeometry.java` — Ocean normalized distance and bias math
- `cell/beach/BeachEvaluator.java` — Core evaluator: ocean/river/lake shore detection, material selection driven by climate + geology, continuity promotion for coastal consistency
- `cell/heightmap/ControlPoints.java` — Adapter record bridging WorldSettings.ControlPoints to the evaluator's coastMarker-based geometry
- `feature/BeachSurfaceFeature.java` — World gen feature that paints beach blocks on the heightmap surface based on Cell beach state

### Modified Files
- `Cell.java` — 16 new fields (riverWidth, riverDepth, riverBankHeight, riverBankAlpha, riverShoreAlpha, lakeShoreAlpha, lakeBankAlpha, lakeBankHeight, lakeDepth, beachSurfaceNoise, beachMaterialNoise, beachSurfaceAlpha, oceanShoreAlpha, oceanShoreDistance, beachType, beachMaterial). Reset and copyFrom updated.
- `ITerrain.java` — Added isRiverShore(), isLakeShore(), isInlandShore() with Delegate forwarding
- `TerrainCategory.java` — Added RIVER_SHORE, LAKE_SHORE enums with overridesCoast
- `TerrainType.java` — Registered RIVER_SHORE, LAKE_SHORE after MUSHROOM_FIELDS (preserves existing IDs)
- `WorldSettings.java` — Added Beach config hierarchy (Variance, MaterialPalette, OceanGeometry, Ocean, River, Lake, Beach) with full Codec support. 4-arg constructor, optional beaches field.
- `Heightmap.java` — Added beachSurfaceNoise, beachMaterialNoise record fields and computation in applyTerrain(). Fixed ControlPoints import collision with fully qualified WorldSettings.ControlPoints.
- `WorldLookup.java` — Added BeachEvaluator for standalone cell lookups
- `BeachDetect.java` — Replaced gradient-based classifier with evaluator-based two-pass filter (evaluate + continuity)
- `UpliftRiverCarver.java` — PR #77 applied. Added emitShoreFields() to populate river shore Cell fields from zone geometry.
- `Wetland.java` — PR #77 applied. Populates lake shore Cell fields (lakeBankAlpha, lakeShoreAlpha, lakeBankHeight, lakeDepth). Fixed riverWaterLevel to store absolute height.
- `RTFFeatures.java` — Registered BEACH_SURFACE feature
- `PresetConfiguredFeatures.java` — Registered BEACH_SURFACE configured feature with depth config from preset
- `PresetPlacedFeatures.java` — Registered BEACH_SURFACE placed feature
- `PresetBiomeModifierData.java` — Registered ADD_BEACH_SURFACE biome modifier (append to RAW_GENERATION)

### Critical Fixes Applied (from QA)
1. **River/lake shore fields wired** — UpliftRiverCarver.emitShoreFields() now populates riverWidth, riverDepth, riverBankHeight, riverBankAlpha, riverShoreAlpha. Wetland.apply() now populates lakeBankAlpha, lakeShoreAlpha, lakeBankHeight, lakeDepth.
2. **resolveLocalWaterBase fixed** — Both UpliftRiverCarver and Wetland now store riverWaterLevel as absolute normalized height. BeachEvaluator uses it directly without adding levels.water.
3. **Continuity pass fixed** — SnapshotNeighborhood.getCell() returns a present sentinel for in-bounds positions so collectSupport() can accumulate neighbor data.

## Known Limitations

### River/Lake Shore Field Granularity
The emitted river shore fields derive from UpliftRiverCarver's zone radii, which are coarser than Re's original per-cell river carver fields. River shore detection works but the alpha gradients may be less smooth than Re's original. This is acceptable because ET's zone-based carver produces different valley shapes anyway.

### Wetland Lake Shore Detection
Wetland.apply() emits lake fields using distance-to-center alpha, which provides a reasonable proxy for bank proximity. However, ET's wetlands are not traditional "lakes" — they're swamp-like lowlands with mounds. The lake shore detection may fire in places where the visual terrain doesn't look like a typical lake shore. Tuning the WorldSettings.Lake coverage and slope thresholds can mitigate this.

## Future Work: Modded Beach Material Support

The current `BeachMaterial` enum is hardcoded to 5 types: SAND, GRAVEL, STONE, MUD, RED_SAND. To support modded blocks (e.g. Create's crushed ores, Biomes O' Plenty's white/black sand, Terrestria's volcanic sand), the following changes are needed:

### 1. Registry-Based Material System
Replace the `BeachMaterial` enum with a registry-backed identifier system:
- Create a `BeachMaterialRegistry` (or use RTF's existing `RegistryUtil` pattern) that maps string IDs to `BeachMaterialEntry` records containing surface/filler BlockState pairs
- Default entries for the 5 vanilla materials registered at startup
- Mods register additional materials via the registry during initialization

### 2. MaterialPalette Extension
`WorldSettings.MaterialPalette` currently has 5 fixed float fields. Replace with:
- A `Map<ResourceLocation, Float>` weight map, serialized via Codec
- Default palette populates vanilla entries; modded entries start at weight 0.0 unless overridden in preset JSON
- The `selectMaterial` method in BeachEvaluator iterates the weight map instead of using hardcoded cumulative thresholds

### 3. BeachSurfaceFeature Block Resolution
`BeachSurfaceFeature.MaterialSet` currently maps enum constants to hardcoded `Blocks.X.defaultBlockState()`. Replace with:
- Lookup from the material registry at feature initialization time
- Fallback to vanilla blocks if a registered material's block isn't loaded (mod removed)

### 4. Data-Driven Material Rules
For full flexibility, support data-driven rules that assign materials based on conditions:
- Biome tags (e.g. volcanic biomes get basalt beaches)
- Temperature/moisture ranges
- Altitude bands
- Custom predicates from mod APIs

This would be implemented as a `BeachMaterialRule` codec-serializable record with a list of condition-material pairs evaluated in order, with the weighted palette as fallback.

### 5. TerraBlender Compatibility Note
RTF already integrates with TerraBlender via `MixinParameterList`. The beach system runs independently of biome selection — it operates on Cell data during RAW_GENERATION. Modded biomes added via TerraBlender will automatically receive beach treatment based on their Cell's terrain classification and climate values. No additional TerraBlender integration is needed for the beach system itself, only for the material registry if mods want biome-specific beach materials.
