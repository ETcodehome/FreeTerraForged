package raccoonman.reterraforged.world.worldgen.cell.terrain;

import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.biome.BiomeParameter;
import raccoonman.reterraforged.world.worldgen.biome.Erosion;
import raccoonman.reterraforged.world.worldgen.biome.Weirdness;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.OceanPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.EdgeFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.noise.module.Erosion.BlendMode;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class Populators {

	private static final int EROSION_VARIATION_SEED_OFFSET = 48291;
	private static final int WEIRDNESS_VARIATION_SEED_OFFSET = 73519;
	private static final int EROSION_VARIATION_SCALE = 200;
	private static final int WEIRDNESS_VARIATION_SCALE = 300;

	public static CellPopulator makeDeepOcean(@Deprecated int seed, Levels levels, int oceanDepth) {
		int minDepth = Math.max(8, oceanDepth / 3);
		int canyonMinDepth = minDepth + Math.max(1, (oceanDepth - minDepth) / 2);

		float lower = Math.max(levels.water(-oceanDepth), levels.min);
		float upper = Math.max(levels.water(-minDepth), lower);
		float canyonUpper = Math.max(levels.water(-canyonMinDepth), lower);

		// Scale horizontal noise and warp with vertical depth to keep ocean-floor slopes consistent.
		float depthScale = oceanDepth / (float) WorldSettings.Properties.DEFAULT_OCEAN_DEPTH;
		int hillsScale = Math.max(1, Math.round(150 * depthScale));
		int selectorScale = Math.max(1, Math.round(500 * depthScale));
		int warpScale = Math.max(1, Math.round(50 * depthScale));
		float warpStrength = 50.0F * depthScale;

		Noise hills = Noises.perlin(++seed, hillsScale, 3);
		hills = Noises.map(hills, lower, upper);

		Noise canyons = Noises.perlin(++seed, hillsScale, 4);
		canyons = Noises.powCurve(canyons, 0.2F);
		canyons = Noises.invert(canyons);
		canyons = Noises.map(canyons, lower, canyonUpper);

		Noise selector = Noises.perlin(++seed, selectorScale, 1);

		Noise height = Noises.blend(selector, hills, canyons, 0.6F, 0.65F);
		height = Noises.warpPerlin(height, ++seed, warpScale, 2, warpStrength);
		height = Noises.clamp(height, lower, upper);
		return new OceanPopulator(TerrainType.DEEP_OCEAN, height, levels.min);
	}

	public static CellPopulator makeShallowOcean(Levels levels, int oceanDepth) {
		int shallowDepth = Math.max(7, oceanDepth / 9);
		float height = Math.max(levels.water(-shallowDepth), levels.min);
		return new OceanPopulator(TerrainType.SHALLOW_OCEAN, Noises.constant(height), levels.min);
	}

	public static CellPopulator makeCoast(Levels levels) {
		return new OceanPopulator(TerrainType.COAST, Noises.constant(levels.water));
	}

    public static TerrainPopulator makeSteppe(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
        int scaleH = Math.round(250.0F * settings.horizontalScale);

        Noise erosion = Noises.perlin(seed.next(), scaleH * 2, 3, 3.75F);
        erosion = Noises.alpha(erosion, 0.45F);

        Noise warpX = Noises.perlin(seed.next(), scaleH / 4, 3, 3.0F);
        Noise warpZ = Noises.perlin(seed.next(), scaleH / 4, 3, 3.0F);

        Noise height = Noises.perlin(seed.next(), scaleH, 1);
        height = Noises.mul(height, erosion);
        height = Noises.warp(height, warpX, warpZ, scaleH / 4.0F);
        height = Noises.warpPerlin(height, seed.next(), 256, 1, 200.0F);
        height = Noises.mul(height, 0.08F);
        height = Noises.add(height, -0.02F);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_2, Erosion.LEVEL_4, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = negativeWeirdnessVariation(seed.offset(WEIRDNESS_VARIATION_SEED_OFFSET));
		return TerrainPopulator.make(TerrainType.FLATS, ground, height, climateErosion, climateWeirdness, settings);
    }

    private static TerrainPopulator makePlains(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain noiseSettings, TerrainSettings.Terrain scalingSettings, float verticalScale) {
    	int scaleH = Math.round(250.0F * noiseSettings.horizontalScale);

		Noise erosion = Noises.perlin(seed.next(), scaleH * 2, 3, 3.75F);
      	erosion = Noises.alpha(erosion, 0.45F);

      	Noise warpX = Noises.perlin(seed.next(), scaleH / 4, 3, 3.5F);
      	Noise warpZ = Noises.perlin(seed.next(), scaleH / 4, 3, 3.5F);

      	Noise height = Noises.perlin(seed.next(), scaleH, 1);
      	height = Noises.mul(height, erosion);
      	height = Noises.warp(height, warpX, warpZ, scaleH / 4.0F);
      	height = Noises.warpPerlin(height, seed.next(), 256, 1, 256.0F);
      	height = Noises.mul(height, 0.15F * verticalScale);
      	height = Noises.add(height, -0.02F);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_2, Erosion.LEVEL_4, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = negativeWeirdnessVariation(seed.offset(WEIRDNESS_VARIATION_SEED_OFFSET));
      	return TerrainPopulator.make(TerrainType.FLATS, ground, height, climateErosion, climateWeirdness, scalingSettings);
    }

    public static TerrainPopulator makePlains(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
    	return makePlains(seed, ground, settings, settings, verticalScale);
    }

	public static TerrainPopulator makePlateau(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise valley = Noises.perlinRidge(seed.next(), 500, 1);
		valley = Noises.invert(valley);
		valley = Noises.warpPerlin(valley, seed.next(), 100, 1, 150.0F);
		valley = Noises.warpPerlin(valley, seed.next(), 20, 1, 15.0F);

		Noise top = Noises.perlinRidge(seed.next(), 150, 3, 2.45F);
		top = Noises.warpPerlin(top, seed.next(), 300, 1, 150.0F);
		top = Noises.warpPerlin(top, seed.next(), 40, 2, 20.0F);
		top = Noises.mul(top, 0.15F);

		Noise valleyScaler = Noises.clamp(valley, 0.02F, 0.1F);
		valleyScaler = Noises.map(valleyScaler, 0.0F, 1.0F);

		top = Noises.mul(top, valleyScaler);

		Noise surface = Noises.perlin(seed.next(), 20, 3);
		surface = Noises.mul(surface, 0.05F);
		surface = Noises.warpPerlin(surface, seed.next(), 40, 2, 20.0F);

		Noise cubic = Noises.cubic(seed.next(), 500, 1);
		cubic = Noises.mul(cubic, 0.6F);
		cubic = Noises.add(cubic, 0.3F);

		Noise valleyBase = Noises.mul(valley, cubic);
		valleyBase = Noises.add(valleyBase, top);

		Noise height = Noises.terrace(valleyBase, 0.9F, 0.15F, 0.35F, 0.4F, 4);
		height = Noises.add(height, surface);
		height = Noises.mul(height, 0.475F * verticalScale);

		Noise weirdness = Noises.clamp(valleyBase, 0.0F, 0.415F);
		weirdness = Noises.map(weirdness, 0.0F, 1.0F);
		weirdness = Noises.map(weirdness, Weirdness.LOW_SLICE_NORMAL_DESCENDING.mid(), -0.42F);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_4, EROSION_VARIATION_SCALE);
		return TerrainPopulator.make(TerrainType.PLATEAU, ground, height, climateErosion, weirdness, settings);
	}

	public static TerrainPopulator makeHills1(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise height = Noises.perlin(seed.next(), 200, 3);

		Noise scaler = Noises.billow(seed.next(), 400, 3);
		scaler = Noises.alpha(scaler, 0.5F);

		height = Noises.mul(height, scaler);
		height = Noises.warpPerlin(height, seed.next(), 30, 3, 20.0F);
		height = Noises.warpPerlin(height, seed.next(), 400, 3, 200.0F);
		height = Noises.mul(height, 0.6F * verticalScale);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_5, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = negativeWeirdnessVariation(seed.offset(WEIRDNESS_VARIATION_SEED_OFFSET));
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, climateErosion, climateWeirdness, settings);
	}

	public static TerrainPopulator makeHills2(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale) {
		Noise height = Noises.cubic(seed.next(), 128, 2);

		Noise scaler1 = Noises.perlin(seed.next(), 32, 4);
		scaler1 = Noises.alpha(scaler1, 0.075F);
		height = Noises.mul(height, scaler1);

		height = Noises.warpPerlin(height, seed.next(), 30, 3, 20.0F);
		height = Noises.warpPerlin(height, seed.next(), 400, 3, 200.0F);

		Noise scaler2 = Noises.perlinRidge(seed.next(), 512, 2);
		scaler2 = Noises.alpha(scaler2, 0.8F);
		height = Noises.mul(height, scaler2);

		height = Noises.mul(height, 0.55F * verticalScale);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_5, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = negativeWeirdnessVariation(seed.offset(WEIRDNESS_VARIATION_SEED_OFFSET));
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, climateErosion, climateWeirdness, settings);
	}

	public static TerrainPopulator makeDales(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise hills1 = Noises.billow(seed.next(), 300, 4, 4.0F, 0.8F);
		hills1 = Noises.powCurve(hills1, 0.5F);
		hills1 = Noises.mul(hills1, 0.75F);

		Noise hills2 = Noises.billow(seed.next(), 350, 3, 4.0F, 0.8F);
		hills2 = Noises.pow(hills2, 1.25F);

		Noise selector = Noises.perlin(seed.next(), 400, 1);
		selector = Noises.clamp(selector, 0.3F, 0.6F);
		selector = Noises.map(selector, 0.0F, 1.0F);

		int warpSeed = seed.next();

		Noise hillsBlend = Noises.blend(selector, hills1, hills2, 0.4F, 0.75F);

		Noise height = hillsBlend;
		height = Noises.pow(height, 1.125F);
		height = Noises.warpPerlin(height, warpSeed, 300, 1, 100.0F);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_5, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = Noises.min(Noises.mul(height, -1.0F), Noises.constant(-0.06F));
		return TerrainPopulator.make(TerrainType.HILLS, ground, Noises.mul(height, 0.4F), climateErosion, climateWeirdness, settings);
	}

	public static TerrainPopulator makeBadlands(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise mask = Noises.perlin(seed.next(), 270, 3);
		mask = Noises.clamp(mask, 0.35F, 0.65F);
		mask = Noises.map(mask, 0.0F, 1.0F);

		Noise hills = Noises.perlinRidge(seed.next(), 275, 4);
		hills = Noises.warpPerlin(hills, seed.next(), 400, 2, 100.0F);
		hills = Noises.warpPerlin(hills, seed.next(), 18, 1, 20.0F);
		hills = Noises.mul(hills, mask);

		float modulation = 0.4F;
		float alpha = 1.0F - modulation;

		Noise mod1 = Noises.warpPerlin(hills, seed.next(), 100, 1, 50.0F);
		mod1 = Noises.mul(mod1, modulation);

		Noise lowFreq = Noises.steps(hills, 4, 0.6F, 0.7F);
		lowFreq = Noises.mul(lowFreq, alpha);
		lowFreq = Noises.add(lowFreq, mod1);

		Noise highFreq = Noises.steps(hills, 10, 0.6F, 0.7F);
		highFreq = Noises.mul(highFreq, alpha);
		highFreq = Noises.add(highFreq, mod1);

		Noise detail = Noises.add(lowFreq, highFreq);
		detail = Noises.alpha(detail, 0.5F);

		Noise scaler = Noises.perlin(seed.next(), 200, 3);
		scaler = Noises.mul(scaler, modulation);

		Noise mod2 = Noises.mul(hills, scaler);

		Noise shape = Noises.steps(hills, 4, 0.65F, 0.75F, Interpolation.CURVE3);
		shape = Noises.mul(shape, alpha);
		shape = Noises.add(shape, mod2);
		shape = Noises.mul(shape, alpha);

		Noise height = Noises.mul(shape, detail);
		height = Noises.mul(height, 0.55F);
		height = Noises.add(height, 0.025F);
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_3, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = negativeWeirdnessVariation(seed.offset(WEIRDNESS_VARIATION_SEED_OFFSET));
		return TerrainPopulator.make(TerrainType.BADLANDS, ground, height, climateErosion, climateWeirdness, settings);
	}

	// TODO only use erosion + ridge combos that respect continentalness
	public static TerrainPopulator makeTorridonian(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings) {
		Noise plains = Noises.perlin(seed.next(), 100, 3);
		plains = Noises.warpPerlin(plains, seed.next(), 300, 1, 150.0F);
		plains = Noises.warpPerlin(plains, seed.next(), 20, 1, 40.0F);
		plains = Noises.mul(plains, 0.15F);

		Noise hills = Noises.perlin(seed.next(), 150, 4);
		hills = Noises.warpPerlin(hills, seed.next(), 300, 1, 200.0F);
		hills = Noises.warpPerlin(hills, seed.next(), 20, 2, 20.0F);
		hills = Noises.boost(hills);

		Noise selector = Noises.perlin(seed.next(), 200, 3);

		Noise modulation = Noises.perlin(seed.next(), 120, 1);
		modulation = Noises.mul(modulation, 0.25F);

		Noise mask = Noises.perlin(seed.next(), 200, 1);
		mask = Noises.mul(mask, 0.5F);
		mask = Noises.add(mask, 0.5F);

		Noise slope = Noises.constant(0.5F);

		Noise blend = Noises.blend(selector, plains, hills, 0.6F, 0.6F);
		blend = Noises.advancedTerrace(blend, modulation, mask, slope, 0.0F, 0.3F, 6, 1);
		Noise height = Noises.boost(blend);
		height = Noises.mul(height, 0.5F);

		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_1, Erosion.LEVEL_5, EROSION_VARIATION_SCALE);
		Noise climateWeirdness = Noises.min(Noises.negative(blend), Noises.constant(Weirdness.LOW_SLICE_NORMAL_DESCENDING.max() - 0.01F));
		return TerrainPopulator.make(TerrainType.HILLS, ground, height, climateErosion, climateWeirdness, settings);
	}

    private static final int MOUNTAINS_H = 610;
    private static final float MOUNTAINS_V = 1.3F;
    private static final int MOUNTAINS3_H = 600;
    private static final float MOUNTAINS3_V = 1.185F;
	private static final float DEFAULT_EROSION_STRENGTH = 0.65F;

	private static TerrainPopulator makeMountains(Terrain terrainType, @Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling, float erosionStrength) {
		int scaleH = legacyScaling ? Math.round(410.0F * settings.horizontalScale) : Math.round(MOUNTAINS_H * settings.horizontalScale);

		Noise height = Noises.perlinRidge(seed.next(), scaleH, 4, 2.35F, 1.15F);

		Noise scaler = Noises.perlin(seed.next(), 24, 4);
		scaler = Noises.alpha(scaler, 0.075F);

		height = Noises.mul(height, scaler);
		height = Noises.warpPerlin(height, seed.next(), 350, 1, 150.0F);
		if(makeFancy) {
			height = makeFancy(seed, height);
		}
		height = Noises.cache2d(height);
		Noise climateErosion = mountainErosion(seed, erosionStrength);
		return TerrainPopulator.make(terrainType, ground, Noises.mul(height, (legacyScaling ? 0.7F : MOUNTAINS_V) * verticalScale), climateErosion, Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
	}

	public static TerrainPopulator makeMountains(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling, float erosionStrength) {
		return makeMountains(TerrainType.MOUNTAINS_1, seed, ground, settings, horizontalScale, verticalScale, makeFancy, legacyScaling, erosionStrength);
	}

	public static TerrainPopulator makeMountains(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling) {
		return makeMountains(TerrainType.MOUNTAINS_1, seed, ground, settings, horizontalScale, verticalScale, makeFancy, legacyScaling, DEFAULT_EROSION_STRENGTH);
	}

	public static TerrainPopulator makeMountainChain(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling) {
		return makeMountainChain(seed, ground, settings, horizontalScale, verticalScale, makeFancy, legacyScaling, DEFAULT_EROSION_STRENGTH);
	}

	public static TerrainPopulator makeMountainChain(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float horizontalScale, float verticalScale, boolean makeFancy, boolean legacyScaling, float erosionStrength) {
		return makeMountains(TerrainType.MOUNTAIN_CHAIN, seed, ground, settings, legacyScaling ? horizontalScale : horizontalScale * 2.25F, verticalScale, makeFancy, legacyScaling, erosionStrength);
	}

	public static TerrainPopulator makeMountains2(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling, float erosionStrength) {
		Noise cell = Noises.worleyEdge(seed.next(), legacyScaling ? 360 : Math.round(360 * settings.horizontalScale), EdgeFunction.DISTANCE_2, DistanceFunction.EUCLIDEAN);
		cell = Noises.mul(cell, 1.2F);
		cell = Noises.clamp(cell, 0.0F, 1.0F);
		cell = Noises.warpPerlin(cell, seed.next(), 200, 2, 100.0F);

		Noise blur = Noises.perlin(seed.next(), 10, 1);
		blur = Noises.alpha(blur, 0.025F);

		Noise surface = Noises.perlinRidge(seed.next(), 125, 4);
		surface = Noises.alpha(surface, 0.37F);

		Noise height = Noises.clamp(cell, 0.0F, 1.0F);
		height = Noises.mul(height, blur);
		height = Noises.mul(height, surface);
		height = Noises.pow(height, 1.1F);
		if(makeFancy) {
			height = makeFancy(seed, height);
		}
		height = Noises.cache2d(height);
		Noise climateErosion = mountainErosion(seed, erosionStrength);
		return TerrainPopulator.make(TerrainType.MOUNTAINS_2, ground, Noises.mul(height, 0.645F * verticalScale), climateErosion, Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
	}

	public static TerrainPopulator makeMountains2(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling) {
		return makeMountains2(seed, ground, settings, verticalScale, makeFancy, legacyScaling, DEFAULT_EROSION_STRENGTH);
	}

    public static TerrainPopulator makeMountains3(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling, float erosionStrength) {
    	Noise cell = Noises.worleyEdge(seed.next(), legacyScaling ? 400 : Math.round(MOUNTAINS3_H * settings.horizontalScale), EdgeFunction.DISTANCE_2, DistanceFunction.EUCLIDEAN);
    	cell = Noises.mul(cell, 1.2F);
    	cell = Noises.clamp(cell, 0.0F, 1.0F);
    	cell = Noises.warpPerlin(cell, seed.next(), 200, 2, 100.0F);

    	Noise blur = Noises.perlin(seed.next(), 10, 1);
    	blur = Noises.alpha(blur, 0.025F);

    	Noise surface = Noises.perlinRidge(seed.next(), 125, 4);
    	surface = Noises.alpha(surface, 0.37F);

    	Noise mountains = Noises.clamp(cell, 0.0F, 1.0F);
    	mountains = Noises.mul(mountains, blur);
    	mountains = Noises.mul(mountains, surface);
    	mountains = Noises.pow(mountains, 1.1F);

    	Noise modulation = Noises.perlin(seed.next(), 50, 1);
    	modulation = Noises.mul(modulation, 0.5F);

    	Noise mask = Noises.perlin(seed.next(), 100, 1);
    	mask = Noises.clamp(mask, 0.5F, 0.95F);
    	mask = Noises.map(mask, 0.0F, 1.0F);

    	Noise slope = Noises.constant(0.45F);

    	Noise height = Noises.advancedTerrace(mountains, modulation, mask, slope, 0.20000000298023224F, 0.44999998807907104F, 24, 1);
    	if(makeFancy) {
			height = makeFancy(seed, height);
    	}
		height = Noises.cache2d(height);
		Noise climateErosion = mountainErosion(seed, erosionStrength);
		return TerrainPopulator.make(TerrainType.MOUNTAINS_3, ground, Noises.mul(height, (legacyScaling ? 0.645F : MOUNTAINS3_V) * verticalScale), climateErosion, Noises.min(Noises.mul(height, Noises.constant(-1.0F)), Noises.constant(-0.08F)), settings);
    }

    public static TerrainPopulator makeMountains3(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain settings, float verticalScale, boolean makeFancy, boolean legacyScaling) {
    	return makeMountains3(seed, ground, settings, verticalScale, makeFancy, legacyScaling, DEFAULT_EROSION_STRENGTH);
    }

	public static Noise makeFancy(@Deprecated Seed seed, Noise input) {
		Domain domain = Domains.direction(
			Noises.perlin(seed.next(), 10, 1),
			Noises.constant(2.0F)
		);
		Noise erosion = Noises.erosion(input, seed.next(), 2, DEFAULT_EROSION_STRENGTH, 128.0F, 0.15F, 3.1F, 0.8F, BlendMode.CONSTANT);
		erosion = Noises.warp(erosion, domain);
		return erosion;
	}

	private static Noise mountainErosion(Seed seed, float erosionStrength) {
		Noise climateErosion = parameterVariation(seed.offset(EROSION_VARIATION_SEED_OFFSET), Erosion.LEVEL_0, Erosion.LEVEL_3, EROSION_VARIATION_SCALE);
		if (erosionStrength == DEFAULT_EROSION_STRENGTH) {
			return climateErosion;
		}
		return Noises.clamp(Noises.add(climateErosion, erosionStrength - DEFAULT_EROSION_STRENGTH), Erosion.LEVEL_0.min(), Erosion.LEVEL_6.max());
	}

	public static TerrainPopulator makeBorder(@Deprecated Seed seed, Noise ground, TerrainSettings.Terrain plainsSettings, TerrainSettings.Terrain steppeSettings, float verticalScale) {
		return makePlains(seed, ground, plainsSettings, steppeSettings, verticalScale);
	}

	private static Noise parameterVariation(Seed seed, BiomeParameter from, BiomeParameter to, int scale) {
		Noise variation = Noises.perlin(seed.next(), scale, 2);
		return Noises.map(variation, from.min(), to.max());
	}

	private static Noise negativeWeirdnessVariation(Seed seed) {
		// Vanilla reserves [-0.05, 0.05] for its valley slice. The existing macro-biome
		// sign inversion below the terrain pipeline supplies the corresponding variant
		// slice, so ordinary terrain only needs to produce the normal descending side.
		Noise variation = Noises.perlin(seed.next(), WEIRDNESS_VARIATION_SCALE, 2);
		return Noises.map(
			variation,
			Weirdness.MID_SLICE_NORMAL_DESCENDING.min(),
			Weirdness.LOW_SLICE_NORMAL_DESCENDING.max() - 0.01F
		);
	}

	private static Noise centeredVariation(Seed seed, float center, float halfWidth) {
		Noise variation = Noises.perlin(seed.next(), 200, 2);
		return Noises.map(variation, center - halfWidth, center + halfWidth);
	}
}
