package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import etcodehome.freeterraforged.world.worldgen.noise.function.DistanceFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.EdgeFunction;
import etcodehome.freeterraforged.world.worldgen.noise.module.LegacyMoisture;
import etcodehome.freeterraforged.world.worldgen.noise.module.LegacyTemperature;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class ClimateModule {
	private int seed;
	private float biomeFreq;
	private float warpStrength;
	private Noise warpX;
	private Noise warpZ;
	private Noise moisture;
	private Noise temperature;
	private Noise macroBiomeNoise;
	private Continent continent;
	private ControlPoints controlPoints;
	private Levels levels;
	private static final float ALTITUDE_COOLING_STRENGTH = 2.2F; // TODO - configurable strength
	private static final float RAIN_SHADOW_STRENGTH = 1.8F;    // Exponent k scales up to 2^1.8 ~ 3.48 // TODO - configurable strength
	private static final float COASTAL_MOISTURE_BOOST = 0.20F; // TODO - configurable strength
	
	public ClimateModule(Seed seed, Continent continent, WorldSettings.ControlPoints controlPoints, ClimateSettings climateSettings, Levels levels) {
		int biomeSize = climateSettings.biomeShape.biomeSize();
		
		float tempScaler = (float) climateSettings.temperature.scale;
		float moistScaler = climateSettings.moisture.scale * 2.5F;
		float biomeFreq = 1.0F / biomeSize;
		float moistureSize = moistScaler * biomeSize;
		float temperatureSize = tempScaler * biomeSize;
		
		int moistScale = NoiseUtil.round(moistureSize * biomeFreq);
		int tempScale = NoiseUtil.round(temperatureSize * biomeFreq);
		int warpScale = climateSettings.biomeShape.biomeWarpScale;
		
		this.continent = continent;
		this.seed = seed.next();
		this.biomeFreq = 1.0F / biomeSize;
		this.controlPoints = controlPoints;
		this.warpStrength = (float) climateSettings.biomeShape.biomeWarpStrength;
		this.levels = levels;
		
		Noise warpX = Noises.simplex(seed.next(), warpScale, 2);
		warpX = Noises.add(warpX, -0.5F);
		this.warpX = warpX;
		
		Noise warpZ = Noises.simplex(seed.next(), warpScale, 2);
		warpZ = Noises.add(warpZ, -0.5F);
		this.warpZ = warpZ;
		
		Seed moistureSeed = seed.offset(climateSettings.moisture.seedOffset);
		
		Noise moistureSource = Noises.simplex(moistureSeed.next(), moistScale, 1);
		moistureSource = Noises.clamp(moistureSource, 0.125F, 0.875F);
		moistureSource = Noises.map(moistureSource, 0.0F, 1.0F);
		moistureSource = Noises.frequency(moistureSource, 0.5F, 1.0F);
		
		Noise moisture = new LegacyMoisture(moistureSource, climateSettings.moisture.falloff);
		moisture = climateSettings.moisture.apply(moisture);
		moisture = Noises.warpPerlin(moisture, moistureSeed.next(), Math.max(1, moistScale / 2), 1, moistScale / 4.0F);
		moisture = Noises.warpPerlin(moisture, moistureSeed.next(), Math.max(1, moistScale / 6), 2, moistScale / 12.0F);
		this.moisture = moisture;
		
		Seed tempSeed = seed.offset(climateSettings.temperature.seedOffset);
		Noise temperature = new LegacyTemperature(1.0F / tempScale, climateSettings.temperature.falloff);
		temperature = climateSettings.temperature.apply(temperature);
		temperature = Noises.warpPerlin(temperature, tempSeed.next(), tempScale * 4, 2, tempScale * 4);
		temperature = Noises.warpPerlin(temperature, tempSeed.next(), tempScale, 1, tempScale);
		this.temperature = temperature;
		
		Noise macroBiomeNoise = Noises.worley(seed.next(), climateSettings.biomeShape.macroNoiseSize);
		this.macroBiomeNoise = macroBiomeNoise;
	}

	public void apply(Cell cell, float x, float z, float originalX, float originalZ) {
		this.apply(cell, x, z, originalX, originalZ, true);
	}

	public void apply(Cell cell, float x, float z, float originalX, float originalZ, boolean mask) {
		float warpedX = x + this.warpX.compute(x, z, 0) * this.warpStrength;
		float warpedZ = z + this.warpZ.compute(x, z, 0) * this.warpStrength;
		x = warpedX * this.biomeFreq;
		z = warpedZ * this.biomeFreq;
		this.resolveRegion(cell, x, z, mask);
		float centerX = cell.biomeRegionCenterX;
		float centerZ = cell.biomeRegionCenterZ;
		int cellX = (int) cell.biomeRegionX;
		int cellZ = (int) cell.biomeRegionZ;
		cell.biomeRegionId = this.cellValue(this.seed, cellX, cellZ);
		cell.macroBiomeId = this.macroBiomeNoise.compute(centerX, centerZ, 0);
		int posX = NoiseUtil.floor(centerX / this.biomeFreq);
		int posZ = NoiseUtil.floor(centerZ / this.biomeFreq);

		// Constant per biome cell: only for things meant to be uniform across a region.
		float regionEdge = this.continent.getLandValue(posX, posZ);
		if (mask) {
			this.modifyTerrain(cell, regionEdge);
		}

		float rawMoist = this.moisture.compute(x, z, 0);
		float moist = this.modifyMoisture(cell.height, rawMoist);

		float rawTemp = this.temperature.compute(x, z, 0);
		float temp = this.modifyTemp(cell.height, rawTemp);

		// convert from normalised 0-1 range to -1 to 1 range
		cell.temperature = temp * 2.0F - 1.0F;
		cell.moisture = moist * 2.0F - 1.0F;
	}

	private float modifyTemp(float height, float rawTemp) {
		// Only apply cooling above ground level
		if (height <= this.levels.ground) {
			return rawTemp;
		}

		// getNormalizedInlandElevation returns 0.0 at sea level and 1.0 at maximum terrain height
		float heightNorm = NoiseUtil.clamp(this.levels.getNormalizedInlandElevation(height), 0.0F, 1.0F);

		// Calculate exponential warp factor (k ranges from 1.0 at sea level to 4.0 at peak height)
		float exponent = (float) Math.pow(2.0, heightNorm * ALTITUDE_COOLING_STRENGTH);

		float tempNormalized = NoiseUtil.clamp(rawTemp, 0.0F, 1.0F);

		return (float) Math.pow(tempNormalized, exponent);
	}

	public float modifyMoisture(float height, float rawMoisture) {
		float moisture = NoiseUtil.clamp(rawMoisture, 0.0F, 1.0F);

		// Calculate height relative to sea level (0.0 at ground, 1.0 at peak inland terrain)
		float heightNorm = (height <= this.levels.ground) ? 0.0F :
				NoiseUtil.clamp(this.levels.getNormalizedInlandElevation(height), 0.0F, 1.0F);

		// Coastal boost fades as elevation rises inland
		float coastalInfluence = 1.0F - heightNorm;
		if (coastalInfluence > 0.0F) {
			moisture = moisture + (1.0F - moisture) * (COASTAL_MOISTURE_BOOST * coastalInfluence);
		}

		// Return boosted moisture directly at or below sea level
		if (height <= this.levels.ground) {
			return NoiseUtil.clamp(moisture, 0.0F, 1.0F);
		}

		// Rain Shadow Exponent (k ranges from 1.0 at sea level to ~3.48 at max peak)
		float exponent = (float) Math.pow(2.0, heightNorm * RAIN_SHADOW_STRENGTH);

		// Power curve preserves 1.0 (rainforests) while exponentially drying mid-tier moisture
		return (float) Math.pow(moisture, exponent);
	}


	public void applyRegion(Cell cell, float x, float z, boolean mask) {
		float warpedX = x + this.warpX.compute(x, z, 0) * this.warpStrength;
		float warpedZ = z + this.warpZ.compute(x, z, 0) * this.warpStrength;
		this.resolveRegion(
			cell, warpedX * this.biomeFreq, warpedZ * this.biomeFreq, mask
		);
	}

	private void resolveRegion(Cell cell, float x, float z, boolean mask) {
		int xr = NoiseUtil.floor(x);
		int zr = NoiseUtil.floor(z);
		int cellX = xr;
		int cellZ = zr;
		float centerX = x;
		float centerZ = z;
		float edgeDistance = 999999.0F;
		float edgeDistance2 = 999999.0F;
		DistanceFunction dist = DistanceFunction.EUCLIDEAN;
		for (int dz = -1; dz <= 1; ++dz) {
			for (int dx = -1; dx <= 1; ++dx) {
				int cx = xr + dx;
				int cz = zr + dz;
				Vec2f vec = NoiseUtil.cell(this.seed, cx, cz);
				float cxf = cx + vec.x();
				float czf = cz + vec.y();
				float distance = dist.apply(cxf - x, czf - z);
				if (distance < edgeDistance) {
					edgeDistance2 = edgeDistance;
					edgeDistance = distance;
					centerX = cxf;
					centerZ = czf;
					cellX = cx;
					cellZ = cz;
				} else if (distance < edgeDistance2) {
					edgeDistance2 = distance;
				}
			}
		}
		cell.biomeRegionCenterX = centerX;
		cell.biomeRegionCenterZ = centerZ;
		cell.biomeRegionX = cellX;
		cell.biomeRegionZ = cellZ;
		if (mask) {
			cell.biomeRegionEdge = this.edgeValue(edgeDistance, edgeDistance2);
		}
	}

	private void modifyTerrain(Cell cell, float continentEdge) {
		if (cell.terrain.isOverground() && !cell.terrain.overridesCoast() && continentEdge <= this.controlPoints.coastMarker()
			&& cell.terrain != TerrainType.ISLAND && cell.terrain != TerrainType.ISLAND_BEACH && cell.terrain != TerrainType.ISLAND_MOUNTAINS) {
			cell.terrain = TerrainType.COAST;
		}
	}

	private float cellValue(int seed, int cellX, int cellY) {
		float value = NoiseUtil.valCoord2D(seed, cellX, cellY);
		return NoiseUtil.map(value, -1.0F, 1.0F, 2.0F);
	}

	private float edgeValue(float distance, float distance2) {
		EdgeFunction edge = EdgeFunction.DISTANCE_2_DIV;
		float value = edge.apply(distance, distance2);
		value = 1.0F - NoiseUtil.map(value, edge.min(), edge.max(), edge.range());
		return value;
	}
}
