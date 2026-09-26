# FreeTerraForged + C2ME OpenCL GPU worldgen

Paths mirror the repo, so this can be extracted over the project root.

Target: FreeTerraForged 1.0.0 (MC 1.21.1, Architectury, Mojmap), package `etcodehome.freeterraforged`.
Against: c2me 0.4.0-alpha.0.29 with `c2me-opts-dfc` and `c2me-opts-accel-opencl`.

## Enabling

GPU terrain is off by default. JVM arg:

    -Dfreeterraforged.gpuTerrain=true

Also requires `allowIncompatibilityFallback = true` under `[openclAccel]` in `config/c2me.toml`.

Debug switches:

    -Dfreeterraforged.gpuConstantCells=true      every cell read returns 0.5 (bisect plumbing vs data)
    -Dfreeterraforged.gpuMissingCellValue=0.0    fallback when rw_data is absent

## NEW - C2ME density function integration (JVM path, works standalone)

    fabric/.../compat/c2me/C2MECompat.java
    fabric/.../compat/c2me/C2MEMixinPlugin.java
    fabric/.../compat/c2me/mixin/MixinOpenCLCGen.java
    fabric/.../compat/c2me/dfc/FTFCellNode.java
    fabric/.../compat/c2me/dfc/FTFCellFrontend.java
    fabric/.../compat/c2me/dfc/FTFCellBytecodeEmitter.java
    fabric/.../compat/c2me/dfc/FTFCellDotEmitter.java
    fabric/.../compat/c2me/dfc/FTFClampNode.java
    fabric/.../compat/c2me/dfc/FTFClampFrontend.java
    fabric/.../compat/c2me/dfc/FTFClampBytecodeEmitter.java
    fabric/.../compat/c2me/dfc/FTFClampDotEmitter.java
    fabric/.../compat/c2me/dfc/FTFCellBindings.java        registration entry point
    common/.../world/worldgen/densityfunction/FTFDensityFunctionScanner.java
    fabric/src/main/resources/freeterraforged-c2me.mixins.json

Binds FTF's `CellSampler` and `ClampToNearestUnit` as first-class c2me AST nodes so the
density function compiler stops falling back to its generic delegate path.

## NEW - GPU terrain (OpenCL)

    fabric/.../compat/c2me/dfc/FTFCellOpenCLEmitter.java
    fabric/.../compat/c2me/dfc/FTFClampOpenCLEmitter.java
    fabric/.../compat/c2me/dfc/FTFCellData.java            blob layout and builder
    fabric/.../compat/c2me/dfc/FTFGpuTerrain.java          per-area blob cache and upload
    fabric/.../compat/c2me/mixin/MixinCLDataUtil.java      hooks BOTH serializers
    fabric/.../compat/c2me/mixin/MixinGeneratedCLSource.java

Erosion stays on CPU. Only its per-column output is uploaded, as a `CLDataUtil$ConstantBlob`
carrying an 8-int header (startX, startZ, sizeX, sizeZ, stride) then 12 CellSampler.Field
floats per column. The emitted kernel reads it via `df_data_offset_global(ctx.rw_data, idx)`.

## NEW - status readout

    common/.../world/worldgen/FTFAcceleration.java
    common/.../mixin/MixinPauseScreen.java                 CPU/GPU line in the pause menu
    common/.../mixin/MixinAccelerationLifecycle.java       resets on server stop

## CHANGED

    fabric/build.gradle                     c2meStubs source set, asm-commons compileOnly
    fabric/src/main/resources/fabric.mod.json   registers freeterraforged-c2me.mixins.json
    fabric/.../fabric/FTFFabric.java        calls FTFCellBindings.register()
    common/.../mixin/MixinRandomState.java  publishes the GeneratorContext statically
    common/src/main/resources/freeterraforged-common.mixins.json
    common/.../world/worldgen/util/WorldGenTracker.java      adds CPU vs wall time
    common/.../mixin/MixinChunkGeneratorTiming.java          feeds the CPU time counter

The profiler previously reported wall time while labelling it CPU time. It now reports both,
so `Thread Blocked Per Chunk` shows how much worker time is spent waiting rather than computing.
Note it instruments only c2me's worker threads; FTF's own WORLD_GEN pool is not counted.

## Compile-only stubs

    fabric/src/c2meStubs/java/com/ishland/...

Stand-ins so the mixins and emitters compile without c2me on the classpath. Wired as
`compileOnly` and MUST NOT ship in the built jar. Verify after building:

    unzip -l <jar> | grep com/ishland     # must be empty

## Known limitations

- Out-of-range cell reads are clamped to the nearest edge column. This removed terrain
  spires but means GPU output may not exactly equal CPU output beyond the 8-block border.
  Not yet verified. If they diverge, raise AREA_BORDER_BLOCKS in FTFGpuTerrain.
- `rw_data` is NULL on aquifer paths and in the biome multinoise kernel, so cells read the
  fallback there. Affects aquifers and biome placement, not terrain height.
- Tile priming is serial before each dispatch. Observed 3088ms first area, amortising to 43-545ms.
- The node does not deduplicate, so it emits thousands of times into the kernel source.
