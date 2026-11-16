package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.data.worldgen.preset.PresetManager.PM;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings;

import java.util.*;
import java.util.function.*;

public final class PresetTokens {

    public interface SettingsToken {
        String name();
        String typeName();
        String get();
        void set(String value) throws IllegalArgumentException;
        List<String> suggestions();
    }

    private static final Map<String, SettingsToken> TOKENS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private static void register(SettingsToken token) {
        TOKENS.put(token.name(), token);
    }

    private static void registerFloat(String name, Supplier<Float> getter, Consumer<Float> setter) {
        register(new SettingsToken() {
            @Override
            public String name() { return name; }

            @Override
            public String typeName() { return "float"; }

            @Override
            public String get() {
                Float val = getter.get();
                return val != null ? String.valueOf(val) : "0.0";
            }

            @Override
            public void set(String value) {
                try {
                    float f = Float.parseFloat(value.trim());
                    setter.accept(f);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Expected float number, got: " + value);
                }
            }

            @Override
            public List<String> suggestions() {
                return List.of(get());
            }
        });
    }

    private static void registerInt(String name, Supplier<Integer> getter, Consumer<Integer> setter) {
        register(new SettingsToken() {
            @Override
            public String name() { return name; }

            @Override
            public String typeName() { return "int"; }

            @Override
            public String get() {
                Integer val = getter.get();
                return val != null ? String.valueOf(val) : "0";
            }

            @Override
            public void set(String value) {
                try {
                    int i = Integer.parseInt(value.trim());
                    setter.accept(i);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Expected integer number, got: " + value);
                }
            }

            @Override
            public List<String> suggestions() {
                return List.of(get());
            }
        });
    }

    private static void registerBoolean(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        register(new SettingsToken() {
            @Override
            public String name() { return name; }

            @Override
            public String typeName() { return "boolean"; }

            @Override
            public String get() {
                Boolean val = getter.get();
                return val != null ? String.valueOf(val) : "false";
            }

            @Override
            public void set(String value) {
                String trimmed = value.trim().toLowerCase(Locale.ROOT);
                if (trimmed.equals("true") || trimmed.equals("1") || trimmed.equals("yes") || trimmed.equals("on")) {
                    setter.accept(true);
                } else if (trimmed.equals("false") || trimmed.equals("0") || trimmed.equals("no") || trimmed.equals("off")) {
                    setter.accept(false);
                } else {
                    throw new IllegalArgumentException("Expected boolean (true/false), got: " + value);
                }
            }

            @Override
            public List<String> suggestions() {
                return List.of("true", "false");
            }
        });
    }

    static {
        // --- Islands (islands.*) ---
        registerBoolean("islands.spawn", () -> PM.islandSettings.spawnIslands, v -> PM.islandSettings.spawnIslands = v);
        registerBoolean("islands.spawnIslands", () -> PM.islandSettings.spawnIslands, v -> PM.islandSettings.spawnIslands = v);
        registerFloat("islands.density", () -> PM.islandSettings.density, v -> PM.islandSettings.density = v);
        registerFloat("islands.size", () -> PM.islandSettings.size, v -> PM.islandSettings.size = v);
        registerFloat("islands.height", () -> PM.islandSettings.height, v -> PM.islandSettings.height = v);
        registerFloat("islands.baseScale", () -> PM.islandSettings.baseScale, v -> PM.islandSettings.baseScale = v);
        registerFloat("islands.verticalScale", () -> PM.islandSettings.verticalScale, v -> PM.islandSettings.verticalScale = v);
        registerFloat("islands.horizontalScale", () -> PM.islandSettings.horizontalScale, v -> PM.islandSettings.horizontalScale = v);
        registerFloat("islands.mountainChance", () -> PM.islandSettings.mountainChance, v -> PM.islandSettings.mountainChance = v);
        registerFloat("islands.mountainScale", () -> PM.islandSettings.mountainScale, v -> PM.islandSettings.mountainScale = v);
        registerFloat("islands.volcanoChance", () -> PM.islandSettings.volcanoChance, v -> PM.islandSettings.volcanoChance = v);
        registerFloat("islands.VolcanoChance", () -> PM.islandSettings.volcanoChance, v -> PM.islandSettings.volcanoChance = v);
        registerFloat("islands.volcanismScale", () -> PM.islandSettings.volcanismScale, v -> PM.islandSettings.volcanismScale = v);
        registerFloat("islands.mountainHorizontalScale", () -> PM.islandSettings.mountainHorizontalScale, v -> PM.islandSettings.mountainHorizontalScale = v);
        registerFloat("islands.volcanismHorizontalScale", () -> PM.islandSettings.volcanismHorizontalScale, v -> PM.islandSettings.volcanismHorizontalScale = v);
        registerFloat("islands.offshoreDepth", () -> PM.islandSettings.offshoreDepth, v -> PM.islandSettings.offshoreDepth = v);
        registerFloat("islands.beachWidth", () -> PM.islandSettings.beachWidth, v -> PM.islandSettings.beachWidth = v);
        registerFloat("islands.beachCoverage", () -> PM.islandSettings.beachCoverage, v -> PM.islandSettings.beachCoverage = v);
        registerFloat("islands.macroDensityPercentage", () -> PM.islandSettings.macroDensityPercentage, v -> PM.islandSettings.macroDensityPercentage = v);

        // --- Erosion Filters (erosionFilter.*) ---
        registerFloat("erosionFilter.scale", () -> PM.erosionFilterSettings.scale, v -> PM.erosionFilterSettings.scale = v);
        registerFloat("erosionFilter.strengthMultiplier", () -> PM.erosionFilterSettings.strengthMultiplier, v -> PM.erosionFilterSettings.strengthMultiplier = v);
        registerFloat("erosionFilter.gullySharpness", () -> PM.erosionFilterSettings.gullySharpness, v -> PM.erosionFilterSettings.gullySharpness = v);
        registerInt("erosionFilter.gullyFlowOctaves", () -> PM.erosionFilterSettings.flowOctaves, v -> PM.erosionFilterSettings.flowOctaves = v);
        registerInt("erosionFilter.flowOctaves", () -> PM.erosionFilterSettings.flowOctaves, v -> PM.erosionFilterSettings.flowOctaves = v);
        registerFloat("erosionFilter.lacunarity", () -> PM.erosionFilterSettings.lacunarity, v -> PM.erosionFilterSettings.lacunarity = v);
        registerFloat("erosionFilter.gain", () -> PM.erosionFilterSettings.gain, v -> PM.erosionFilterSettings.gain = v);
        registerFloat("erosionFilter.phacelleScale", () -> PM.erosionFilterSettings.phacelleScale, v -> PM.erosionFilterSettings.phacelleScale = v);
        registerFloat("erosionFilter.phacelleOffset", () -> PM.erosionFilterSettings.phacelleOffset, v -> PM.erosionFilterSettings.phacelleOffset = v);
        registerFloat("erosionFilter.phacelleNormalization", () -> PM.erosionFilterSettings.phacelleNormalization, v -> PM.erosionFilterSettings.phacelleNormalization = v);
        registerFloat("erosionFilter.slopeOnsetBase", () -> PM.erosionFilterSettings.slopeOnsetBase, v -> PM.erosionFilterSettings.slopeOnsetBase = v);
        registerFloat("erosionFilter.slopeOnsetRidge", () -> PM.erosionFilterSettings.slopeOnsetRidge, v -> PM.erosionFilterSettings.slopeOnsetRidge = v);
        registerFloat("erosionFilter.slopeOnsetOctave", () -> PM.erosionFilterSettings.slopeOnsetOctave, v -> PM.erosionFilterSettings.slopeOnsetOctave = v);
        registerFloat("erosionFilter.roundingMin", () -> PM.erosionFilterSettings.roundingMin, v -> PM.erosionFilterSettings.roundingMin = v);
        registerFloat("erosionFilter.roundingMax", () -> PM.erosionFilterSettings.roundingMax, v -> PM.erosionFilterSettings.roundingMax = v);

        // --- Continent Settings (continent.*) ---
        registerFloat("continent.bridgeChance", () -> PM.worldSettings.continent.bridgeChance, v -> PM.worldSettings.continent.bridgeChance = v);
        registerFloat("continent.tectonicPlateUplift", () -> PM.worldSettings.continent.tectonicPlateUplift, v -> PM.worldSettings.continent.tectonicPlateUplift = v);
        registerFloat("continent.tectonicRounding", () -> PM.worldSettings.continent.tectonicRounding, v -> PM.worldSettings.continent.tectonicRounding = v);
        registerFloat("continent.macroWarpStrength", () -> PM.worldSettings.continent.macroWarpStrength, v -> PM.worldSettings.continent.macroWarpStrength = v);
        registerFloat("continent.coastalComplexity", () -> PM.worldSettings.continent.coastalComplexity, v -> PM.worldSettings.continent.coastalComplexity = v);
        registerInt("continent.scale", () -> PM.worldSettings.continent.continentScale, v -> PM.worldSettings.continent.continentScale = v);
        registerInt("continent.continentScale", () -> PM.worldSettings.continent.continentScale, v -> PM.worldSettings.continent.continentScale = v);
        registerFloat("continent.jitter", () -> PM.worldSettings.continent.continentJitter, v -> PM.worldSettings.continent.continentJitter = v);
        registerFloat("continent.continentJitter", () -> PM.worldSettings.continent.continentJitter, v -> PM.worldSettings.continent.continentJitter = v);
        registerFloat("continent.skipping", () -> PM.worldSettings.continent.continentSkipping, v -> PM.worldSettings.continent.continentSkipping = v);
        registerFloat("continent.continentSkipping", () -> PM.worldSettings.continent.continentSkipping, v -> PM.worldSettings.continent.continentSkipping = v);
        registerFloat("continent.sizeVariance", () -> PM.worldSettings.continent.continentSizeVariance, v -> PM.worldSettings.continent.continentSizeVariance = v);
        registerFloat("continent.continentSizeVariance", () -> PM.worldSettings.continent.continentSizeVariance, v -> PM.worldSettings.continent.continentSizeVariance = v);
        registerInt("continent.noiseOctaves", () -> PM.worldSettings.continent.continentNoiseOctaves, v -> PM.worldSettings.continent.continentNoiseOctaves = v);
        registerInt("continent.continentNoiseOctaves", () -> PM.worldSettings.continent.continentNoiseOctaves, v -> PM.worldSettings.continent.continentNoiseOctaves = v);
        registerFloat("continent.noiseGain", () -> PM.worldSettings.continent.continentNoiseGain, v -> PM.worldSettings.continent.continentNoiseGain = v);
        registerFloat("continent.continentNoiseGain", () -> PM.worldSettings.continent.continentNoiseGain, v -> PM.worldSettings.continent.continentNoiseGain = v);
        registerFloat("continent.noiseLacunarity", () -> PM.worldSettings.continent.continentNoiseLacunarity, v -> PM.worldSettings.continent.continentNoiseLacunarity = v);
        registerFloat("continent.continentNoiseLacunarity", () -> PM.worldSettings.continent.continentNoiseLacunarity, v -> PM.worldSettings.continent.continentNoiseLacunarity = v);

        // --- Control Points (controlPoints.*) ---
        registerFloat("controlPoints.islandInland", () -> PM.worldSettings.controlPoints.islandInland, v -> PM.worldSettings.controlPoints.islandInland = v);
        registerFloat("controlPoints.islandCoast", () -> PM.worldSettings.controlPoints.islandCoast, v -> PM.worldSettings.controlPoints.islandCoast = v);
        registerFloat("controlPoints.deepOcean", () -> PM.worldSettings.controlPoints.deepOcean, v -> PM.worldSettings.controlPoints.deepOcean = v);
        registerFloat("controlPoints.shallowOcean", () -> PM.worldSettings.controlPoints.shallowOcean, v -> PM.worldSettings.controlPoints.shallowOcean = v);
        registerFloat("controlPoints.beach", () -> PM.worldSettings.controlPoints.beach, v -> PM.worldSettings.controlPoints.beach = v);
        registerFloat("controlPoints.coast", () -> PM.worldSettings.controlPoints.coast, v -> PM.worldSettings.controlPoints.coast = v);
        registerFloat("controlPoints.inland", () -> PM.worldSettings.controlPoints.inland, v -> PM.worldSettings.controlPoints.inland = v);

        // --- Properties (properties.*) ---
        registerInt("properties.worldHeight", () -> PM.worldSettings.properties.worldHeight, v -> PM.worldSettings.properties.worldHeight = v);
        registerInt("properties.worldDepth", () -> PM.worldSettings.properties.worldDepth, v -> PM.worldSettings.properties.worldDepth = v);
        registerInt("properties.seaLevel", () -> PM.worldSettings.properties.seaLevel, v -> PM.worldSettings.properties.seaLevel = v);
        registerInt("properties.lavaLevel", () -> PM.worldSettings.properties.lavaLevel, v -> PM.worldSettings.properties.lavaLevel = v);
        registerInt("properties.oceanDepth", () -> PM.worldSettings.properties.oceanDepth, v -> PM.worldSettings.properties.oceanDepth = v);

        // --- Climate Settings (climate.*) ---
        registerFloat("climate.altitudeCoolingStrength", () -> PM.climateSettings.altitudeCoolingStrength, v -> PM.climateSettings.altitudeCoolingStrength = v);
        registerFloat("climate.rainShadowStrength", () -> PM.climateSettings.rainShadowStrength, v -> PM.climateSettings.rainShadowStrength = v);
        registerFloat("climate.coastalMoistureBoost", () -> PM.climateSettings.coastalMoistureBoost, v -> PM.climateSettings.coastalMoistureBoost = v);
        registerInt("climate.temperature.scale", () -> PM.climateSettings.temperature.scale, v -> PM.climateSettings.temperature.scale = v);
        registerInt("climate.temperature.falloff", () -> PM.climateSettings.temperature.falloff, v -> PM.climateSettings.temperature.falloff = v);
        registerFloat("climate.temperature.min", () -> PM.climateSettings.temperature.min, v -> PM.climateSettings.temperature.min = v);
        registerFloat("climate.temperature.max", () -> PM.climateSettings.temperature.max, v -> PM.climateSettings.temperature.max = v);
        registerFloat("climate.temperature.bias", () -> PM.climateSettings.temperature.offsetBias, v -> PM.climateSettings.temperature.offsetBias = v);
        registerFloat("climate.temperature.offsetBias", () -> PM.climateSettings.temperature.offsetBias, v -> PM.climateSettings.temperature.offsetBias = v);
        registerInt("climate.moisture.scale", () -> PM.climateSettings.moisture.scale, v -> PM.climateSettings.moisture.scale = v);
        registerInt("climate.moisture.falloff", () -> PM.climateSettings.moisture.falloff, v -> PM.climateSettings.moisture.falloff = v);
        registerFloat("climate.moisture.min", () -> PM.climateSettings.moisture.min, v -> PM.climateSettings.moisture.min = v);
        registerFloat("climate.moisture.max", () -> PM.climateSettings.moisture.max, v -> PM.climateSettings.moisture.max = v);
        registerFloat("climate.moisture.bias", () -> PM.climateSettings.moisture.offsetBias, v -> PM.climateSettings.moisture.offsetBias = v);
        registerFloat("climate.moisture.offsetBias", () -> PM.climateSettings.moisture.offsetBias, v -> PM.climateSettings.moisture.offsetBias = v);
        registerInt("climate.erosion.scale", () -> PM.climateSettings.erosion.scale, v -> PM.climateSettings.erosion.scale = v);
        registerInt("climate.erosion.falloff", () -> PM.climateSettings.erosion.falloff, v -> PM.climateSettings.erosion.falloff = v);
        registerFloat("climate.erosion.min", () -> PM.climateSettings.erosion.min, v -> PM.climateSettings.erosion.min = v);
        registerFloat("climate.erosion.max", () -> PM.climateSettings.erosion.max, v -> PM.climateSettings.erosion.max = v);
        registerFloat("climate.erosion.bias", () -> PM.climateSettings.erosion.offsetBias, v -> PM.climateSettings.erosion.offsetBias = v);
        registerFloat("climate.erosion.offsetBias", () -> PM.climateSettings.erosion.offsetBias, v -> PM.climateSettings.erosion.offsetBias = v);
        registerInt("climate.biomeShape.biomeSize", () -> PM.climateSettings.biomeShape.biomeSize, v -> PM.climateSettings.biomeShape.biomeSize = v);
        registerInt("climate.biomeShape.undergroundBiomeSize", () -> PM.climateSettings.biomeShape.undergroundBiomeSize, v -> PM.climateSettings.biomeShape.undergroundBiomeSize = v);
        registerInt("climate.biomeShape.undergroundBiomeVerticalSize", () -> PM.climateSettings.biomeShape.undergroundBiomeVerticalSize, v -> PM.climateSettings.biomeShape.undergroundBiomeVerticalSize = v);
        registerFloat("climate.biomeShape.undergroundBiomeCoverage", () -> PM.climateSettings.biomeShape.undergroundBiomeCoverage, v -> PM.climateSettings.biomeShape.undergroundBiomeCoverage = v);
        registerFloat("climate.biomeShape.undergroundBiomeClimateInfluence", () -> PM.climateSettings.biomeShape.undergroundBiomeClimateInfluence, v -> PM.climateSettings.biomeShape.undergroundBiomeClimateInfluence = v);
        registerBoolean("climate.biomeShape.undergroundBiomeBanding", () -> PM.climateSettings.biomeShape.undergroundBiomeBanding, v -> PM.climateSettings.biomeShape.undergroundBiomeBanding = v);
        registerInt("climate.biomeShape.macroNoiseSize", () -> PM.climateSettings.biomeShape.macroNoiseSize, v -> PM.climateSettings.biomeShape.macroNoiseSize = v);
        registerInt("climate.biomeShape.biomeWarpScale", () -> PM.climateSettings.biomeShape.biomeWarpScale, v -> PM.climateSettings.biomeShape.biomeWarpScale = v);
        registerInt("climate.biomeShape.biomeWarpStrength", () -> PM.climateSettings.biomeShape.biomeWarpStrength, v -> PM.climateSettings.biomeShape.biomeWarpStrength = v);

        // --- Cave Settings (caves.*) ---
        registerFloat("caves.entranceCaveProbability", () -> PM.caveSettings.entranceCaveProbability, v -> PM.caveSettings.entranceCaveProbability = v);
        registerFloat("caves.cheeseCaveDepthOffset", () -> PM.caveSettings.cheeseCaveDepthOffset, v -> PM.caveSettings.cheeseCaveDepthOffset = v);
        registerFloat("caves.cheeseCaveProbability", () -> PM.caveSettings.cheeseCaveProbability, v -> PM.caveSettings.cheeseCaveProbability = v);
        registerFloat("caves.spaghettiCaveProbability", () -> PM.caveSettings.spaghettiCaveProbability, v -> PM.caveSettings.spaghettiCaveProbability = v);
        registerFloat("caves.noodleCaveProbability", () -> PM.caveSettings.noodleCaveProbability, v -> PM.caveSettings.noodleCaveProbability = v);
        registerFloat("caves.caveCarverProbability", () -> PM.caveSettings.caveCarverProbability, v -> PM.caveSettings.caveCarverProbability = v);
        registerFloat("caves.deepCaveCarverProbability", () -> PM.caveSettings.deepCaveCarverProbability, v -> PM.caveSettings.deepCaveCarverProbability = v);
        registerFloat("caves.ravineCarverProbability", () -> PM.caveSettings.ravineCarverProbability, v -> PM.caveSettings.ravineCarverProbability = v);
        registerBoolean("caves.largeOreVeins", () -> PM.caveSettings.largeOreVeins, v -> PM.caveSettings.largeOreVeins = v);
        registerBoolean("caves.legacyCarverDistribution", () -> PM.caveSettings.legacyCarverDistribution, v -> PM.caveSettings.legacyCarverDistribution = v);
        registerInt("climate.biomeEdgeShape.strength", () -> PM.climateSettings.biomeEdgeShape.strength, v -> PM.climateSettings.biomeEdgeShape.strength = v);
        registerInt("climate.biomeEdgeShape.scale", () -> PM.climateSettings.biomeEdgeShape.scale, v -> PM.climateSettings.biomeEdgeShape.scale = v);

        // --- Terrain Settings (terrain.*) ---
        registerInt("terrain.general.terrainSeedOffset", () -> PM.terrainSettings.general.terrainSeedOffset, v -> PM.terrainSettings.general.terrainSeedOffset = v);
        registerInt("terrain.general.terrainRegionSize", () -> PM.terrainSettings.general.terrainRegionSize, v -> PM.terrainSettings.general.terrainRegionSize = v);
        registerFloat("terrain.general.globalVerticalScale", () -> PM.terrainSettings.general.globalVerticalScale, v -> PM.terrainSettings.general.globalVerticalScale = v);
        registerFloat("terrain.general.globalHorizontalScale", () -> PM.terrainSettings.general.globalHorizontalScale, v -> PM.terrainSettings.general.globalHorizontalScale = v);
        registerBoolean("terrain.general.fancyMountains", () -> PM.terrainSettings.general.fancyMountains, v -> PM.terrainSettings.general.fancyMountains = v);
        registerBoolean("terrain.general.legacyMountainScaling", () -> PM.terrainSettings.general.legacyMountainScaling, v -> PM.terrainSettings.general.legacyMountainScaling = v);
        registerFloat("terrain.general.mountainVariety", () -> PM.terrainSettings.general.mountainVariety, v -> PM.terrainSettings.general.mountainVariety = v);

        registerTerrainSubtype("steppe", () -> PM.terrainSettings.steppe);
        registerTerrainSubtype("plains", () -> PM.terrainSettings.plains);
        registerTerrainSubtype("hills", () -> PM.terrainSettings.hills);
        registerTerrainSubtype("dales", () -> PM.terrainSettings.dales);
        registerTerrainSubtype("plateau", () -> PM.terrainSettings.plateau);
        registerTerrainSubtype("badlands", () -> PM.terrainSettings.badlands);
        registerTerrainSubtype("torridonian", () -> PM.terrainSettings.torridonian);
        registerTerrainSubtype("mountains", () -> PM.terrainSettings.mountains);
        registerTerrainSubtype("volcano", () -> PM.terrainSettings.volcano);

        // --- River Settings (rivers.*) ---
        registerInt("rivers.seedOffset", () -> PM.riverSettings.seedOffset, v -> PM.riverSettings.seedOffset = v);
        registerInt("rivers.riverCount", () -> PM.riverSettings.riverCount, v -> PM.riverSettings.riverCount = v);
        registerInt("rivers.mainRivers.bedDepth", () -> PM.riverSettings.mainRivers.bedDepth, v -> PM.riverSettings.mainRivers.bedDepth = v);
        registerInt("rivers.mainRivers.minBankHeight", () -> PM.riverSettings.mainRivers.minBankHeight, v -> PM.riverSettings.mainRivers.minBankHeight = v);
        registerInt("rivers.mainRivers.maxBankHeight", () -> PM.riverSettings.mainRivers.maxBankHeight, v -> PM.riverSettings.mainRivers.maxBankHeight = v);
        registerInt("rivers.mainRivers.bedWidth", () -> PM.riverSettings.mainRivers.bedWidth, v -> PM.riverSettings.mainRivers.bedWidth = v);
        registerInt("rivers.mainRivers.bankWidth", () -> PM.riverSettings.mainRivers.bankWidth, v -> PM.riverSettings.mainRivers.bankWidth = v);
        registerFloat("rivers.mainRivers.fade", () -> PM.riverSettings.mainRivers.fade, v -> PM.riverSettings.mainRivers.fade = v);
        registerInt("rivers.branchRivers.bedDepth", () -> PM.riverSettings.branchRivers.bedDepth, v -> PM.riverSettings.branchRivers.bedDepth = v);
        registerInt("rivers.branchRivers.minBankHeight", () -> PM.riverSettings.branchRivers.minBankHeight, v -> PM.riverSettings.branchRivers.minBankHeight = v);
        registerInt("rivers.branchRivers.maxBankHeight", () -> PM.riverSettings.branchRivers.maxBankHeight, v -> PM.riverSettings.branchRivers.maxBankHeight = v);
        registerInt("rivers.branchRivers.bedWidth", () -> PM.riverSettings.branchRivers.bedWidth, v -> PM.riverSettings.branchRivers.bedWidth = v);
        registerInt("rivers.branchRivers.bankWidth", () -> PM.riverSettings.branchRivers.bankWidth, v -> PM.riverSettings.branchRivers.bankWidth = v);
        registerFloat("rivers.branchRivers.fade", () -> PM.riverSettings.branchRivers.fade, v -> PM.riverSettings.branchRivers.fade = v);
        registerFloat("rivers.lakes.chance", () -> PM.riverSettings.lakes.chance, v -> PM.riverSettings.lakes.chance = v);
        registerFloat("rivers.lakes.lakeChance", () -> PM.riverSettings.lakes.chance, v -> PM.riverSettings.lakes.chance = v);
        registerInt("rivers.lakes.depth", () -> PM.riverSettings.lakes.depth, v -> PM.riverSettings.lakes.depth = v);
        registerInt("rivers.lakes.sizeMin", () -> PM.riverSettings.lakes.sizeMin, v -> PM.riverSettings.lakes.sizeMin = v);
        registerInt("rivers.lakes.sizeMax", () -> PM.riverSettings.lakes.sizeMax, v -> PM.riverSettings.lakes.sizeMax = v);
        registerFloat("rivers.wetlands.chance", () -> PM.riverSettings.wetlands.chance, v -> PM.riverSettings.wetlands.chance = v);
        registerInt("rivers.wetlands.sizeMin", () -> PM.riverSettings.wetlands.sizeMin, v -> PM.riverSettings.wetlands.sizeMin = v);
        registerInt("rivers.wetlands.sizeMax", () -> PM.riverSettings.wetlands.sizeMax, v -> PM.riverSettings.wetlands.sizeMax = v);

        // --- Flow Settings (flow.*) ---
        registerBoolean("flow.flowParticles", () -> PM.flowSettings.flowParticles, v -> PM.flowSettings.flowParticles = v);
        registerBoolean("flow.boatFlowDynamics", () -> PM.flowSettings.boatFlowDynamics, v -> PM.flowSettings.boatFlowDynamics = v);
        registerBoolean("flow.navigableWaterfalls", () -> PM.flowSettings.navigableWaterfalls, v -> PM.flowSettings.navigableWaterfalls = v);

        // --- Miscellaneous Settings (miscellaneous.*) ---
        registerBoolean("miscellaneous.smoothLayerDecorator", () -> PM.miscellaneousSettings.smoothLayerDecorator, v -> PM.miscellaneousSettings.smoothLayerDecorator = v);
        registerInt("miscellaneous.strataRegionSize", () -> PM.miscellaneousSettings.strataRegionSize, v -> PM.miscellaneousSettings.strataRegionSize = v);
        registerBoolean("miscellaneous.strataDecorator", () -> PM.miscellaneousSettings.strataDecorator, v -> PM.miscellaneousSettings.strataDecorator = v);
        registerBoolean("miscellaneous.oreCompatibleStoneOnly", () -> PM.miscellaneousSettings.oreCompatibleStoneOnly, v -> PM.miscellaneousSettings.oreCompatibleStoneOnly = v);
        registerBoolean("miscellaneous.screeDecorator", () -> PM.miscellaneousSettings.screeDecorator, v -> PM.miscellaneousSettings.screeDecorator = v);
        registerBoolean("miscellaneous.erosionDecorator", () -> PM.miscellaneousSettings.screeDecorator, v -> PM.miscellaneousSettings.screeDecorator = v);
        registerBoolean("miscellaneous.plainStoneErosion", () -> PM.miscellaneousSettings.plainStoneErosion, v -> PM.miscellaneousSettings.plainStoneErosion = v);
        registerBoolean("miscellaneous.naturalSnowDecorator", () -> PM.miscellaneousSettings.naturalSnowDecorator, v -> PM.miscellaneousSettings.naturalSnowDecorator = v);
        registerBoolean("miscellaneous.customBiomeFeatures", () -> PM.miscellaneousSettings.customBiomeFeatures, v -> PM.miscellaneousSettings.customBiomeFeatures = v);
        registerBoolean("miscellaneous.vanillaSprings", () -> PM.miscellaneousSettings.vanillaSprings, v -> PM.miscellaneousSettings.vanillaSprings = v);
        registerBoolean("miscellaneous.vanillaLavaLakes", () -> PM.miscellaneousSettings.vanillaLavaLakes, v -> PM.miscellaneousSettings.vanillaLavaLakes = v);
        registerBoolean("miscellaneous.vanillaLavaSprings", () -> PM.miscellaneousSettings.vanillaLavaSprings, v -> PM.miscellaneousSettings.vanillaLavaSprings = v);
        registerFloat("miscellaneous.mountainBiomeUsage", () -> PM.miscellaneousSettings.mountainBiomeUsage, v -> PM.miscellaneousSettings.mountainBiomeUsage = v);
        registerFloat("miscellaneous.volcanoBiomeUsage", () -> PM.miscellaneousSettings.volcanoBiomeUsage, v -> PM.miscellaneousSettings.volcanoBiomeUsage = v);

        // --- Surface Settings (surface.*) ---
        registerInt("surface.scree.rockVariance", () -> PM.surfaceSettings.scree().rockVariance, v -> PM.surfaceSettings.scree().rockVariance = v);
        registerInt("surface.scree.rockMin", () -> PM.surfaceSettings.scree().rockMin, v -> PM.surfaceSettings.scree().rockMin = v);
        registerInt("surface.scree.dirtVariance", () -> PM.surfaceSettings.scree().dirtVariance, v -> PM.surfaceSettings.scree().dirtVariance = v);
        registerInt("surface.scree.dirtMin", () -> PM.surfaceSettings.scree().dirtMin, v -> PM.surfaceSettings.scree().dirtMin = v);
        registerFloat("surface.scree.rockSteepness", () -> PM.surfaceSettings.scree().rockSteepness, v -> PM.surfaceSettings.scree().rockSteepness = v);
        registerFloat("surface.scree.dirtSteepness", () -> PM.surfaceSettings.scree().dirtSteepness, v -> PM.surfaceSettings.scree().dirtSteepness = v);
        registerFloat("surface.scree.screeSteepness", () -> PM.surfaceSettings.scree().screeSteepness, v -> PM.surfaceSettings.scree().screeSteepness = v);
    }

    private static void registerTerrainSubtype(String prefix, Supplier<TerrainSettings.Terrain> supplier) {
        registerFloat("terrain." + prefix + ".weight", () -> supplier.get().weight, v -> supplier.get().weight = v);
        registerFloat("terrain." + prefix + ".baseScale", () -> supplier.get().baseScale, v -> supplier.get().baseScale = v);
        registerFloat("terrain." + prefix + ".verticalScale", () -> supplier.get().verticalScale, v -> supplier.get().verticalScale = v);
        registerFloat("terrain." + prefix + ".horizontalScale", () -> supplier.get().horizontalScale, v -> supplier.get().horizontalScale = v);
    }

    public static SettingsToken get(String tokenName) {
        if (tokenName == null) return null;
        return TOKENS.get(tokenName.trim());
    }

    public static List<String> getAllTokenNames() {
        return new ArrayList<>(TOKENS.keySet());
    }

    public static boolean contains(String tokenName) {
        if (tokenName == null) return false;
        return TOKENS.containsKey(tokenName.trim());
    }
}
