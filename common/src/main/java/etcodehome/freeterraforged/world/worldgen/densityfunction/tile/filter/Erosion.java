package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import java.util.function.IntFunction;

import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ErosionFilterSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Size;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.Seed;

/**
 * Single-pass procedural erosion filter based on Runevision's Phacelle Noise algorithm.
 * Replaces iterative hydraulic droplet simulations with direct analytical evaluation.
 *
 * Intended to run AFTER {@link CliffRelaxation}. Erosion has no hard dependency on it: it only reads cell
 * heights, and records its own {@code Cell.preErosionHeight} snapshot of the terrain it received.
 *
 * The erosion pass is purely ADDITIVE: each octave contributes (noise * mask * strength), so a zero
 * mask means zero change. The current cell height is used only to modulate masks, never as a height
 * offset, so there is no contrast-stretching around a midpoint.
 *
 * Flat terrain is protected by a slope gate: cells whose RAW slope is below FLAT_SLOPE_START are not carved
 * and skip all noise sampling, and the final delta is capped by what the local slope can physically support.
 * There is no mask floor, so a low slope really does mean zero erosion.
 *
 * Slopes are a minmod of the one-sided differences rather than a central difference, so the flat rim of a
 * cliff top does not inherit half the cliff's slope (which used to put full-amplitude noise on the rim).
 *
 * SMOOTHED AMPLITUDE SLOPE: minmod reads exactly 0 on a crest, rim or valley floor, but the cells right next
 * to it read the full flank slope. Used directly, the carve amplitude jumps from closed to fully open in a
 * single cell, so the delta field jumps by tens of blocks between neighbors. That jump is the spike above a
 * ridgeline / pit below a base. To make amplitude continuous, the carve gate and depth cap read a smoothed
 * copy of the slope: a small window-MIN (spreads the crest's "zero" outward) followed by a box blur (turns
 * the edge of that quiet zone into a ramp).
 *
 * Division of labor between the two slope fields:
 *  - RAW slope: decides whether a cell runs the octave loop, and drives the noise SHAPE (masks, gully
 *    direction).
 *  - SMOOTHED slope: drives only the carve AMPLITUDE (gate and depth cap). A cell can run the octave loop
 *    (and have its sediment updated) while receiving no carve because the smoothed gate is closed.
 *
 * HEADROOM LIMIT: the noise is signed, so on a narrow spire the cells a few blocks from the peak (where the
 * slope reads steep and the gate is open) can be raised ABOVE the original crest, making pillars taller and
 * thinner. Positive carve is therefore softly limited by the headroom above the highest ORIGINAL terrain in
 * a small window (ENVELOPE_RADIUS): c -> c * h / (c + h), where h = (envMax - height) in blocks plus
 * ENVELOPE_MARGIN_BLOCKS. Small raises pass almost unchanged and large ones saturate smoothly at h, so there
 * are no flat shelves. At a peak h is just the margin; on a slope it is roughly slope * radius. Negative
 * carve (gullies) is not limited. The envelope is built from the pre-erosion heights, which this filter never
 * modifies during the pass, so results do not depend on cell iteration order.
 *
 * SEDIMENT COVERAGE: every cell that is not erosion-masked and not non-finite gets a sediment update.
 *  - Raw-flat cells take a closed-form shortcut (no noise sampling): with no carve mask the octave loop
 *    would reduce to the height fade, so that value is written directly.
 *  - The ridge mask is capped at RIDGE_MASK_MAX so it can never saturate to exactly 1.0, which would make
 *    the update add exactly 0 on steep cells and gully walls.
 *  - Cells with a zero modifier factor (below ground, river centers) are still skipped, as in the original.
 *
 * The depth cap uses a SATURATED slope (MAX_SLOPE_FOR_CAP). Without saturation the cap grows without
 * bound on cliffs (where slopes are 10-50 blocks/block), which lets full-amplitude noise through exactly
 * where it produces spikes. The cap is also applied AFTER the sediment multiplier, which can be up to 2x.
 *
 * Units: the erosion math runs entirely in BLOCKS.
 *  - Wavelength: s.scale * BASE_WAVELENGTH_BLOCKS blocks (octave n: divided by lacunarity^n).
 *  - Depth: strength * wavelength, in blocks, so depth-to-wavelength ratio is fixed.
 *  - Slope: blocks of rise per block of run (normalized slope * worldHeight).
 * The accumulated delta is converted to normalized height (divided by worldHeight) only when written,
 * which makes the result independent of world height.
 *
 * Octave count and gain come straight from the preset with no limiting, so every slider is live.
 */
public class Erosion implements Filter {

    /** Gully wavelength in blocks when s.scale == 1. Octave n has wavelength ~ this / lacunarity^n. */
    private static final float BASE_WAVELENGTH_BLOCKS = 96.0f;

    /**
     * Slope (blocks/block) below which erosion is fully off, and above which the gate is fully open.
     * Smoothstep in between. START should sit just above the slope your flat and rolling terrain produces.
     * The raw slope is tested against START only (to decide whether to run the octave loop); the smoothed
     * slope uses the full START..FULL smoothstep to scale the carve.
     */
    private static final float FLAT_SLOPE_START = 0.15f;
    private static final float FLAT_SLOPE_FULL  = 0.60f;

    /**
     * Radius (cells) of both the window-min and the box blur applied to the amplitude slope. Carve amplitude
     * fades out over roughly 2 * this many cells around any crest, rim or valley floor.
     * Raise it if spikes still show next to sharp ridgelines; lower it if erosion detail is dying out on
     * terrain that oscillates quickly. 0 disables the smoothing (raw minmod slope, as before).
     */
    private static final int GATE_SMOOTH_RADIUS = 2;

    /** Radius (cells) of the window used to find the highest original terrain near a cell. */
    private static final int ENVELOPE_RADIUS = 3;

    /**
     * Extra blocks a cell may rise even when it is already the local maximum. Lower = flatter crests,
     * higher = more ridge relief survives. Raise ENVELOPE_RADIUS or this value if ridges look too tame.
     * Must be > 0 (it is the floor of the headroom, which is used as a divisor).
     */
    private static final float ENVELOPE_MARGIN_BLOCKS = 0.5f;

    /**
     * Max |delta| as a multiple of (slope * base wavelength): a gully can't be deeper than the slope
     * supports. Lower this if gentle slopes still ripple too much.
     */
    private static final float MAX_DEPTH_PER_SLOPE = 0.5f;

    /**
     * Slope (blocks/block) at which the depth cap stops growing. The cap is
     * MAX_DEPTH_PER_SLOPE * min(slope, MAX_SLOPE_FOR_CAP) * scaleBlocks, so the absolute ceiling is
     * MAX_DEPTH_PER_SLOPE * MAX_SLOPE_FOR_CAP * scaleBlocks blocks (about 34 with the default preset).
     * Behaviour below this slope is unchanged. Lower it (2-3) for tamer cliffs, raise it (4-6) for wilder ones.
     */
    private static final float MAX_SLOPE_FOR_CAP = 3.0f;

    /**
     * slopeOnsetRidge was authored for tiny normalized slopes; blocks/block slopes are ~100-1000x larger.
     * This rescales the ridge mask input so it doesn't saturate at 1. Tune this before the slider.
     */
    private static final float RIDGE_SLOPE_SCALE = 0.01f;

    /**
     * Upper bound for the ridge mask. easeOut() returns exactly 1.0 once its input reaches 1, and the
     * sediment update adds ridgeMapFadeTarget * (1 - mask), so a saturated mask adds exactly 0 and leaves
     * sediment at its default. Capping below 1 keeps a small signal on every cell. Lower = stronger signal
     * on steep cells, at the cost of drifting further from the uncapped behaviour.
     */
    private static final float RIDGE_MASK_MAX = 0.98f;

    /**
     * Normalized height treated as the "midpoint" when deriving the height fade used for masking
     * (terrainScaler float units). Only feeds masks and the sediment seed, never the height delta.
     */
    private static final float HEIGHT_MIDPOINT = 0.5f;

    /**
     * false: noise is signed (ridges rise, gullies cut, ~zero mean).
     * true:  noise is remapped to [0, 1] so the pass only ever ADDS material. Raises average height by
     *        roughly 0.5 * sum(mask * strength), so lower strengthMultiplier to compensate.
     */
    private static final boolean NON_NEGATIVE_NOISE = false;

    /**
     * Per-octave multiplier on the rounding term. 0.001 effectively disables rounding after octave 0,
     * which is intentional: rounding only shapes the first (coarsest) octave.
     */
    private static final float ROUNDING_DECAY_PER_OCTAVE = 0.001f;

    // Prevents sediment from fully zeroing the carve.
    private static final float MIN_SEDIMENT_MODIFIER = 0.2f;

    // Erosion fades in from ground level up to ground + this many levels (see Modifier.range / Modifier.modify).
    // Cells near ground get a linear fraction of the carve. Lower = more detail on low ground.
    private static final int ERODE_RAMP_LEVELS = 15;

    private static final float TAU = 6.2831855f;

    // Per thread scratch buffers, allocated once and reused across apply() calls.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final int seed;
    private final int mapSize;
    private final Modifier modifier;

    private final float scaleBlocks;   // base gully wavelength in blocks
    private final float worldHeight;   // blocks
    private final float strength;
    private final float gullyWeight;
    private final float detail;
    private final int octaves;
    private final float lacunarity;
    private final float gain;
    private final ErosionFilterSettings s;
    private final Levels levels;

    public Erosion(
            final int seed,
            final int mapSize,
            final Modifier modifier,
            final Levels levels
    ) {
        this.seed = seed;
        this.mapSize = mapSize;
        this.modifier = modifier;
        this.levels = levels;

        this.s = PresetManager.PM.erosionFilterSettings;
        this.scaleBlocks = s.scale * BASE_WAVELENGTH_BLOCKS;
        this.worldHeight = (float) levels.worldHeight;
        this.strength = 0.20F * s.strengthMultiplier;
        this.gullyWeight = s.gullySharpness;
        this.detail = 3.00F;
        this.octaves = s.flowOctaves;
        this.lacunarity = s.lacunarity;
        this.gain = s.gain;
    }

    public int getSize() {
        return this.mapSize;
    }

    @Override
    public void apply(Filterable map, int regionX, int regionZ, int iterationsPerChunk) {
        final Size size = map.getBlockSize();
        final int tileSize = size.total(); // no longer shadows the mapSize field
        final Cell[] cells = map.getBacking();

        // Fetch this thread's scratch buffers, growing them only if this map is larger than any seen so far.
        final Scratch scratch = SCRATCH.get();
        scratch.reserve(cells.length);
        final float[] heights = scratch.heights;
        final float[] envHi = scratch.envHi;
        final float[] slopeX = scratch.slopeX;
        final float[] slopeZ = scratch.slopeZ;
        final float[] gateSlope = scratch.gateSlope;
        final float[] gateTmp = scratch.gateTmp;
        final float[] phacelleOut = scratch.phacelleOut;

        // Store the pre-erosion heights (the terrain as this filter received it).
        for (Cell cell : cells) {
            cell.preErosionHeight = cell.height;
        }

        // Step 1: Pre-extract initial height state into flat primitive array
        for (int i = 0; i < cells.length; ++i) {
            heights[i] = cells[i].height;
        }

        // Step 1b: Envelope of the highest original terrain near each cell (used by the headroom limit).
        // gateTmp is free until step 2b, so it doubles as the temp buffer here.
        for (int i = 0; i < cells.length; ++i) {
            final float h = heights[i];
            envHi[i] = Float.isFinite(h) ? h : Float.NEGATIVE_INFINITY; // non-finite cells can't raise the envelope
        }
        windowMax(envHi, gateTmp, tileSize, ENVELOPE_RADIUS);

        // Step 2: Compute slopes with clamped boundary checks.
        // Multiplying by worldHeight gives blocks of rise per block of run.
        //
        // Slopes use a minmod of the one-sided differences (not a central difference), so a plateau rim
        // cell whose far side is flat reads slope 0 instead of half the cliff drop. Tile borders use the
        // one side that exists. Border cases are handled outside the hot loops so the interior loops
        // are branch-light and easy for the JIT to vectorize/inline. Requires tileSize >= 2.
        final float wh = this.worldHeight;
        final int last = tileSize - 1;
        for (int z = 0; z < tileSize; ++z) {
            final int row = z * tileSize;

            // slopeX: border cells first, then the interior.
            slopeX[row] = (heights[row + 1] - heights[row]) * wh;
            for (int x = 1; x < last; ++x) {
                final int idx = row + x;
                final float c = heights[idx];
                slopeX[idx] = minmod(c - heights[idx - 1], heights[idx + 1] - c) * wh;
            }
            slopeX[row + last] = (heights[row + last] - heights[row + last - 1]) * wh;

            // slopeZ: the z border test is loop-invariant, so pick the loop once per row.
            if (z == 0) {
                final int nextRow = row + tileSize;
                for (int x = 0; x < tileSize; ++x) {
                    slopeZ[row + x] = (heights[nextRow + x] - heights[row + x]) * wh;
                }
            } else if (z == last) {
                final int prevRow = row - tileSize;
                for (int x = 0; x < tileSize; ++x) {
                    slopeZ[row + x] = (heights[row + x] - heights[prevRow + x]) * wh;
                }
            } else {
                final int prevRow = row - tileSize;
                final int nextRow = row + tileSize;
                for (int x = 0; x < tileSize; ++x) {
                    final float c = heights[row + x];
                    slopeZ[row + x] = minmod(c - heights[prevRow + x], heights[nextRow + x] - c) * wh;
                }
            }
        }

        // Step 2b: Build the smoothed slope field used ONLY for carve amplitude (gate and depth cap).
        // Non-finite slopes are treated as 0 so they can't poison their neighbors through the blur.
        final int total = tileSize * tileSize;
        for (int i = 0; i < total; ++i) {
            final float sx = slopeX[i];
            final float sz = slopeZ[i];
            final float len = (float) Math.sqrt(sx * sx + sz * sz + 1e-10f);
            gateSlope[i] = Float.isFinite(len) ? len : 0.0f;
        }
        if (GATE_SMOOTH_RADIUS > 0) {
            windowMin(gateSlope, gateTmp, tileSize, GATE_SMOOTH_RADIUS);
            boxBlur(gateSlope, gateTmp, tileSize, GATE_SMOOTH_RADIUS);
        }

        final int worldBlockX = map.getBlockX();
        final int worldBlockZ = map.getBlockZ();

        final float slopeGateRange = Math.max(1e-5f, FLAT_SLOPE_FULL - FLAT_SLOPE_START);

        // Step 3: Single procedural, ADDITIVE pass over all cells
        for (int z = 0; z < tileSize; ++z) {
            final int row = z * tileSize;
            final float worldZ = (float) (worldBlockZ + z);

            for (int x = 0; x < tileSize; ++x) {
                final int idx = row + x;
                final Cell cell = cells[idx];

                if (cell.erosionMask) {
                    continue;
                }

                final float baseHeight = heights[idx];
                if (!Float.isFinite(baseHeight)) {
                    continue;
                }

                final float curSlopeX = slopeX[idx];
                final float curSlopeZ = slopeZ[idx];

                // Raw slope: drives the noise SHAPE (masks, gully direction) and decides whether this cell
                // runs the octave loop.
                final float slopeLen = (float) Math.sqrt(curSlopeX * curSlopeX + curSlopeZ * curSlopeZ + 1e-10f);

                // Used ONLY for masking / sediment seeding, never as a height offset.
                final float heightFade = NoiseUtil.clamp((baseHeight - HEIGHT_MIDPOINT) * 2.0f, -1.0f, 1.0f);

                // Initial ridge mask, capped so it can't saturate (see RIDGE_MASK_MAX).
                float ridgeMapCombiMask = Math.min(RIDGE_MASK_MAX,
                        easeOut(slopeLen * RIDGE_SLOPE_SCALE * s.slopeOnsetRidge));

                final float rawT = NoiseUtil.clamp((slopeLen - FLAT_SLOPE_START) / slopeGateRange, 0.0f, 1.0f);
                if (rawT <= 0.0f) {
                    // Genuinely flat: skip all noise sampling and the carve, but still record sediment.
                    // With no carve mask the octave loop would leave ridgeMapFadeTarget at heightFade and
                    // the mask at its initial value, so the same update is written directly.
                    cell.sediment = NoiseUtil.clamp(
                            cell.sediment + heightFade * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);
                    continue;
                }

                // Smoothed slope: drives the carve AMPLITUDE only (gate and depth cap), so it is continuous
                // across crests, rims and valley floors. It may be 0 here even though the raw gate is open;
                // in that case the cell still updates sediment below but receives no carve.
                final float smoothSlope = gateSlope[idx];
                final float ampT = NoiseUtil.clamp((smoothSlope - FLAT_SLOPE_START) / slopeGateRange, 0.0f, 1.0f);
                final float flatGate = ampT * ampT * (3.0f - 2.0f * ampT);

                // Modifier is linear in its value argument and depends only on the cell (its height,
                // terrain, river mask), so evaluate it once. A factor of exactly 0 (below ground, river
                // centers) means the result is exactly 0, so skip all noise work. Note: those cells do not
                // update cell.sediment.
                final float erosionFactor = this.modifier.modify(cell, 1.0F);
                if (erosionFactor == 0.0F) {
                    continue;
                }

                final float worldX = (float) (worldBlockX + x);

                // Depth in BLOCKS, wavelength in blocks: the ratio stays constant at any world height.
                float currentStrength = this.strength * this.scaleBlocks;
                float freq = 1.0f / (this.scaleBlocks * s.phacelleScale);

                float roundingForInput = lerp(
                        s.roundingMin,
                        s.roundingMax,
                        NoiseUtil.clamp(heightFade + 0.5f, 0.0f, 1.0f));

                // No mask floor: low slope can drive the mask all the way to zero.
                float combiMask = easeOut(smoothStart(
                        slopeLen * s.slopeOnsetBase,
                        roundingForInput * s.slopeOnsetBase));

                float ridgeMapFadeTarget = heightFade;

                float gullySlopeX = curSlopeX;
                float gullySlopeZ = curSlopeZ;

                float accumulatedHeightDelta = 0.0f; // blocks
                float roundingMult = 1.0f;
                float fadeTarget = 0.0f; // zero-centered: no height-driven bias

                // Multi-octave flow erosion iteration
                for (int octave = 0; octave < this.octaves; ++octave) {
                    float gullyLen = (float) Math.sqrt(gullySlopeX * gullySlopeX + gullySlopeZ * gullySlopeZ);
                    float normGullyX = gullyLen > 1e-10f ? gullySlopeX / gullyLen : 0.0f;
                    float normGullyZ = gullyLen > 1e-10f ? gullySlopeZ / gullyLen : 0.0f;

                    samplePhacelleNoise(
                            worldX * freq, worldZ * freq,
                            normGullyX, normGullyZ,
                            s.phacelleScale,
                            s.phacelleOffset,
                            s.phacelleNormalization,
                            this.seed + octave,
                            phacelleOut
                    );

                    float phacelleX = phacelleOut[0];
                    float phacelleY = phacelleOut[1];
                    float phacelleZ = phacelleOut[2];
                    float phacelleW = phacelleOut[3];

                    float sloping = Math.abs(phacelleY);

                    // Slope of a wave with depth A (blocks) at this octave's frequency: A * TAU * phacelleScale * freq.
                    // Keeps the slope feedback in blocks/block, the same units as gullySlope.
                    float gradient = currentStrength * TAU * s.phacelleScale * freq;
                    float gullySign = Math.signum(phacelleY);
                    gullySlopeX += gullySign * phacelleZ * gradient * this.gullyWeight;
                    gullySlopeZ += gullySign * phacelleW * gradient * this.gullyWeight;

                    // Purely additive
                    float noiseTerm = phacelleX * this.gullyWeight;
                    if (NON_NEGATIVE_NOISE) {
                        noiseTerm = 0.5f * (noiseTerm + 1.0f);
                    }
                    float fadedGullyVal = lerp(fadeTarget, noiseTerm, combiMask);
                    accumulatedHeightDelta += fadedGullyVal * currentStrength;
                    fadeTarget = fadedGullyVal;

                    float roundingOctave = lerp(
                            s.roundingMin,
                            s.roundingMax,
                            NoiseUtil.clamp(phacelleX + 0.5f, 0.0f, 1.0f)) * roundingMult;
                    float newMask = easeOut(smoothStart(sloping * s.slopeOnsetBase, roundingOctave * s.slopeOnsetBase));
                    combiMask = powInv(combiMask, this.detail) * newMask; // no floor

                    ridgeMapFadeTarget = lerp(ridgeMapFadeTarget, phacelleX, ridgeMapCombiMask);
                    // Capped so the mask can't pin at exactly 1.0 through every octave.
                    ridgeMapCombiMask = Math.min(RIDGE_MASK_MAX,
                            ridgeMapCombiMask * easeOut(sloping * s.slopeOnsetOctave));

                    currentStrength *= this.gain;
                    freq *= this.lacunarity;
                    roundingMult *= ROUNDING_DECAY_PER_OCTAVE;
                }

                if (!Float.isFinite(accumulatedHeightDelta)) {
                    continue;
                }

                cell.sediment = NoiseUtil.clamp(cell.sediment + ridgeMapFadeTarget * (1.0f - ridgeMapCombiMask), -1.0f, 1.0f);

                // The smoothed gate can be closed while the raw gate is open. Sediment is already recorded
                // above, so there is nothing left to do for this cell.
                if (flatGate <= 0.0f) {
                    continue;
                }

                // Up to 2.0 when sediment == -1, so the depth cap below must be applied AFTER this.
                final float sedimentModifier = Math.max(MIN_SEDIMENT_MODIFIER, 1.0F - cell.sediment);

                // Gate by slope, apply sediment, then cap by what the (saturated, smoothed) slope can
                // physically support. Saturating keeps the cap finite on cliffs, and smoothing keeps it
                // continuous from cell to cell.
                final float capSlope = Math.min(smoothSlope, MAX_SLOPE_FOR_CAP);
                final float maxDepth = MAX_DEPTH_PER_SLOPE * capSlope * this.scaleBlocks;
                final float gatedBlocks = NoiseUtil.clamp(
                        accumulatedHeightDelta * flatGate * sedimentModifier,
                        -maxDepth, maxDepth);

                // Soft headroom limit: positive carve may not push the cell meaningfully above the highest
                // original terrain nearby. headroom >= ENVELOPE_MARGIN_BLOCKS > 0, so the division is safe.
                float limitedBlocks = gatedBlocks;
                if (limitedBlocks > 0.0f) {
                    final float headroom = (envHi[idx] - baseHeight) * wh + ENVELOPE_MARGIN_BLOCKS;
                    limitedBlocks = limitedBlocks * headroom / (limitedBlocks + headroom);
                }

                // Blocks -> this world's normalized height units.
                final float deltaNormalized = limitedBlocks / this.worldHeight;

                final float change = erosionFactor * deltaNormalized;
                cell.heightErosion += change;
                cell.height += change;
            }
        }

        // Keep land that was carved below the waterline at ground level, and keep heightErosion consistent.
        for (Cell cell : cells) {
            boolean wasLand = cell.preErosionHeight > this.levels.water;
            boolean afterIsWater = cell.height <= this.levels.water;
            if (wasLand && afterIsWater) {
                cell.heightErosion += this.levels.ground - cell.height;
                cell.height = this.levels.ground;
            }
        }
    }

    /**
     * Separable square-window maximum (radius r, clamped at the tile edges). Result is written back into
     * {@code buf}; {@code tmp} is scratch. Used to find the highest original terrain near each cell.
     */
    private static void windowMax(final float[] buf, final float[] tmp, final int size, final int r) {
        final int last = size - 1;

        // Horizontal: buf -> tmp
        for (int z = 0; z < size; ++z) {
            final int row = z * size;
            for (int x = 0; x < size; ++x) {
                final int lo = Math.max(0, x - r);
                final int hi = Math.min(last, x + r);
                float m = buf[row + lo];
                for (int k = lo + 1; k <= hi; ++k) {
                    final float v = buf[row + k];
                    if (v > m) m = v;
                }
                tmp[row + x] = m;
            }
        }

        // Vertical: tmp -> buf
        for (int z = 0; z < size; ++z) {
            final int lo = Math.max(0, z - r);
            final int hi = Math.min(last, z + r);
            for (int x = 0; x < size; ++x) {
                float m = tmp[lo * size + x];
                for (int k = lo + 1; k <= hi; ++k) {
                    final float v = tmp[k * size + x];
                    if (v > m) m = v;
                }
                buf[z * size + x] = m;
            }
        }
    }

    /**
     * Separable square-window minimum (radius r, clamped at the tile edges). Result is written back into
     * {@code buf}; {@code tmp} is scratch. Spreads zeros (crests, rims, valley floors) outward by r cells.
     */
    private static void windowMin(final float[] buf, final float[] tmp, final int size, final int r) {
        final int last = size - 1;

        // Horizontal: buf -> tmp
        for (int z = 0; z < size; ++z) {
            final int row = z * size;
            for (int x = 0; x < size; ++x) {
                final int lo = Math.max(0, x - r);
                final int hi = Math.min(last, x + r);
                float m = buf[row + lo];
                for (int k = lo + 1; k <= hi; ++k) {
                    final float v = buf[row + k];
                    if (v < m) m = v;
                }
                tmp[row + x] = m;
            }
        }

        // Vertical: tmp -> buf
        for (int z = 0; z < size; ++z) {
            final int lo = Math.max(0, z - r);
            final int hi = Math.min(last, z + r);
            for (int x = 0; x < size; ++x) {
                float m = tmp[lo * size + x];
                for (int k = lo + 1; k <= hi; ++k) {
                    final float v = tmp[k * size + x];
                    if (v < m) m = v;
                }
                buf[z * size + x] = m;
            }
        }
    }

    /**
     * Separable box blur (radius r), averaging only over in-bounds cells at the tile edges. Result is
     * written back into {@code buf}; {@code tmp} is scratch. Turns the hard edge of the quiet zone left by
     * {@link #windowMin} into a ramp.
     */
    private static void boxBlur(final float[] buf, final float[] tmp, final int size, final int r) {
        final int last = size - 1;

        // Horizontal: buf -> tmp
        for (int z = 0; z < size; ++z) {
            final int row = z * size;
            for (int x = 0; x < size; ++x) {
                final int lo = Math.max(0, x - r);
                final int hi = Math.min(last, x + r);
                float sum = 0.0f;
                for (int k = lo; k <= hi; ++k) {
                    sum += buf[row + k];
                }
                tmp[row + x] = sum / (hi - lo + 1);
            }
        }

        // Vertical: tmp -> buf
        for (int z = 0; z < size; ++z) {
            final int lo = Math.max(0, z - r);
            final int hi = Math.min(last, z + r);
            final float inv = 1.0f / (hi - lo + 1);
            for (int x = 0; x < size; ++x) {
                float sum = 0.0f;
                for (int k = lo; k <= hi; ++k) {
                    sum += tmp[k * size + x];
                }
                buf[z * size + x] = sum * inv;
            }
        }
    }

    /**
     * Minmod limiter on the backward and forward one-sided differences: if they disagree in sign, or
     * either is zero, the result is 0; otherwise the one with the smaller magnitude wins.
     * Pure and allocation-free, so it is safe to call from any thread and trivially inlined.
     */
    private static float minmod(final float back, final float fwd) {
        if (back * fwd <= 0.0f) {
            return 0.0f;
        }
        return Math.abs(back) < Math.abs(fwd) ? back : fwd;
    }

    /**
     * Samples 2D Phacelle Noise vector aligned with flow direction (normDirX, normDirZ).
     */
    private static void samplePhacelleNoise(
            float px, float pz,
            float normDirX, float normDirZ,
            float freq, float offset, float normalization, int seed,
            float[] result
    ) {
        float sideDirX = -normDirZ * freq * TAU;
        float sideDirZ = normDirX * freq * TAU;
        offset *= TAU;

        int pIntX = (int) Math.floor(px);
        int pIntZ = (int) Math.floor(pz);
        float pFracX = px - pIntX;
        float pFracZ = pz - pIntZ;

        float phaseDirX = 0.0f;
        float phaseDirZ = 0.0f;
        float weightSum = 0.0f;

        for (int i = -1; i <= 2; ++i) {
            for (int j = -1; j <= 2; ++j) {
                int cellX = pIntX + i;
                int cellZ = pIntZ + j;

                float hashX = hash(cellX, cellZ, seed) * 0.5f;
                float hashZ = hash(cellX, cellZ, seed + 1013) * 0.5f;

                float vecX = pFracX - i - hashX;
                float vecZ = pFracZ - j - hashZ;

                float sqrDist = vecX * vecX + vecZ * vecZ;
                float weight = Math.max(0.0f, (float) Math.exp(-sqrDist * 2.0f) - 0.01111f);

                weightSum += weight;
                float waveInput = vecX * sideDirX + vecZ * sideDirZ + offset;
                phaseDirX += (float) Math.cos(waveInput) * weight;
                phaseDirZ += (float) Math.sin(waveInput) * weight;
            }
        }

        float invWeight = 1.0f / Math.max(weightSum, 1e-5f);
        float interpX = phaseDirX * invWeight;
        float interpZ = phaseDirZ * invWeight;

        float magnitude = (float) Math.sqrt(interpX * interpX + interpZ * interpZ);
        magnitude = Math.max(1.0f - normalization, magnitude);

        result[0] = interpX / magnitude;
        result[1] = interpZ / magnitude;
        result[2] = sideDirX / (freq * TAU + 1e-5f);
        result[3] = sideDirZ / (freq * TAU + 1e-5f);
    }

    private static float hash(int x, int z, int seed) {
        int n = x * 374761393 + z * 668265263 + seed * 144677219;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF - 0.5f;
    }

    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private static float easeOut(float t) {
        float v = 1.0f - NoiseUtil.clamp(t, 0.0f, 1.0f);
        return 1.0f - v * v;
    }

    private static float powInv(float t, float power) {
        return 1.0f - (float) Math.pow(1.0f - NoiseUtil.clamp(t, 0.0f, 1.0f), power);
    }

    private static float smoothStart(float t, float smoothing) {
        if (t >= smoothing) {
            return t - 0.5f * smoothing;
        }
        return 0.5f * t * t / Math.max(smoothing, 1e-5f);
    }

    // Per thread scratch storage.
    private static final class Scratch {
        float[] heights = new float[0];
        float[] envHi = new float[0];     // Highest original terrain within ENVELOPE_RADIUS of each cell
        float[] slopeX = new float[0];
        float[] slopeZ = new float[0];
        float[] gateSlope = new float[0]; // Smoothed slope magnitude used for the carve gate and depth cap
        float[] gateTmp = new float[0];   // Temp buffer for the separable max / min / blur passes
        final float[] phacelleOut = new float[4];

        /** Grow-only: reallocates only when a larger map than any previously seen arrives on this thread. */
        void reserve(final int n) {
            if (this.heights.length < n) {
                this.heights = new float[n];
                this.envHi = new float[n];
                this.slopeX = new float[n];
                this.slopeZ = new float[n];
                this.gateSlope = new float[n];
                this.gateTmp = new float[n];
            }
        }
    }

    public static IntFunction<Erosion> factory(final GeneratorContext context) {
        return new Factory(Seed.toInt(context.seed.root()), context.levels);
    }

    private static class Factory implements IntFunction<Erosion> {
        private static final int SEED_OFFSET = 12768;
        private final int seed;
        private final Modifier modifier;
        private final Levels levels;

        private Factory(final int seed, final Levels levels) {
            this.seed = seed + SEED_OFFSET;
            this.modifier = Modifier.range(levels.ground, levels.ground(ERODE_RAMP_LEVELS));
            this.levels = levels;
        }

        @Override
        public Erosion apply(final int size) {
            return new Erosion(this.seed, size, this.modifier, this.levels);
        }
    }
}