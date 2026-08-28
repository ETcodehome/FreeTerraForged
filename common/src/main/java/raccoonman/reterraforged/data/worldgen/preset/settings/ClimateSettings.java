package raccoonman.reterraforged.data.worldgen.preset.settings;

import java.util.Optional;
import java.util.function.BiFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public class ClimateSettings {
	public static final Codec<ClimateSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		RangeValue.CODEC.fieldOf("temperature").forGetter((o) -> o.temperature),
		RangeValue.CODEC.fieldOf("moisture").forGetter((o) -> o.moisture),
		BiomeShape.CODEC.fieldOf("biomeShape").forGetter((o) -> o.biomeShape),
		BiomeNoise.CODEC.fieldOf("biomeEdgeShape").forGetter((o) -> o.biomeEdgeShape)
	).apply(instance, ClimateSettings::new));
	
	public RangeValue temperature;
    public RangeValue moisture;
    public BiomeShape biomeShape;
    public BiomeNoise biomeEdgeShape;
    
    public ClimateSettings(RangeValue temperature, RangeValue moisture, BiomeShape biomeShape, BiomeNoise biomeEdgeShape) {
    	this.temperature = temperature;
    	this.moisture = moisture;
    	this.biomeShape = biomeShape;
    	this.biomeEdgeShape = biomeEdgeShape;
    }
    
    public ClimateSettings copy() {
    	return new ClimateSettings(this.temperature.copy(), this.moisture.copy(), this.biomeShape.copy(), this.biomeEdgeShape.copy());
    }
    
    public static class RangeValue {
    	public static final Codec<RangeValue> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		Codec.INT.fieldOf("seedOffset").forGetter((o) -> o.seedOffset),
    		Codec.INT.fieldOf("scale").forGetter((o) -> o.scale),
    		Codec.INT.fieldOf("falloff").forGetter((o) -> o.falloff),
    		Codec.FLOAT.fieldOf("min").forGetter((o) -> o.min),
    		Codec.FLOAT.fieldOf("max").forGetter((o) -> o.max),
    		Codec.FLOAT.fieldOf("bias").forGetter((o) -> o.bias)
    	).apply(instance, RangeValue::new));
    	
        public int seedOffset;
        public int scale;
        public int falloff;
        public float min;
        public float max;
        public float bias;
        
        public RangeValue(int seedOffset, int scale, int falloff, float min, float max, float bias) {
            this.seedOffset = seedOffset;
            this.min = min;
            this.max = max;
            this.bias = bias;
            this.scale = scale;
            this.falloff = falloff;
        }
        
        public float getMin() {
            return NoiseUtil.clamp(Math.min(this.min, this.max), 0.0F, 1.0F);
        }
        
        public float getMax() {
            return NoiseUtil.clamp(Math.max(this.min, this.max), this.getMin(), 1.0F);
        }
        
        public float getBias() {
            return NoiseUtil.clamp(this.bias, -1.0F, 1.0F);
        }
        
        public Noise apply(Noise module) {
            float min = this.getMin();
            float max = this.getMax();
            float bias = this.getBias() / 2.0F;
            module = Noises.add(module, bias);
            module = Noises.clamp(module, min, max);
            return module;
        }
        
        public RangeValue copy() {
        	return new RangeValue(this.seedOffset, this.scale, this.falloff, this.min, this.max, this.bias);
        }
    }
    
	public static class BiomeShape {
		public static final int MIN_BIOME_SIZE = 50;
		public static final int MAX_BIOME_SIZE = 2000;
		public static final int MIN_UNDERGROUND_VERTICAL_SIZE = 16;
		public static final int MAX_UNDERGROUND_VERTICAL_SIZE = 2048;
		public static final int DEFAULT_UNDERGROUND_VERTICAL_SIZE = 64;
		public static final float DEFAULT_UNDERGROUND_BIOME_COVERAGE = 0.25F;
		public static final float DEFAULT_UNDERGROUND_BIOME_CLIMATE_INFLUENCE = 0.75F;
		private static final Codec<Integer> BIOME_SIZE_CODEC = Codec.intRange(MIN_BIOME_SIZE, MAX_BIOME_SIZE);
		private static final Codec<Integer> UNDERGROUND_VERTICAL_SIZE_CODEC = Codec.intRange(
			MIN_UNDERGROUND_VERTICAL_SIZE,
			MAX_UNDERGROUND_VERTICAL_SIZE
		);
		private static final Codec<Float> UNIT_FLOAT_CODEC = Codec.floatRange(0.0F, 1.0F);

    	public static final Codec<BiomeShape> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BIOME_SIZE_CODEC.fieldOf("biomeSize").forGetter((o) -> o.biomeSize),
			BIOME_SIZE_CODEC.optionalFieldOf("undergroundBiomeSize").forGetter((o) -> Optional.of(o.undergroundBiomeSize)),
			UNDERGROUND_VERTICAL_SIZE_CODEC.optionalFieldOf("undergroundBiomeVerticalSize", DEFAULT_UNDERGROUND_VERTICAL_SIZE).forGetter((o) -> o.undergroundBiomeVerticalSize),
			UNIT_FLOAT_CODEC.optionalFieldOf("undergroundBiomeCoverage", DEFAULT_UNDERGROUND_BIOME_COVERAGE).forGetter((o) -> o.undergroundBiomeCoverage),
			UNIT_FLOAT_CODEC.optionalFieldOf("undergroundBiomeClimateInfluence", DEFAULT_UNDERGROUND_BIOME_CLIMATE_INFLUENCE).forGetter((o) -> o.undergroundBiomeClimateInfluence),
			Codec.BOOL.optionalFieldOf("undergroundBiomeBanding", true).forGetter((o) -> o.undergroundBiomeBanding),
    		Codec.INT.fieldOf("macroNoiseSize").forGetter((o) -> o.macroNoiseSize),
    		Codec.INT.fieldOf("biomeWarpScale").forGetter((o) -> o.biomeWarpScale),
    		Codec.INT.fieldOf("biomeWarpStrength").forGetter((o) -> o.biomeWarpStrength)    		
		).apply(instance, (biomeSize, undergroundBiomeSize, undergroundBiomeVerticalSize, undergroundBiomeCoverage, undergroundBiomeClimateInfluence, undergroundBiomeBanding, macroNoiseSize, biomeWarpScale, biomeWarpStrength) ->
			new BiomeShape(
				biomeSize,
				undergroundBiomeSize.orElse(biomeSize),
				undergroundBiomeVerticalSize,
				undergroundBiomeCoverage,
				undergroundBiomeClimateInfluence,
				undergroundBiomeBanding,
				macroNoiseSize,
				biomeWarpScale,
				biomeWarpStrength
			)
		));
    	
        public int biomeSize;
		public int undergroundBiomeSize;
		public int undergroundBiomeVerticalSize;
		public float undergroundBiomeCoverage;
		public float undergroundBiomeClimateInfluence;
		public boolean undergroundBiomeBanding;
        public int macroNoiseSize;
        public int biomeWarpScale;
        public int biomeWarpStrength;
        
        public BiomeShape(int biomeSize, int macroNoiseSize, int biomeWarpScale, int biomeWarpStrength) {
			this(
				biomeSize,
				biomeSize,
				DEFAULT_UNDERGROUND_VERTICAL_SIZE,
				DEFAULT_UNDERGROUND_BIOME_COVERAGE,
				DEFAULT_UNDERGROUND_BIOME_CLIMATE_INFLUENCE,
				true,
				macroNoiseSize,
				biomeWarpScale,
				biomeWarpStrength
			);
		}

		public BiomeShape(int biomeSize, int undergroundBiomeSize, int macroNoiseSize, int biomeWarpScale, int biomeWarpStrength) {
			this(
				biomeSize,
				undergroundBiomeSize,
				DEFAULT_UNDERGROUND_VERTICAL_SIZE,
				DEFAULT_UNDERGROUND_BIOME_COVERAGE,
				DEFAULT_UNDERGROUND_BIOME_CLIMATE_INFLUENCE,
				true,
				macroNoiseSize,
				biomeWarpScale,
				biomeWarpStrength
			);
		}

		public BiomeShape(
			int biomeSize,
			int undergroundBiomeSize,
			int undergroundBiomeVerticalSize,
			float undergroundBiomeCoverage,
			float undergroundBiomeClimateInfluence,
			boolean undergroundBiomeBanding,
			int macroNoiseSize,
			int biomeWarpScale,
			int biomeWarpStrength
		) {
			this.biomeSize = validateBiomeSize(biomeSize);
			this.undergroundBiomeSize = validateBiomeSize(undergroundBiomeSize);
			this.undergroundBiomeVerticalSize = validateUndergroundVerticalSize(undergroundBiomeVerticalSize);
			this.undergroundBiomeCoverage = validateUnitFloat("Underground biome coverage", undergroundBiomeCoverage);
			this.undergroundBiomeClimateInfluence = validateUnitFloat(
				"Underground biome climate influence",
				undergroundBiomeClimateInfluence
			);
			this.undergroundBiomeBanding = undergroundBiomeBanding;
        	this.macroNoiseSize = macroNoiseSize;
        	this.biomeWarpScale = biomeWarpScale;
        	this.biomeWarpStrength = biomeWarpStrength;
        }

		public int biomeSize() {
			return validateBiomeSize(this.biomeSize);
		}

		public int undergroundBiomeSize() {
			return validateBiomeSize(this.undergroundBiomeSize);
		}

		public int undergroundBiomeVerticalSize() {
			return validateUndergroundVerticalSize(this.undergroundBiomeVerticalSize);
		}

		public int undergroundBiomeVerticalSize(int worldHeight, int worldDepth) {
			return Math.min(
				this.undergroundBiomeVerticalSize(),
				maximumUndergroundVerticalSize(worldHeight, worldDepth)
			);
		}

		public float undergroundBiomeCoverage() {
			return validateUnitFloat("Underground biome coverage", this.undergroundBiomeCoverage);
		}

		public float undergroundBiomeClimateInfluence() {
			return validateUnitFloat("Underground biome climate influence", this.undergroundBiomeClimateInfluence);
		}

		private static int validateBiomeSize(int biomeSize) {
			if (biomeSize < MIN_BIOME_SIZE || biomeSize > MAX_BIOME_SIZE) {
				throw new IllegalArgumentException(
					"Biome size must be between " + MIN_BIOME_SIZE + " and " + MAX_BIOME_SIZE + ": " + biomeSize
				);
			}
			return biomeSize;
		}

		private static int validateUndergroundVerticalSize(int size) {
			if (size < MIN_UNDERGROUND_VERTICAL_SIZE || size > MAX_UNDERGROUND_VERTICAL_SIZE) {
				throw new IllegalArgumentException(
					"Underground biome vertical size must be between " + MIN_UNDERGROUND_VERTICAL_SIZE
						+ " and " + MAX_UNDERGROUND_VERTICAL_SIZE + ": " + size
				);
			}
			return size;
		}

		public static int maximumUndergroundVerticalSize(int worldHeight, int worldDepth) {
			long worldSpan = (long) Math.max(0, worldHeight) + Math.max(0, worldDepth);
			return (int) Math.max(
				MIN_UNDERGROUND_VERTICAL_SIZE,
				Math.min(MAX_UNDERGROUND_VERTICAL_SIZE, worldSpan)
			);
		}

		private static float validateUnitFloat(String name, float value) {
			if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
				throw new IllegalArgumentException(name + " must be between 0 and 1: " + value);
			}
			return value;
		}
        
        public BiomeShape copy() {
			return new BiomeShape(
				this.biomeSize,
				this.undergroundBiomeSize,
				this.undergroundBiomeVerticalSize,
				this.undergroundBiomeCoverage,
				this.undergroundBiomeClimateInfluence,
				this.undergroundBiomeBanding,
				this.macroNoiseSize,
				this.biomeWarpScale,
				this.biomeWarpStrength
			);
        }
    }
    
    public static class BiomeNoise {
    	public static final Codec<BiomeNoise> CODEC = RecordCodecBuilder.create(instance -> instance.group(
    		EdgeType.CODEC.fieldOf("type").forGetter((o) -> o.type),
    		Codec.INT.fieldOf("scale").forGetter((o) -> o.scale),
    		Codec.INT.fieldOf("octaves").forGetter((o) -> o.octaves),
    		Codec.FLOAT.fieldOf("gain").forGetter((o) -> o.gain),
    		Codec.FLOAT.fieldOf("lacunarity").forGetter((o) -> o.lacunarity),
    		Codec.INT.fieldOf("strength").forGetter((o) -> o.strength)
    	).apply(instance, BiomeNoise::new));
    	
        public EdgeType type;
        public int scale;
        public int octaves;
        public float gain;
        public float lacunarity;
        public int strength;

        public BiomeNoise(EdgeType type, int scale, int octaves, float gain, float lacunarity, int strength) {
            this.type = type;
            this.scale = scale;
            this.octaves = octaves;
            this.gain = gain;
            this.lacunarity = lacunarity;
            this.strength = strength;
        }
        
        public Noise build(int seed) {
            return Noises.add(this.type.factory.apply(seed, this), Noises.constant(-0.5F));
        }
        
        public BiomeNoise copy() {
        	return new BiomeNoise(this.type, this.scale, this.octaves, this.gain, this.lacunarity, this.strength);
        }
        
        public enum EdgeType implements StringRepresentable {
        	BILLOW("BILLOW", (seed, settings) -> Noises.billow(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	VORONOI("CELL", (seed, settings) -> Noises.worley(seed, settings.scale)),
        	VORONOI_EDGE("CELL_EDGE", (seed, settings) -> Noises.worleyEdge(seed, settings.scale)),
        	CONSTANT("CONST", (seed, settings) -> Noises.one()),
        	CUBIC("CUBIC", (seed, settings) -> Noises.cubic(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	PERLIN("PERLIN", (seed, settings) -> Noises.perlin(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	PERLIN2("PERLIN2", (seed, settings) -> Noises.perlin2(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	PERLIN_RIDGE("RIDGE", (seed, settings) -> Noises.perlinRidge(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	SIMPLEX("SIMPLEX", (seed, settings) -> Noises.simplex(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	SIMPLEX2("SIMPLEX2", (seed, settings) -> Noises.simplex2(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	
        	// calling simplex2 isn't a mistake, this is for backwards compatibility
        	SIMPLEX_RIDGE("SIMPLEX_RIDGE", (seed, settings) -> Noises.simplex2(seed, settings.scale, settings.octaves, settings.lacunarity, settings.gain)),
        	SIN("SIN", (seed, settings) -> Noises.sin(seed, 1.0F, Noises.zero())),
        	WHITE("RAND", (seed, settings) -> Noises.white(seed, 1));

        	public static final Codec<EdgeType> CODEC = StringRepresentable.fromEnum(EdgeType::values);
        	
        	private String name;
        	private BiFunction<Integer, BiomeNoise, Noise> factory;
        	
        	private EdgeType(String name, BiFunction<Integer, BiomeNoise, Noise> factory) {
        		this.name = name;
        		this.factory = factory;
        	}

			@Override
			public String getSerializedName() {
				return this.name;
			}
        	
        	public Noise build(int seed, BiomeNoise settings) {
        		return this.factory.apply(seed, settings);
        	}
        }
    }
}
