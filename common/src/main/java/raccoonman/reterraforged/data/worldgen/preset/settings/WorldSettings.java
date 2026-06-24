package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;

import java.util.Optional;

public class WorldSettings {
	public static final Codec<WorldSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Continent.CODEC.fieldOf("continent").forGetter((o) -> o.continent),
		ControlPoints.CODEC.fieldOf("controlPoints").forGetter((o) -> o.controlPoints),
		Properties.CODEC.fieldOf("properties").forGetter((o) -> o.properties),
		Ocean.CODEC.optionalFieldOf("ocean", new Ocean(7, 32, 96, 150)).forGetter((o) -> o.ocean)
	).apply(instance, WorldSettings::new));

    public Continent continent;
    public ControlPoints controlPoints;
    public Properties properties;
    public Ocean ocean;

    public WorldSettings(Continent continent, ControlPoints controlPoints, Properties properties, Ocean ocean) {
        this.continent = continent;
        this.controlPoints = controlPoints;
        this.properties = properties;
        this.ocean = ocean;
    }

    public WorldSettings(Continent continent, ControlPoints controlPoints, Properties properties) {
        this(continent, controlPoints, properties, new Ocean(7, 32, 96, 150));
    }

    public WorldSettings copy() {
    	return new WorldSettings(this.continent.copy(), this.controlPoints.copy(), this.properties.copy(), this.ocean.copy());
    }
    
    public static class Continent {
    	public static final Codec<Continent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		ContinentType.CODEC.fieldOf("continentType").forGetter((o) -> o.continentType),
    		DistanceFunction.CODEC.optionalFieldOf("continentShape", DistanceFunction.EUCLIDEAN).forGetter((o) -> o.continentShape),
    		Codec.INT.fieldOf("continentScale").forGetter((o) -> o.continentScale),
    		Codec.FLOAT.fieldOf("continentJitter").forGetter((o) -> o.continentJitter),
    		Codec.FLOAT.optionalFieldOf("continentSkipping", 0.25F).forGetter((o) -> o.continentSkipping),
    		Codec.FLOAT.optionalFieldOf("continentSizeVariance", 0.25F).forGetter((o) -> o.continentSizeVariance),
    		Codec.INT.optionalFieldOf("continentNoiseOctaves", 5).forGetter((o) -> o.continentNoiseOctaves),
    		Codec.FLOAT.optionalFieldOf("continentNoiseGain", 0.26F).forGetter((o) -> o.continentNoiseGain),
    		Codec.FLOAT.optionalFieldOf("continentNoiseLacunarity", 4.33F).forGetter((o) -> o.continentNoiseLacunarity)
    	).apply(instance, Continent::new));
    	
        public ContinentType continentType;
        public DistanceFunction continentShape;
        public int continentScale;
        public float continentJitter;
        public float continentSkipping;
        public float continentSizeVariance;
        public int continentNoiseOctaves;
        public float continentNoiseGain;
        public float continentNoiseLacunarity;
        
        public Continent(ContinentType continentType, DistanceFunction continentShape, int continentScale, float continentJitter, float continentSkipping, float continentSizeVariance, int continentNoiseOctaves, float continentNoiseGain, float continentNoiseLacunarity) {
            this.continentType = continentType;
            this.continentShape = continentShape;
            this.continentScale = continentScale;
            this.continentJitter = continentJitter;
            this.continentSkipping = continentSkipping;
            this.continentSizeVariance = continentSizeVariance;
            this.continentNoiseOctaves = continentNoiseOctaves;
            this.continentNoiseGain = continentNoiseGain;
            this.continentNoiseLacunarity = continentNoiseLacunarity;
        }
        
        public Continent copy() {
        	return new Continent(this.continentType, this.continentShape, this.continentScale, this.continentJitter, this.continentSkipping, this.continentSizeVariance, this.continentNoiseOctaves, this.continentNoiseGain, this.continentNoiseLacunarity);
        }
    }
    
    public static class ControlPoints {
    	public static final Codec<ControlPoints> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("islandInland").xmap(opt -> opt.orElse(0.0F), Optional::of).forGetter((o) -> o.islandInland),
            Codec.FLOAT.optionalFieldOf("islandCoast").xmap(opt -> opt.orElse(0.074F), Optional::of).forGetter((o) -> o.islandCoast),
    		Codec.FLOAT.fieldOf("deepOcean").forGetter((o) -> o.deepOcean),
    		Codec.FLOAT.fieldOf("shallowOcean").forGetter((o) -> o.shallowOcean),
    		Codec.FLOAT.fieldOf("beach").forGetter((o) -> o.beach),
    		Codec.FLOAT.fieldOf("coast").forGetter((o) -> o.coast),
    		Codec.FLOAT.fieldOf("inland").forGetter((o) -> o.inland)
        ).apply(instance, ControlPoints::new));

    	public float islandInland;
    	public float islandCoast;
        public float deepOcean;
        public float shallowOcean;
        public float beach;
        public float coast;
        public float inland;
        
        public ControlPoints(float islandInland, float islandCoast, float deepOcean, float shallowOcean, float beach, float coast, float inland) {
        	this.islandInland = islandInland;
        	this.islandCoast = islandCoast;
            this.deepOcean = deepOcean;
            this.shallowOcean = shallowOcean;
            this.beach = beach;
            this.coast = coast;
            this.inland = inland;
        }
        
        public float coastMarker() {
        	return this.coast + (this.inland - this.coast) / 2.0F;
        }
        
        public ControlPoints copy() {
        	return new ControlPoints(this.islandInland, this.islandCoast, this.deepOcean, this.shallowOcean, this.beach, this.coast, this.inland);
        }
    }
    
    public static class Properties {
        public static final int MAX_TERRAIN_MODEL_HEIGHT = 256;
        public static final double TALL_TERRAIN_SHOULDER_HEIGHT = 1.3D;
        public static final double TALL_TERRAIN_SHOULDER_FRACTION = 0.6D;
    	public static final Codec<Properties> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		SpawnType.CODEC.fieldOf("spawnType").forGetter((o) -> o.spawnType),
    		Codec.INT.fieldOf("worldHeight").forGetter((o) -> o.worldHeight),
    		Codec.INT.optionalFieldOf("worldDepth", 64).forGetter((o) -> o.worldDepth),
    		Codec.INT.fieldOf("seaLevel").forGetter((o) -> o.seaLevel),
    		Codec.INT.optionalFieldOf("lavaLevel", -54).forGetter((o) -> o.lavaLevel),
            Codec.INT.optionalFieldOf("spawnX", 0).forGetter((o) -> o.spawnX),
            Codec.INT.optionalFieldOf("spawnZ", 0).forGetter((o) -> o.spawnZ)
    	).apply(instance, Properties::new));
    	
        public static SpawnType spawnType;
        public int worldHeight;
        public int worldDepth;
        public int seaLevel;
        public int lavaLevel;
        public static int spawnX;
        public static int spawnZ;
        
        public Properties(SpawnType spawnType, int worldHeight, int worldDepth, int seaLevel, int lavaLevel, int spawnX, int spawnZ) {
        	this.spawnType = spawnType;
        	this.worldHeight = worldHeight;
        	this.worldDepth = worldDepth;
        	this.seaLevel = seaLevel;
        	this.lavaLevel = lavaLevel;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
        }
        
        public Properties copy() {
        	return new Properties(this.spawnType, this.worldHeight, this.worldDepth, this.seaLevel, this.lavaLevel, this.spawnX, this.spawnZ);
        }
        
        public int terrainModelHeight() {
            return Math.min(this.worldHeight, MAX_TERRAIN_MODEL_HEIGHT);
        }

        public float terrainHeightRatio() {
            int terrainModelHeight = this.terrainModelHeight();
            return this.worldHeight > terrainModelHeight ? (float) this.worldHeight / terrainModelHeight : 1.0F;
        }

        public float tallTerrainHorizontalScale() {
            float heightRatio = this.terrainHeightRatio();
            if (heightRatio <= 1.0F) {
                return 1.0F;
            }

            double shoulderRange = TALL_TERRAIN_SHOULDER_HEIGHT - 1.0D;
            double bodyProjectionRange = (heightRatio - 1.0D) * TALL_TERRAIN_SHOULDER_FRACTION;
            double bodyProjectionSlope = bodyProjectionRange / shoulderRange;
            return (float) Math.max(heightRatio, bodyProjectionSlope);
        }

        @Deprecated
        public int terrainScaler() {
            return this.terrainModelHeight();
        }
    }

    public static class Ocean {
        public static final Codec<Ocean> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("shallowOceanDepth", 7).forGetter((o) -> o.shallowOceanDepth),
            Codec.INT.optionalFieldOf("deepOceanMinDepth", 32).forGetter((o) -> o.deepOceanMinDepth),
            Codec.INT.optionalFieldOf("deepOceanMaxDepth", 96).forGetter((o) -> o.deepOceanMaxDepth),
            Codec.INT.optionalFieldOf("oceanDepthNoiseScale", 150).forGetter((o) -> o.oceanDepthNoiseScale)
        ).apply(instance, Ocean::new));

        public int shallowOceanDepth;
        public int deepOceanMinDepth;
        public int deepOceanMaxDepth;
        public int oceanDepthNoiseScale;

        public Ocean(int shallowOceanDepth, int deepOceanMinDepth, int deepOceanMaxDepth, int oceanDepthNoiseScale) {
            this.shallowOceanDepth = shallowOceanDepth;
            this.deepOceanMinDepth = deepOceanMinDepth;
            this.deepOceanMaxDepth = deepOceanMaxDepth;
            this.oceanDepthNoiseScale = oceanDepthNoiseScale;
        }

        public Ocean copy() {
            return new Ocean(this.shallowOceanDepth, this.deepOceanMinDepth, this.deepOceanMaxDepth, this.oceanDepthNoiseScale);
        }
    }
}
