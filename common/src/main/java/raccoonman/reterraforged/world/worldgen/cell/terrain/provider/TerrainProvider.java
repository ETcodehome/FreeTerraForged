package raccoonman.reterraforged.world.worldgen.cell.terrain.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

import net.minecraft.core.HolderGetter;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.RegionConfig;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Populators;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.TerrainPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.VariedMountainPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.VolcanoPopulator;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class TerrainProvider {

    public static List<CellPopulator> generateTerrain(Seed seed, TerrainSettings settings, RegionConfig config, Levels levels, HolderGetter<Noise> noiseLookup) {
    	TerrainSettings.General general = settings.general;
    	float verticalScale = general.globalVerticalScale;
    	boolean fancyMountains = general.fancyMountains;
    	boolean legacyMountainScaling = general.legacyMountainScaling;
    	float mountainVariety = general.mountainVariety;
    	Seed terrainSeed = seed.offset(general.terrainSeedOffset);

    	Noise ground = PresetNoiseData.getNoise(noiseLookup, PresetTerrainTypeNoise.GROUND);

    	List<TerrainPopulator> mixable = new ArrayList<>();
    	mixable.add(Populators.makeSteppe(terrainSeed, ground, settings.steppe));
    	mixable.add(Populators.makePlains(terrainSeed, ground, settings.plains, verticalScale));
        mixable.add(Populators.makeDales(terrainSeed, ground, settings.dales));
        mixable.add(Populators.makeHills1(terrainSeed, ground, settings.hills, verticalScale));
        mixable.add(Populators.makeHills2(terrainSeed, ground, settings.hills, verticalScale));
        mixable.add(Populators.makeTorridonian(terrainSeed, ground, settings.torridonian));
        mixable.add(Populators.makePlateau(terrainSeed, ground, settings.plateau, verticalScale));
        mixable.add(Populators.makeBadlands(terrainSeed, ground, settings.badlands));
    	mixable = mixable.stream().filter((populator) -> {
    		return populator.weight() > 0.0F;
    	}).toList();

        List<CellPopulator> unmixable = new ArrayList<>();
        unmixable.add(Populators.makeBadlands(terrainSeed, ground, settings.badlands));

        if (mountainVariety > 0.0F) {
        	addVariedMountains(unmixable, terrainSeed, ground, settings.mountains, verticalScale, fancyMountains, legacyMountainScaling, mountainVariety);
        } else {
        	unmixable.add(Populators.makeMountains(terrainSeed, ground, settings.mountains, 1.0F, verticalScale, fancyMountains, legacyMountainScaling));
        	unmixable.add(Populators.makeMountains2(terrainSeed, ground, settings.mountains, verticalScale, fancyMountains, legacyMountainScaling));
        	unmixable.add(Populators.makeMountains3(terrainSeed, ground, settings.mountains, verticalScale, fancyMountains, legacyMountainScaling));
        }

        unmixable.add(new VolcanoPopulator(terrainSeed, config, levels, settings.volcano.weight));

        List<TerrainPopulator> mixed = combine(mixable, (t1, t2) -> {
        	return combine(t1, t2, terrainSeed, levels, config.scale() / 2);
        });

        List<CellPopulator> result = new ArrayList<>();
        result.addAll(mixed);
        result.addAll(unmixable);
        Collections.shuffle(result, new Random(terrainSeed.next()));
        return result;
    }
    
    private static final float MAX_SPREAD_VERTICAL = 0.20F;
    private static final float MAX_SPREAD_HORIZONTAL = 0.35F;
    private static final float MAX_SPREAD_BASE = 0.15F;
    private static final float EROSION_CENTER = 0.65F;
    private static final float EROSION_SPREAD_LOW = 0.25F;
    private static final float EROSION_SPREAD_HIGH = 0.20F;

    private static void addVariedMountains(List<CellPopulator> unmixable, Seed seed, Noise ground, TerrainSettings.Terrain mountains, float verticalScale, boolean fancyMountains, boolean legacyMountainScaling, float variety) {
    	TerrainPopulator m1Center = Populators.makeMountains(seed, ground, mountains, 1.0F, verticalScale, fancyMountains, legacyMountainScaling);
    	TerrainPopulator m2Center = Populators.makeMountains2(seed, ground, mountains, verticalScale, fancyMountains, legacyMountainScaling);
    	TerrainPopulator m3Center = Populators.makeMountains3(seed, ground, mountains, verticalScale, fancyMountains, legacyMountainScaling);

    	Seed varietySeed = seed.offset(713);

    	TerrainSettings.Terrain lowSettings = new TerrainSettings.Terrain(
    		mountains.weight,
    		mountains.baseScale * (1.0F - MAX_SPREAD_BASE * variety),
    		mountains.verticalScale * (1.0F - MAX_SPREAD_VERTICAL * variety),
    		mountains.horizontalScale * (1.0F + MAX_SPREAD_HORIZONTAL * variety)
    	);
    	float lowErosion = EROSION_CENTER + EROSION_SPREAD_HIGH * variety;
    	TerrainPopulator m1Low = Populators.makeMountains(varietySeed, ground, lowSettings, 1.0F, verticalScale, fancyMountains, legacyMountainScaling, lowErosion);
    	TerrainPopulator m2Low = Populators.makeMountains2(varietySeed, ground, lowSettings, verticalScale, fancyMountains, legacyMountainScaling, lowErosion);
    	TerrainPopulator m3Low = Populators.makeMountains3(varietySeed, ground, lowSettings, verticalScale, fancyMountains, legacyMountainScaling, lowErosion);

    	TerrainSettings.Terrain highSettings = new TerrainSettings.Terrain(
    		mountains.weight,
    		mountains.baseScale * (1.0F + MAX_SPREAD_BASE * variety),
    		mountains.verticalScale * (1.0F + MAX_SPREAD_VERTICAL * variety),
    		mountains.horizontalScale * (1.0F - MAX_SPREAD_HORIZONTAL * variety)
    	);
    	float highErosion = EROSION_CENTER - EROSION_SPREAD_LOW * variety;
    	TerrainPopulator m1High = Populators.makeMountains(varietySeed, ground, highSettings, 1.0F, verticalScale, fancyMountains, legacyMountainScaling, highErosion);
    	TerrainPopulator m2High = Populators.makeMountains2(varietySeed, ground, highSettings, verticalScale, fancyMountains, legacyMountainScaling, highErosion);
    	TerrainPopulator m3High = Populators.makeMountains3(varietySeed, ground, highSettings, verticalScale, fancyMountains, legacyMountainScaling, highErosion);

    	unmixable.add(new VariedMountainPopulator(new TerrainPopulator[]{m1Low, m1Center, m1High}, mountains.weight));
    	unmixable.add(new VariedMountainPopulator(new TerrainPopulator[]{m2Low, m2Center, m2High}, mountains.weight));
    	unmixable.add(new VariedMountainPopulator(new TerrainPopulator[]{m3Low, m3Center, m3High}, mountains.weight));
    }

    private static TerrainPopulator combine(TerrainPopulator tp1, TerrainPopulator tp2, Seed seed, Levels levels, int scale) {
        Terrain type = TerrainType.registerComposite(tp1.type(), tp2.type());
        Noise selector = Noises.perlin(seed.next(), scale, 1);
        selector = Noises.warpPerlin(selector, seed.next(), scale / 2, 2, scale / 2.0F);

        Noise height = Noises.blend(selector, tp1.height(), tp2.height(), 0.5F, 0.25F);
        height = Noises.max(height, Noises.zero());
        
        Noise erosion = Noises.blend(selector, tp1.erosion(), tp2.erosion(), 0.5F, 0.25F);
        Noise weirdness = Noises.threshold(selector, tp1.weirdness(), tp2.weirdness(), 0.5F);

        float weight = (tp1.weight() + tp2.weight()) / 2.0F;
        return new TerrainPopulator(type, Noises.constant(levels.ground), height, erosion, weirdness, weight);
    }
    
    private static <T> List<T> combine(List<T> input, BiFunction<T, T, T> operator) {
        int length = input.size();
        for (int i = 1; i < input.size(); ++i) {
            length += input.size() - i;
        }
        List<T> result = new ArrayList<T>(length);
        for (int j = 0; j < length; ++j) {
            result.add(null);
        }
        int j = 0;
        int k = input.size();
        while (j < input.size()) {
            T t1 = input.get(j);
            result.set(j, t1);
            for (int l = j + 1; l < input.size(); ++l, ++k) {
                T t2 = input.get(l);
                T t3 = operator.apply(t1, t2);
                result.set(k, t3);
            }
            ++j;
        }
        return result;
    }
}
