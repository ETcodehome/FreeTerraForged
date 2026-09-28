package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.world.worldgen.biome.Humidity;
import etcodehome.freeterraforged.world.worldgen.biome.Temperature;
import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainCategory;
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

	// Must match Climate.EDGE_BLEND: below this biomeRegionEdge the edge offset is active,
	// so region-center overrides must be fully faded out by then.
	private static final float REGION_FADE = 0.4F;
	// Cell.height range over which the highland override fades in above ground level.
	private static final float HIGHLAND_RISE = 0.10F;
	// Blocks above the waterline over which the island override fades in (keeps beaches smooth).
	private static final float ISLAND_RISE_BLOCKS = 6.0F;
	private static final float MUSHROOM_THRESHOLD = 0.95F;
	
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

	ClimateModule(
		int seed,
		float biomeFreq,
		float warpStrength,
		Noise warpX,
		Noise warpZ,
		Noise moisture,
		Noise temperature,
		Noise macroBiomeNoise,
		Continent continent,
		ControlPoints controlPoints,
		Levels levels
	) {
		this.seed = seed;
		this.biomeFreq = biomeFreq;
		this.warpStrength = warpStrength;
		this.warpX = warpX;
		this.warpZ = warpZ;
		this.moisture = moisture;
		this.temperature = temperature;
		this.macroBiomeNoise = macroBiomeNoise;
		this.continent = continent;
		this.controlPoints = controlPoints;
		this.levels = levels;
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

		// Smooth per-pixel climate (values in [0, 1]).
		float temp = this.temperature.compute(x, z, 0);
		//float temp = this.modifyTemp(cell.height, this.temperature.compute(x, z, 0), originalX, originalZ);
		float moist = this.moisture.compute(x, z, 0);
		//float moist = this.modifyMoisture(this.moisture.compute(x, z, 0), cell.continentEdge);

		/*

		// Highlands: fade toward the terrain region's center climate instead of switching to it.
		float hw = this.highlandWeight(cell);
		if (hw > 0.0F) {
			float hx = cell.terrainRegionCenterX * this.biomeFreq;
			float hz = cell.terrainRegionCenterZ * this.biomeFreq;
			float ht = this.modifyTemp(cell.height, this.temperature.compute(hx, hz, 0), originalX, originalZ);
			float hm = this.modifyMoisture(this.moisture.compute(hx, hz, 0), regionEdge);
			//temp = NoiseUtil.lerp(temp, ht, hw);
			//moist = NoiseUtil.lerp(moist, hm, hw);
		}

		// Islands: fade toward the biome region's center climate (or mushroom climate).
		float iw = this.islandWeight(cell);
		if (iw > 0.0F) {
			// The terrain flag carries the mushroom decision into the edge-offset second pass,
			// where macroBiomeId belongs to the offset region and can no longer be trusted.
			boolean mushroom = cell.terrain == TerrainType.MUSHROOM_FIELDS;
			if (!mushroom && mask && cell.macroBiomeId > MUSHROOM_THRESHOLD) {
				cell.terrain = TerrainType.MUSHROOM_FIELDS;
				mushroom = true;
			}
			float it;
			float im;
			if (mushroom) {
				it = (Temperature.LEVEL_2.mid() + 1.0F) * 0.5F; // Moderate
				im = (Humidity.LEVEL_4.mid() + 1.0F) * 0.5F;    // Wet
			} else {
				it = this.modifyTemp(cell.height, this.temperature.compute(centerX, centerZ, 0), originalX, originalZ);
				im = this.modifyMoisture(this.moisture.compute(centerX, centerZ, 0), regionEdge);
			}
			//temp = NoiseUtil.lerp(temp, it, iw);
			//moist = NoiseUtil.lerp(moist, im, iw);
		}

		 */

		// convert from normalised 0-1 range to -1 to 1 range
		cell.temperature = temp * 2.0F - 1.0F;
		cell.moisture = moist * 2.0F - 1.0F;
	}

	/** 0 at terrain-region borders and near ground level, 1 in the interior of a raised highland region. */
	private float highlandWeight(Cell cell) {
		if (cell.terrain == null || cell.terrain.getCategory() != TerrainCategory.HIGHLAND) {
			return 0.0F;
		}
		float region = NoiseUtil.interpHermite(NoiseUtil.clamp(cell.terrainRegionEdge, 0.0F, 1.0F));
		float rise = NoiseUtil.interpHermite(NoiseUtil.clamp((cell.height - this.levels.ground) / HIGHLAND_RISE, 0.0F, 1.0F));
		return region * rise;
	}

	/** 0 at biome-region borders and at the shoreline, 1 in the island interior. */
	private float islandWeight(Cell cell) {
		if (cell.terrain.getCategory() != TerrainCategory.ISLAND) {
			return 0.0F;
		}
		float region = NoiseUtil.interpHermite(NoiseUtil.clamp(cell.biomeRegionEdge / REGION_FADE, 0.0F, 1.0F));
		float rise = NoiseUtil.interpHermite(NoiseUtil.clamp(
				(cell.height - this.levels.water) / (ISLAND_RISE_BLOCKS * this.levels.unit), 0.0F, 1.0F));
		return region * rise;
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
