package etcodehome.freeterraforged.world.worldgen.cell.heightmap;

import etcodehome.freeterraforged.data.worldgen.preset.PresetNoiseData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.biome.Erosion;
import etcodehome.freeterraforged.world.worldgen.biome.Weirdness;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.CellPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.climate.Climate;
import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.continent.ContinentLerper2;
import etcodehome.freeterraforged.world.worldgen.cell.continent.ContinentLerper3;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.ContinentalHydrology;
import etcodehome.freeterraforged.world.worldgen.cell.rivermap.Rivermap;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Blender;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.IslandBlender;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.Populators;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.TerrainType;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.ArchipelagoPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.VariedMountainPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.populator.VolcanoPopulator;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.provider.TerrainProvider;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.region.RegionLerper;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.region.RegionModule;
import etcodehome.freeterraforged.world.worldgen.cell.terrain.region.RegionSelector;
import etcodehome.freeterraforged.world.worldgen.noise.function.DistanceFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.EdgeFunction;
import etcodehome.freeterraforged.world.worldgen.noise.function.Interpolation;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import net.minecraft.core.HolderGetter;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public record Heightmap(CellPopulator terrain, CellPopulator region, Continent continent, Climate climate, Levels levels, WorldSettings.ControlPoints controlPoints, float terrainFrequency, Noise beachNoise) {
	
	public void apply(Cell cell, float x, float z, boolean applyClimate) {
		this.applyTerrain(cell, x, z);
		if (cell.terrain == TerrainType.ISLAND_BEACH) {
			cell.terrain = TerrainType.BEACH;
		}
		this.applyRivers(cell, x, z, this.continent.getRivermap(cell));
		this.applyClimate(cell, x, z, applyClimate);
	}
	
	public void applyTerrain(Cell cell, float x, float z) {
        cell.terrain = TerrainType.FLATS;
        cell.beachNoise = this.beachNoise.compute(x, z, 0);
        this.continent.apply(cell, x, z);
        this.region.apply(cell, x, z);
        this.terrain.apply(cell, x * this.terrainFrequency, z * this.terrainFrequency);
	}
	
	public void applyRivers(Cell cell, float x, float z, Rivermap rivermap) {
		cell.terrainErosion = cell.erosion;
        rivermap.apply(cell, x, z);
        VolcanoPopulator.modifyVolcanoType(cell, this.levels);
	}
	
	public void applyClimate(Cell cell, float x, float z, boolean applyClimate) {
		float riverValleyThreshold = 0.675F;
        if(cell.terrain.isRiver()) {
            cell.erosion = -0.05F;
            cell.weirdness = -0.03F;
        }
        
        if(cell.terrain.isLake() && cell.height < this.levels.water) {
            cell.erosion = Erosion.LEVEL_4.mid();
            cell.weirdness = -0.03F;
        }
        if(cell.terrain.isWetland()) {
        	cell.erosion = Erosion.LEVEL_6.mid();
        	cell.weirdness = Weirdness.VALLEY.mid();
        }
        
        this.climate.apply(cell, x, z, applyClimate);

        if(cell.riverMask >= riverValleyThreshold && cell.macroBiomeId > 0.5F) { 
        	cell.weirdness = -cell.weirdness;
        }
	}
	
	public static Heightmap make(GeneratorContext ctx) {
    	HolderGetter<Noise> noiseLookup = ctx.noiseLookup;
    	
        Preset preset = ctx.preset;
        WorldSettings world = ctx.preset.world();
        WorldSettings.ControlPoints controlPoints = world.controlPoints;

        TerrainSettings terrainSettings = preset.terrain();
        TerrainSettings.General general = terrainSettings.general;
        float globalVerticalScale = general.globalVerticalScale;
        
        Seed regionWarp = ctx.seed.offset(8934);
        int regionWarpScale = 400;
        int regionWarpStrength = 200;
        
        RegionConfig regionConfig = new RegionConfig(
        	ctx.seed.root() + 789124, 
        	general.terrainRegionSize, 
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1),
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1), 
        	regionWarpStrength
        );
        Levels levels = ctx.levels;
        float terrainFrequency = 1.0F / terrainSettings.general.globalHorizontalScale;
        CellPopulator region = new RegionModule(regionConfig);

        Seed mountainSeed = ctx.seed.offset(general.terrainSeedOffset);
        Noise mountainShape = Noises.worleyEdge(mountainSeed.next(), general.legacyMountainScaling ? 1000 : Math.round(1000 * terrainSettings.mountains.horizontalScale * 2.25F), EdgeFunction.DISTANCE_2_ADD, DistanceFunction.EUCLIDEAN);
        mountainShape = Noises.warpPerlin(mountainShape, mountainSeed.next(), 333, 2, 250.0F);
        mountainShape = Noises.curve(mountainShape, Interpolation.CURVE3);
        mountainShape = Noises.clamp(mountainShape, 0.0F, 0.9F);
        mountainShape = Noises.map(mountainShape, 0.0F, 1.0F);

        Noise ground = PresetNoiseData.getNoise(noiseLookup, PresetTerrainTypeNoise.GROUND);
        
        CellPopulator terrainRegions = new RegionSelector(TerrainProvider.generateTerrain(ctx.seed, terrainSettings, regionConfig, levels, noiseLookup));
        CellPopulator terrainRegionBorders = Populators.makeBorder(ctx.seed, ground, terrainSettings.plains, terrainSettings.steppe, globalVerticalScale);
        CellPopulator terrainBlend = new RegionLerper(terrainRegionBorders, terrainRegions);
        CellPopulator mountains;
        if (general.mountainVariety > 0.0F) {
        	float variety = general.mountainVariety;
        	TerrainSettings.Terrain mtnSettings = terrainSettings.mountains;
        	boolean legacy = general.legacyMountainScaling;
        	float chainHScale = legacy ? 1.0F : mtnSettings.horizontalScale * 2.25F;
        	float chainVScale = legacy ? globalVerticalScale : globalVerticalScale * mtnSettings.verticalScale;

        	TerrainPopulator chainCenter = Populators.makeMountainChain(mountainSeed, ground, mtnSettings, chainHScale, chainVScale, general.fancyMountains, legacy);

        	Seed chainVarietySeed = mountainSeed.offset(719);

        	TerrainSettings.Terrain lowSettings = new TerrainSettings.Terrain(
        		mtnSettings.weight,
        		mtnSettings.baseScale * (1.0F - 0.15F * variety),
        		mtnSettings.verticalScale * (1.0F - 0.20F * variety),
        		mtnSettings.horizontalScale * (1.0F + 0.35F * variety)
        	);
        	float lowChainHScale = legacy ? 1.0F : lowSettings.horizontalScale * 2.25F;
        	float lowChainVScale = legacy ? globalVerticalScale : globalVerticalScale * lowSettings.verticalScale;
        	float lowErosion = 0.65F + 0.20F * variety;
        	TerrainPopulator chainLow = Populators.makeMountainChain(chainVarietySeed, ground, lowSettings, lowChainHScale, lowChainVScale, general.fancyMountains, legacy, lowErosion);

        	TerrainSettings.Terrain highSettings = new TerrainSettings.Terrain(
        		mtnSettings.weight,
        		mtnSettings.baseScale * (1.0F + 0.15F * variety),
        		mtnSettings.verticalScale * (1.0F + 0.20F * variety),
        		mtnSettings.horizontalScale * (1.0F - 0.35F * variety)
        	);
        	float highChainHScale = legacy ? 1.0F : highSettings.horizontalScale * 2.25F;
        	float highChainVScale = legacy ? globalVerticalScale : globalVerticalScale * highSettings.verticalScale;
        	float highErosion = 0.65F - 0.25F * variety;
        	TerrainPopulator chainHigh = Populators.makeMountainChain(chainVarietySeed, ground, highSettings, highChainHScale, highChainVScale, general.fancyMountains, legacy, highErosion);

        	mountains = new VariedMountainPopulator(new TerrainPopulator[]{chainLow, chainCenter, chainHigh}, chainCenter, mtnSettings.weight);
        } else {
        	mountains = Populators.makeMountainChain(mountainSeed, ground, terrainSettings.mountains, terrainSettings.general.legacyMountainScaling ? 1.0F : terrainSettings.mountains.horizontalScale * 2.25F, terrainSettings.general.legacyMountainScaling ? globalVerticalScale : globalVerticalScale * terrainSettings.mountains.verticalScale, general.fancyMountains, general.legacyMountainScaling);
        }
        Continent continent = world.continent.continentType.create(ctx.seed, ctx);
        Climate climate = Climate.make(continent, ctx);
        CellPopulator land = new Blender(mountainShape, terrainBlend, mountains, 0.3F, 0.8F, 0.575F);
        
        CellPopulator deepOcean = Populators.makeDeepOcean(ctx.seed.next(), ctx.levels, world.properties.oceanDepth);
        CellPopulator shallowOcean = Populators.makeShallowOcean(ctx.levels, world.properties.oceanDepth);
        CellPopulator coast = Populators.makeCoast(ctx.levels);

        CellPopulator oceans = new ContinentLerper3(deepOcean, shallowOcean, coast, controlPoints.deepOcean, controlPoints.shallowOcean, controlPoints.coast);

        CellPopulator terrain = new ContinentLerper2(oceans, (cell, x, z) -> {
            land.apply(cell, x, z);
            cell.globalContinentScale = world.continent.continentScale;
            cell.height += (ContinentalHydrology.getComplexWaterHeight(
                    cell.waterTable,
                    cell.globalContinentScale,
                    cell.continentSizeModifier)
            );
        }, controlPoints.shallowOcean, controlPoints.inland);
        
        // Wrap with archipelago layer if enabled
        if (ctx.preset.island().enableArchipelago) {
            terrain = new IslandBlender(terrain, new ArchipelagoPopulator(ctx.preset.island(), ctx.levels, controlPoints, ctx.seed, world.properties.oceanDepth), ctx.levels);
        }

        Noise beachNoise = Noises.perlin2(ctx.seed.next(), 20, 1);
        beachNoise = Noises.mul(beachNoise, ctx.levels.scale(5));
        return new Heightmap(terrain, region, continent, climate, levels, controlPoints, terrainFrequency, beachNoise);
	}
}
