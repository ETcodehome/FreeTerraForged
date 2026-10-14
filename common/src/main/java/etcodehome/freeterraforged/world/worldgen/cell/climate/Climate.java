package etcodehome.freeterraforged.world.worldgen.cell.climate;

import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Levels;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import net.minecraft.core.HolderGetter;
import etcodehome.freeterraforged.data.worldgen.preset.PresetClimateNoise;
import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public record Climate(int randomSeed, Noise offsetX, Noise offsetZ, int offsetDistance, Levels levels, ClimateModule biomeNoise) {
	private static final float EDGE_BLEND = 0.4F;

	public void apply(Cell cell, float x, float z, boolean applyClimate) {
		this.biomeNoise.apply(cell, x, z, x, z, true);
		if (cell.height <= this.levels.water) {
			if (cell.terrain == TerrainType.COAST) {
				cell.terrain = TerrainType.SHALLOW_OCEAN;
			}
		} else if (this.usesEdgeClimate(cell)) {
			this.applyEdge(cell, x, z, false);
		}
	}

	/**
	 * Raw regional climate for an already populated cell at these block coordinates.
	 * Reuses this owner's edge policy and noise graphs. The caller must supply a cell
	 * from this Climate's terrain context and position. Does not mutate the cell,
	 * acquire terrain, or retain mutable inputs; safe for a read-only snapshot.
	 */
	public ClimateModule.RawSample sampleRaw(Cell cell, float x, float z) {
		if (cell.height > this.levels.water && this.usesEdgeClimate(cell)) {
			float distance = this.edgeDistance(cell);
			float dx = this.offsetX.compute(x, z, 0) * distance;
			float dz = this.offsetZ.compute(x, z, 0) * distance;
			x += dx;
			z += dz;
		}
		return this.biomeNoise.sampleRaw(x, z);
	}

	private boolean usesEdgeClimate(Cell cell) {
		return cell.biomeRegionEdge < EDGE_BLEND || cell.terrain == TerrainType.MOUNTAIN_CHAIN;
	}

	private float edgeDistance(Cell cell) {
		float modifier = 1.0F - NoiseUtil.map(cell.biomeRegionEdge, 0.0F, EDGE_BLEND, EDGE_BLEND);
		return this.offsetDistance * modifier;
	}

	public boolean applyInitialRegion(Cell cell, float x, float z) {
		this.biomeNoise.applyRegion(cell, x, z, true);
		return cell.biomeRegionEdge < EDGE_BLEND;
	}

	public void applyEdgeRegion(Cell cell, float x, float z) {
		this.applyEdge(cell, x, z, true);
	}

	private void applyEdge(Cell cell, float x, float z, boolean regionOnly) {
		float distance = this.edgeDistance(cell);
		float dx = this.offsetX.compute(x, z, 0) * distance;
		float dz = this.offsetZ.compute(x, z, 0) * distance;
		if (regionOnly) {
			this.biomeNoise.applyRegion(cell, x + dx, z + dz, false);
		} else {
			this.biomeNoise.apply(cell, x + dx, z + dz, x, z, false);
		}
	}
	
	public static Climate make(Continent continent, GeneratorContext context) {
		HolderGetter<Noise> noiseLookup = context.noiseLookup;
		
		Preset preset = context.preset;
		
		WorldSettings worldSettings = preset.world();
		ClimateSettings climateSettings = preset.climate();
		
		ClimateModule biomeNoise = new ClimateModule(context.seed, continent, worldSettings.controlPoints, climateSettings, context.levels);
		Levels levels = context.levels;
		int randSeed = context.seed.next();
		
		Noise biomeEdgeShape = PresetNoiseData.getNoise(noiseLookup, PresetClimateNoise.BIOME_EDGE_SHAPE);
		Noise offsetX = Noises.shiftSeed(biomeEdgeShape, context.seed.next());
		Noise offsetZ = Noises.shiftSeed(biomeEdgeShape, context.seed.next());
		int offsetDistance = climateSettings.biomeEdgeShape.strength;
		return new Climate(randSeed, offsetX, offsetZ, offsetDistance, levels, biomeNoise);
	}
}
