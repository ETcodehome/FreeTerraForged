# RTF 1.21.1 Branch Split Plan

Date: 2026-06-23

## Objective

Keep the current `1.21.1` branch as the integrated working branch with everything it already has. Create separate topic branches from the current upstream `upstream/1.21.1` tip so each topic can become a distinct PR. Each split branch should carry only the commits or manual changes needed for its topic, build clean QA jars, and be independently QA-able.

Current upstream base:

```text
f870c45 upstream/1.21.1 Merge pull request #88 from ETcodehome/addCommunityPreset1
```

Current integrated branch:

```text
f945636 1.21.1 Merge remote-tracking branch 'upstream/1.21.1' into 1.21.1
```

## Target Branches

```text
fix/tall-world-mountain-scaling
fix/tall-world-ocean-scaling
feat/configurable-strata
feat/configurable-shorelines
```

Classification notes:

- `fix/tall-world-mountain-scaling` is a fix branch even if it includes the config/plumbing needed to make tall-world behavior usable.
- `fix/tall-world-ocean-scaling` is a fix branch even if it includes user-facing ocean-depth config/UI, because the behavior being corrected is ocean floors respecting configured depth/world depth in tall worlds.
- `feat/configurable-strata` is feature work. Deepslate is not separately configurable; its band is dynamic from `worldDepth` plus strata rock layer counts, so it should travel with this branch without being named separately.
- `feat/configurable-shorelines` is feature work: shore classification, material palettes, continuity, and raw-generation beach surface painting.

## Key Existing Commits

```text
33687b8 Port beach/shoreline subsystem from ReTerraForged (1.20.1) to ET fork (1.21.1)
b902464 Fix three critical issues found in QA review
317396f Fix uplift river shore metadata units
ebb7f1d Implement sampled WorldLookup fallback
fe1ef47 Add configurable ocean depth and strata
70b53b5 Fix tall-world mountain scaling
b612264 Fix TerraBlender deepslate surface rules
ba51215 Allow deep oceans below zero in tall worlds
```

Important: `fe1ef47` combines two topics: configurable ocean depth and configurable strata. It should not be blindly cherry-picked into both `fix/tall-world-ocean-scaling` and `feat/configurable-strata`; split it manually by file/hunk.

## Recommended Work Order

1. `feat/configurable-shorelines`
2. `fix/tall-world-ocean-scaling`
3. `feat/configurable-strata`
4. `fix/tall-world-mountain-scaling`

Rationale:

- Shorelines are the broadest foundational feature and introduce `WorldSettings.Beach`.
- Deeper oceans modify `WorldSettings.Ocean`, `Levels`, ocean populators, and world settings UI.
- Strata shares translation/UI files with deeper oceans and shares surface-rule files with shoreline-generated beach surfaces, so it benefits from knowing which branch owns which hunks.
- Tall-world mountain scaling touches density projection and many common world-height helpers. It should be prepared after the ocean/depth split is clear, because `ba51215` depends on the tall-world projection code from this area.

## Branch Plans

### Branch: `feat/configurable-shorelines`

Base:

```bash
git switch --detach upstream/1.21.1
git switch -c feat/configurable-shorelines
```

Likely source commits:

```text
33687b8
b902464
317396f
ebb7f1d
```

Expected contents:

- `cell/beach/*`
- `BeachSurfaceFeature`
- beach surface feature registration
- `WorldSettings.Beach` hierarchy
- `Cell` shore metadata fields
- river/lake shore field emission
- `BeachDetect` evaluator pass
- `WorldLookup` sampled fallback/evaluator support

Expected conflicts/manual checks:

- `WorldSettings.java`: upstream now includes optional codec changes for island control points. Keep upstream optional defaults and add optional `beaches`.
- `Presets.java`: upstream includes the new Community preset. Shoreline branch should not remove or rewrite it.
- No translation conflict should be needed unless later UI/config is included by accident.

Build gate:

```bash
sh ./gradlew clean build
```

QA focus:

- ocean beach material painting
- river shore and lake/wetland shore material painting
- continuity around broken/coarse ocean shoreline cells
- fallback `WorldLookup` behavior when tile cache is absent

### Branch: `fix/tall-world-ocean-scaling`

Base:

```bash
git switch --detach upstream/1.21.1
git switch -c fix/tall-world-ocean-scaling
```

Do not cherry-pick all of `fe1ef47`. Manually extract the ocean/depth hunks only.

Source hunks from:

```text
fe1ef47 Add configurable ocean depth and strata
ba51215 Allow deep oceans below zero in tall worlds
```

Expected contents from `fe1ef47`:

- `WorldSettings.Ocean` fields:
  - `shallowOceanDepth`
  - `deepOceanMinDepth`
  - `deepOceanMaxDepth`
  - `oceanDepthNoiseScale`
- `WorldSettings.Beach.DEFAULT` ocean constructor/default updates if this branch is based after shoreline.
- `Levels` support for `worldDepth` and `min`.
- `GeneratorContext` constructing `Levels(terrain/world height, worldDepth, seaLevel)` as needed.
- `Heightmap.make()` passing ocean settings into `Populators.makeDeepOcean` / `makeShallowOcean`.
- `Populators.makeDeepOcean` and `makeShallowOcean` using configured ocean depths.
- `OceanPopulator` allowing negative ocean floors down to a supplied minimum.
- `WorldSettingsPage` ocean depth sliders.
- `RTFTranslationKeys`, `RTFLanguageProvider`, and `en_us.json` ocean labels/tooltips.

Expected contents from `ba51215`:

- `PresetNoiseRouterData.tallTerrainOffset(...)` lower clamp uses configured world bottom:

```java
double minProjectedHeight = -properties.worldDepth / (double) terrainModelHeight;
```

Dependency warning:

- `ba51215` depends on the tall-world density projection code introduced by the tall-world branch. If `fix/tall-world-ocean-scaling` is meant to build independently from upstream, either:
  - include the minimal projection code needed by `ba51215`, or
  - leave the below-zero tall-world projection fix for a separate `fix/oceans-respect-world-depth` branch based on `fix/tall-world-mountain-scaling`.

Expected conflicts/manual checks:

- `WorldSettings.java`: overlaps with shoreline branch's `WorldSettings.Beach`.
- `RTFTranslationKeys.java`: upstream added `GUI_COMMUNITY1_PRESET_NAME` and renamed `GUI_LABEL_TRANSITIONS`. Keep both upstream and ocean keys.
- `WorldSettingsPage.java`: keep upstream behavior and add only ocean-depth UI.
- Avoid bringing in `MiscellaneousSettings.StrataSettings` or `StrataRule` hunks from `fe1ef47`.

Build gate:

```bash
sh ./gradlew clean build
```

QA focus:

- shallow ocean slider changes actual shallow floor Y
- deep min/max depth sliders change deep ocean floor Y
- configured ocean floors can go below `Y=0` when `worldDepth` permits
- existing presets without new ocean depth fields load

### Branch: `feat/configurable-strata`

Base:

```bash
git switch --detach upstream/1.21.1
git switch -c feat/configurable-strata
```

Do not cherry-pick all of `fe1ef47`. Manually extract strata hunks only, then apply deepslate follow-up.

Source hunks from:

```text
fe1ef47 Add configurable ocean depth and strata
b612264 Fix TerraBlender deepslate surface rules
```

Expected contents from `fe1ef47`:

- `MiscellaneousSettings.StrataSettings`
- `StrataRule` layer thickness/material weighting changes
- `PresetSurfaceRuleData` strata setting propagation
- `MiscellaneousPage` strata sliders
- `RTFTranslationKeys`, `RTFLanguageProvider`, and `en_us.json` strata labels/tooltips

Expected contents from `b612264`:

- `PresetSurfaceRuleData` full overworld surface rule replacement with dynamic deepslate band.
- `PresetSurfaceRuleData.computeDeepslateBand(...)`.
- TerraBlender default surface rule registration in `MixinParameterList`.
- Guarded surface debug probe in `MixinSurfaceSystem`.
- `reterraforged.accesswidener` update for debug probe access.
- `DynamicOverworldSurfaceRule` and `RTFSurfaceRules` only if still needed by the selected implementation. Current docs say they are retained but unused; prefer omitting them if the branch builds and behavior stays correct without dead code.

Deepslate naming note:

- There are no separate deepslate sliders or explicit deepslate preset fields.
- Dynamic deepslate uses `worldDepth` plus `rockMinLayers` / `rockMaxLayers` to compute the band.
- Therefore this branch should be named around configurable strata, not configurable deepslate.

Expected conflicts/manual checks:

- `PresetSurfaceRuleData.java`: this will be a large manual reconciliation because shoreline, beach surface, strata, and deepslate can all touch generated features/surface rules.
- `RTFTranslationKeys.java`: keep upstream community preset key and transitions key; add strata keys.
- `RTFLanguageProvider.java`: merged branch currently lacks generated `GUI_COMMUNITY1_PRESET_NAME` in the provider even though `en_us.json` has it. Add it if datagen consistency matters on this branch.
- Do not bring ocean-depth fields/UI from `fe1ef47`.

Build gate:

```bash
sh ./gradlew clean build
```

QA focus:

- fewer/thicker strata bands based on `rockMinLayers` / `rockMaxLayers`
- material weighting makes stone more common without removing granite/andesite/diorite
- deepslate transition follows configured `worldDepth`
- TerraBlender active runtime no longer forces vanilla deepslate at `Y=0`
- vanilla-depth worlds still behave like vanilla deepslate banding

### Branch: `fix/tall-world-mountain-scaling`

Base:

```bash
git switch --detach upstream/1.21.1
git switch -c fix/tall-world-mountain-scaling
```

Source commit:

```text
70b53b5 Fix tall-world mountain scaling
```

Do not blindly cherry-pick the docs-only `agent-ref` files from the historical commit; `agent-ref` is ignored now and should remain local.

Expected contents:

- explicit `terrainModelHeight()` split from configured `worldHeight`
- `terrainScaler()` retained as deprecated compatibility alias
- density projection changes in `PresetNoiseRouterData`
- `CellSampler.HEIGHT` declared range fix
- tall-world dynamic max height handling in `MixinNoiseBasedChunkGenerator`
- model-height usage in `GeneratorContext`, previews, terrain-type noise, surface noise
- mountain horizontal scaling based on `tallTerrainHorizontalScale()`
- `TerrainProvider` / `Populators` mountain scaling fixes
- optional guarded debug helper `WorldHeightDebug` and safe debug lookup in `MixinRandomState`

Expected conflicts/manual checks:

- `WorldSettings.java`: upstream optional codec changes plus branch terrain model helpers must coexist.
- `Heightmap.java`, `Populators.java`, and `TerrainProvider.java`: ensure mountain footprint scaling applies to all mountain layers, not only mountain chains.
- `PresetNoiseRouterData.java`: if this branch also needs below-zero ocean floors, include `ba51215`; otherwise leave that for an ocean follow-up branch.
- `Levels.java`: if started from plain upstream, decide whether `worldDepth/min` belongs here or only in tall-world ocean scaling. Tall-world projection itself may need `worldDepth` only for below-zero ocean behavior.

Build gate:

```bash
sh ./gradlew clean build
```

QA focus:

- 1024-height preset no longer caps mountains around the old 256-scale band
- mountains are not blade-like vertical walls
- ordinary hills/plains are not inflated to extreme heights
- same seed preserves broad continent/ocean/biome layout compared with pre-fix behavior

## Cross-Branch Risks

1. `fe1ef47` must be split manually.
   - Ocean and strata share translation files, GUI provider, `WorldSettings`, and generated JSON.
   - Use `git show fe1ef47 -- <file>` and apply selected hunks with `git apply --3way` or manual patches.

2. `WorldSettings.java` is the highest-conflict file.
   - Upstream optional island control point codecs must be kept.
   - Shorelines add `Beach`.
   - Deeper oceans add fields under `Ocean`.
   - Tall-world scaling adds `terrainModelHeight()` helpers.

3. `PresetSurfaceRuleData.java` is the highest-risk behavior file.
   - Strata/deepslate owns most surface rule changes.
   - Shoreline branch may need beach configured feature placement but should not own deepslate behavior.

4. Translation files will conflict repeatedly.
   - Always keep:
     - `GUI_COMMUNITY1_PRESET_NAME`
     - `GUI_LABEL_TRANSITIONS`
     - ocean depth keys when on ocean branch
     - strata keys when on strata branch
     - `GUI_LABEL_OCEAN` when ocean UI is present

5. Build success is necessary but not enough.
   - Most failures here are worldgen behavior failures. Each branch needs targeted QA in-game after the clean build.

## Execution Checklist Per Branch

For each branch:

1. Start clean.

```bash
git status --short --branch
```

2. Create branch from upstream.

```bash
git switch --detach upstream/1.21.1
git switch -c <branch-name>
```

3. Apply commits/hunks.

4. Resolve conflicts with `rg -n '<<<<<<<|=======|>>>>>>>' .`.

5. Build.

```bash
sh ./gradlew clean build
```

6. Record jar paths/timestamps.

```bash
find fabric/build/libs neoforge/build/libs -maxdepth 1 -type f -name '*.jar' -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' | sort
```

7. Commit with a focused message.

8. User QA.

## Open Decisions Before Execution

1. Use the user's proposed branch names exactly, or adopt the recommended corrected names.
2. Whether `fix/tall-world-ocean-scaling` should include the tall-world below-zero ocean fix (`ba51215`) directly, or whether that should become a dependent `fix/oceans-respect-world-depth` branch.
3. Whether to keep debug instrumentation (`WorldHeightDebug`, `MixinSurfaceSystem` probe) in feature/fix branches or strip it before PR-ready branches.
4. Whether to add `GUI_COMMUNITY1_PRESET_NAME` to `RTFLanguageProvider` during branch work for datagen consistency.
