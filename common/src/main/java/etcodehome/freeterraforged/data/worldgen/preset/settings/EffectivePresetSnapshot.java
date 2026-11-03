package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.google.gson.JsonObject;

import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings.BiomeNoise;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings.BiomeShape;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ClimateSettings.RangeValue;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings.Lake;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings.River;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings.Wetland;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings.Terrain;

/**
 * Materializes every effective preset value for inspection.
 *
 * <p>The normal preset codec is intentionally compact and omits values equal to their defaults.
 * Commands and read-only screens need the resolved runtime state instead, so this snapshot retains
 * the canonical preset keys while always writing every value.</p>
 */
public final class EffectivePresetSnapshot {
	private EffectivePresetSnapshot() {
	}

	public static JsonObject create(Preset preset) {
		JsonObject root = new JsonObject();
		root.add("world", world(preset.world()));
		root.add("surface", surface(preset.surface()));
		root.add("caves", caves(preset.caves()));
		root.add("climate", climate(preset.climate()));
		root.add("terrain", terrain(preset.terrain()));
		root.add("rivers", rivers(preset.rivers()));
		root.add("flow", flow(preset.flow()));
		root.add("island", island(preset.island()));
		root.add("erosionFilters", erosionFilters(preset.erosionFilters()));
		root.add("miscellaneous", miscellaneous(preset.miscellaneous()));
		root.add("presentation", presentation(preset.presentation()));
		return root;
	}

	private static JsonObject world(WorldSettings settings) {
		JsonObject continent = new JsonObject();
		continent.addProperty("continentType", settings.continent.continentType.getSerializedName());
		continent.addProperty("continentShape", settings.continent.continentShape.getSerializedName());
		continent.addProperty("continentScale", settings.continent.continentScale);
		continent.addProperty("continentJitter", settings.continent.continentJitter);
		continent.addProperty("continentSkipping", settings.continent.continentSkipping);
		continent.addProperty("continentSizeVariance", settings.continent.continentSizeVariance);
		continent.addProperty("continentNoiseOctaves", settings.continent.continentNoiseOctaves);
		continent.addProperty("continentNoiseGain", settings.continent.continentNoiseGain);
		continent.addProperty("continentNoiseLacunarity", settings.continent.continentNoiseLacunarity);

		JsonObject controlPoints = new JsonObject();
		controlPoints.addProperty("islandInland", settings.controlPoints.islandInland);
		controlPoints.addProperty("islandCoast", settings.controlPoints.islandCoast);
		controlPoints.addProperty("deepOcean", settings.controlPoints.deepOcean);
		controlPoints.addProperty("shallowOcean", settings.controlPoints.shallowOcean);
		controlPoints.addProperty("beach", settings.controlPoints.beach);
		controlPoints.addProperty("coast", settings.controlPoints.coast);
		controlPoints.addProperty("inland", settings.controlPoints.inland);

		JsonObject properties = new JsonObject();
		properties.addProperty("spawnType", settings.properties.spawnType.getSerializedName());
		properties.addProperty("worldHeight", settings.properties.worldHeight);
		properties.addProperty("worldDepth", settings.properties.worldDepth);
		properties.addProperty("seaLevel", settings.properties.seaLevel);
		properties.addProperty("lavaLevel", settings.properties.lavaLevel);
		properties.addProperty("oceanDepth", settings.properties.oceanDepth);
		properties.addProperty("spawnX", settings.properties.spawnX);
		properties.addProperty("spawnZ", settings.properties.spawnZ);

		JsonObject result = new JsonObject();
		result.add("continent", continent);
		result.add("controlPoints", controlPoints);
		result.add("properties", properties);
		return result;
	}

	private static JsonObject surface(SurfaceSettings settings) {
		SurfaceSettings.Scree scree = settings.scree();
		JsonObject erosion = new JsonObject();
		erosion.addProperty("rockVariance", scree.rockVariance);
		erosion.addProperty("rockMin", scree.rockMin);
		erosion.addProperty("dirtVariance", scree.dirtVariance);
		erosion.addProperty("dirtMin", scree.dirtMin);
		erosion.addProperty("rockSteepness", scree.rockSteepness);
		erosion.addProperty("dirtSteepness", scree.dirtSteepness);
		erosion.addProperty("screeSteepness", scree.screeSteepness);

		JsonObject result = new JsonObject();
		result.add("erosion", erosion);
		return result;
	}

	private static JsonObject caves(CaveSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("entranceCaveProbability", settings.entranceCaveProbability);
		result.addProperty("cheeseCaveDepthOffset", settings.cheeseCaveDepthOffset);
		result.addProperty("cheeseCaveProbability", settings.cheeseCaveProbability);
		result.addProperty("spaghettiCaveProbability", settings.spaghettiCaveProbability);
		result.addProperty("noodleCaveProbability", settings.noodleCaveProbability);
		result.addProperty("caveCarverProbability", settings.caveCarverProbability);
		result.addProperty("deepCaveCarverProbability", settings.deepCaveCarverProbability);
		result.addProperty("ravineCarverProbability", settings.ravineCarverProbability);
		result.addProperty("largeOreVeins", settings.largeOreVeins);
		result.addProperty("legacyCarverDistribution", settings.legacyCarverDistribution);
		return result;
	}

	private static JsonObject climate(ClimateSettings settings) {
		JsonObject result = new JsonObject();
		result.add("temperature", range(settings.temperature));
		result.add("moisture", range(settings.moisture));
		result.add("biomeShape", biomeShape(settings.biomeShape));
		result.add("biomeEdgeShape", biomeNoise(settings.biomeEdgeShape));
		result.addProperty("altitudeCoolingStrength", settings.altitudeCoolingStrength);
		result.addProperty("rainShadowStrength", settings.rainShadowStrength);
		result.addProperty("coastalMoistureBoost", settings.coastalMoistureBoost);
		return result;
	}

	private static JsonObject range(RangeValue settings) {
		JsonObject result = new JsonObject();
		result.addProperty("seedOffset", settings.seedOffset);
		result.addProperty("scale", settings.scale);
		result.addProperty("falloff", settings.falloff);
		result.addProperty("min", settings.min);
		result.addProperty("max", settings.max);
		result.addProperty("bias", settings.offsetBias);
		result.addProperty("distributionBias", settings.distributionBias);
		return result;
	}

	private static JsonObject biomeShape(BiomeShape settings) {
		JsonObject result = new JsonObject();
		result.addProperty("biomeSize", settings.biomeSize);
		result.addProperty("undergroundBiomeSize", settings.undergroundBiomeSize);
		result.addProperty("undergroundBiomeVerticalSize", settings.undergroundBiomeVerticalSize);
		result.addProperty("undergroundBiomeCoverage", settings.undergroundBiomeCoverage);
		result.addProperty("undergroundBiomeClimateInfluence", settings.undergroundBiomeClimateInfluence);
		result.addProperty("undergroundBiomeBanding", settings.undergroundBiomeBanding);
		result.addProperty("macroNoiseSize", settings.macroNoiseSize);
		result.addProperty("biomeWarpScale", settings.biomeWarpScale);
		result.addProperty("biomeWarpStrength", settings.biomeWarpStrength);
		return result;
	}

	private static JsonObject biomeNoise(BiomeNoise settings) {
		JsonObject result = new JsonObject();
		result.addProperty("type", settings.type.getSerializedName());
		result.addProperty("scale", settings.scale);
		result.addProperty("octaves", settings.octaves);
		result.addProperty("gain", settings.gain);
		result.addProperty("lacunarity", settings.lacunarity);
		result.addProperty("strength", settings.strength);
		return result;
	}

	private static JsonObject terrain(TerrainSettings settings) {
		JsonObject general = new JsonObject();
		general.addProperty("terrainSeedOffset", settings.general.terrainSeedOffset);
		general.addProperty("terrainRegionSize", settings.general.terrainRegionSize);
		general.addProperty("globalVerticalScale", settings.general.globalVerticalScale);
		general.addProperty("globalHorizontalScale", settings.general.globalHorizontalScale);
		general.addProperty("fancyMountains", settings.general.fancyMountains);
		general.addProperty("legacyMountainScaling", settings.general.legacyMountainScaling);
		general.addProperty("mountainVariety", settings.general.mountainVariety);

		JsonObject result = new JsonObject();
		result.add("general", general);
		result.add("steppe", terrainFamily(settings.steppe));
		result.add("plains", terrainFamily(settings.plains));
		result.add("hills", terrainFamily(settings.hills));
		result.add("dales", terrainFamily(settings.dales));
		result.add("plateau", terrainFamily(settings.plateau));
		result.add("badlands", terrainFamily(settings.badlands));
		result.add("torridonian", terrainFamily(settings.torridonian));
		result.add("mountains", terrainFamily(settings.mountains));
		result.add("volcano", terrainFamily(settings.volcano));
		return result;
	}

	private static JsonObject terrainFamily(Terrain settings) {
		JsonObject result = new JsonObject();
		result.addProperty("weight", settings.weight);
		result.addProperty("baseScale", settings.baseScale);
		result.addProperty("verticalScale", settings.verticalScale);
		result.addProperty("horizontalScale", settings.horizontalScale);
		return result;
	}

	private static JsonObject rivers(RiverSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("seedOffset", settings.seedOffset);
		result.addProperty("riverCount", settings.riverCount);
		result.add("mainRivers", river(settings.mainRivers));
		result.add("branchRivers", river(settings.branchRivers));
		result.add("lakes", lake(settings.lakes));
		result.add("wetlands", wetland(settings.wetlands));
		return result;
	}

	private static JsonObject river(River settings) {
		JsonObject result = new JsonObject();
		result.addProperty("bedDepth", settings.bedDepth);
		result.addProperty("minBankHeight", settings.minBankHeight);
		result.addProperty("maxBankHeight", settings.maxBankHeight);
		result.addProperty("bankWidth", settings.bankWidth);
		result.addProperty("bedWidth", settings.bedWidth);
		result.addProperty("fade", settings.fade);
		return result;
	}

	private static JsonObject lake(Lake settings) {
		JsonObject result = new JsonObject();
		result.addProperty("chance", settings.chance);
		result.addProperty("minStartDistance", settings.minStartDistance);
		result.addProperty("maxStartDistance", settings.maxStartDistance);
		result.addProperty("depth", settings.depth);
		result.addProperty("sizeMin", settings.sizeMin);
		result.addProperty("sizeMax", settings.sizeMax);
		result.addProperty("minBankHeight", settings.minBankHeight);
		result.addProperty("maxBankHeight", settings.maxBankHeight);
		return result;
	}

	private static JsonObject wetland(Wetland settings) {
		JsonObject result = new JsonObject();
		result.addProperty("chance", settings.chance);
		result.addProperty("sizeMin", settings.sizeMin);
		result.addProperty("sizeMax", settings.sizeMax);
		return result;
	}

	private static JsonObject flow(FlowSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("flowParticles", settings.flowParticles);
		result.addProperty("boatFlowDynamics", settings.boatFlowDynamics);
		result.addProperty("navigableWaterfalls", settings.navigableWaterfalls);
		return result;
	}

	private static JsonObject island(IslandSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("enableArchipelago", settings.spawnIslands);
		result.addProperty("islandDensity", settings.density);
		result.addProperty("islandSize", settings.size);
		result.addProperty("islandHeight", settings.height);
		result.addProperty("islandBaseScale", settings.baseScale);
		result.addProperty("islandVerticalScale", settings.verticalScale);
		result.addProperty("islandHorizontalScale", settings.horizontalScale);
		result.addProperty("offshoreDepth", settings.offshoreDepth);
		result.addProperty("macroDensityPercentage", settings.macroDensityPercentage);
		result.addProperty("mountainChance", settings.mountainChance);
		result.addProperty("volcanoChance", settings.volcanoChance);
		result.addProperty("beachWidth", settings.beachWidth);
		result.addProperty("beachCoverage", settings.beachCoverage);
		result.addProperty("volcanismScale", settings.volcanismScale);
		result.addProperty("mountainScale", settings.mountainScale);
		result.addProperty("volcanismHorizontalScale", settings.volcanismHorizontalScale);
		result.addProperty("mountainHorizontalScale", settings.mountainHorizontalScale);
		return result;
	}

	private static JsonObject erosionFilters(ErosionFilterSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("scale", settings.scale);
		result.addProperty("strengthMultiplier", settings.strengthMultiplier);
		result.addProperty("gullySharpness", settings.gullySharpness);
		result.addProperty("flowOctaves", settings.flowOctaves);
		result.addProperty("lacunarity", settings.lacunarity);
		result.addProperty("gain", settings.gain);
		result.addProperty("phacelleScale", settings.phacelleScale);
		result.addProperty("phacelleOffset", settings.phacelleOffset);
		result.addProperty("phacelleNormalization", settings.phacelleNormalization);
		result.addProperty("slopeOnsetBase", settings.slopeOnsetBase);
		result.addProperty("slopeOnsetRidge", settings.slopeOnsetRidge);
		result.addProperty("slopeOnsetOctave", settings.slopeOnsetOctave);
		result.addProperty("roundingMin", settings.roundingMin);
		result.addProperty("roundingMax", settings.roundingMax);
		return result;
	}

	private static JsonObject miscellaneous(MiscellaneousSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("smoothLayerDecorator", settings.smoothLayerDecorator);
		result.addProperty("strataRegionSize", settings.strataRegionSize);
		result.addProperty("strataDecorator", settings.strataDecorator);
		result.addProperty("oreCompatibleStoneOnly", settings.oreCompatibleStoneOnly);
		result.addProperty("erosionDecorator", settings.screeDecorator);
		result.addProperty("plainStoneErosion", settings.plainStoneErosion);
		result.addProperty("naturalSnowDecorator", settings.naturalSnowDecorator);
		result.addProperty("customBiomeFeatures", settings.customBiomeFeatures);
		result.addProperty("vanillaSprings", settings.vanillaSprings);
		result.addProperty("vanillaLavaLakes", settings.vanillaLavaLakes);
		result.addProperty("vanillaLavaSprings", settings.vanillaLavaSprings);
		result.addProperty("mountainBiomeUsage", settings.mountainBiomeUsage);
		result.addProperty("volcanoBiomeUsage", settings.volcanoBiomeUsage);
		return result;
	}

	private static JsonObject presentation(PresentationSettings settings) {
		JsonObject result = new JsonObject();
		result.addProperty("presetName", settings.presetName);
		result.addProperty("lastModified", settings.lastModified);
		return result;
	}
}
