package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.mojang.datafixers.util.Pair;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;
import etcodehome.freeterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage.PresetEntry;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

public class BiomeSummaryPage extends PresetEditorPage {

    private static final TagKey<Biome> CAVE_TAG = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_cave"));
    private static final TagKey<Biome> MC_CAVE_TAG = TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_cave"));

    // Subterranean depth threshold in quantized climate units (0.2f * 10000 = 2000L)
    private static final long UNDERGROUND_DEPTH_THRESHOLD = 2000L;

    // Minecraft multi-octave noise standard deviation in quantized units (0.35f * 10000 = 3500.0)
    private static final double NOISE_SIGMA = 3500.0;

    public BiomeSummaryPage(PresetConfigScreen screen, PresetEntry preset) {
        super(screen, preset);
    }

    @Override
    public Component title() {
        return Component.translatable("FTFTranslationKeys.GUI_BIOME_SUMMARY_TITLE");
    }

    @Override
    public void init() {
        super.init();

        Map<String, Map<ResourceLocation, BiomeClimateData>> groupedSurface = new TreeMap<>();
        Map<String, Map<ResourceLocation, BiomeClimateData>> groupedUnderground = new TreeMap<>();

        collectAndGroupBiomes(groupedSurface, groupedUnderground);

        double totalSurfaceWeight = calculatePoolTotalWeight(groupedSurface);
        double totalUndergroundWeight = calculatePoolTotalWeight(groupedUnderground);

        // --- Surface Biomes Pool ---
        if (!groupedSurface.isEmpty()) {
            addWidgetToColumn(Component.literal("=== SURFACE BIOMES ==="));
            renderBiomePool(groupedSurface, totalSurfaceWeight, "surface");
        }

        // --- Underground Biomes Pool ---
        if (!groupedUnderground.isEmpty()) {
            addWidgetToColumn(Component.literal("=== UNDERGROUND BIOMES ==="));
            renderBiomePool(groupedUnderground, totalUndergroundWeight, "cave");
        }
    }

    private void renderBiomePool(Map<String, Map<ResourceLocation, BiomeClimateData>> groupedPool, double totalPoolWeight, String poolLabel) {
        for (Map.Entry<String, Map<ResourceLocation, BiomeClimateData>> entry : groupedPool.entrySet()) {
            String namespace = entry.getKey();
            Map<ResourceLocation, BiomeClimateData> biomes = entry.getValue();

            // Namespace Section Header
            addWidgetToColumn(Component.literal("[" + namespace + "]"));

            for (Map.Entry<ResourceLocation, BiomeClimateData> biomeEntry : biomes.entrySet()) {
                ResourceLocation biomeLoc = biomeEntry.getKey();
                BiomeClimateData data = biomeEntry.getValue();

                double estimatedOccurrence = (totalPoolWeight > 0.0) ? (data.totalGaussianWeight / totalPoolWeight) * 100.0 : 0.0;

                // Biome Name + Point Count + Gaussian Spawn Likelihood %
                Component nameLabel = Component.literal(String.format(
                        Locale.ROOT,
                        "  %s (%d pts | ~%.2f%% %s spawn)",
                        biomeLoc.getPath(),
                        data.pointCount,
                        estimatedOccurrence,
                        poolLabel
                ));
                addWidgetToColumn(nameLabel);

                // Bounding Envelope for Climate Parameters
                for (String paramLine : data.formatLines()) {
                    addWidgetToColumn(Component.literal("    " + paramLine));
                }
            }
        }
    }

    private double calculatePoolTotalWeight(Map<String, Map<ResourceLocation, BiomeClimateData>> groupedPool) {
        double grandTotal = 0.0;
        for (Map<ResourceLocation, BiomeClimateData> biomes : groupedPool.values()) {
            for (BiomeClimateData data : biomes.values()) {
                grandTotal += data.totalGaussianWeight;
            }
        }
        return grandTotal;
    }

    private void addWidgetToColumn(Component label) {
        this.left.addWidget(PresetWidgets.createLabel(label));
    }

    private void collectAndGroupBiomes(
            Map<String, Map<ResourceLocation, BiomeClimateData>> groupedSurface,
            Map<String, Map<ResourceLocation, BiomeClimateData>> groupedUnderground
    ) {
        RegistryAccess registryAccess = resolveRegistryAccess();
        if (registryAccess == null) {
            return;
        }

        Optional<Registry<MultiNoiseBiomeSourceParameterList>> paramListRegistry =
                registryAccess.registry(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

        if (paramListRegistry.isEmpty()) {
            return;
        }

        MultiNoiseBiomeSourceParameterList overworldParamList =
                paramListRegistry.get().get(MultiNoiseBiomeSourceParameterLists.OVERWORLD);

        if (overworldParamList == null) {
            return;
        }

        Climate.ParameterList<Holder<Biome>> parameters = overworldParamList.parameters();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : parameters.values()) {
            Climate.ParameterPoint point = pair.getFirst();
            Holder<Biome> holder = pair.getSecond();

            Optional<ResourceLocation> locOpt = holder.unwrapKey().map(ResourceKey::location);
            if (locOpt.isEmpty()) {
                continue;
            }

            ResourceLocation loc = locOpt.get();
            String namespace = loc.getNamespace();

            boolean isCaveBiome = holder.is(CAVE_TAG)
                    || holder.is(MC_CAVE_TAG)
                    || loc.getPath().contains("cave")
                    || loc.getPath().contains("deep_dark")
                    || loc.getPath().contains("underground");

            Map<String, Map<ResourceLocation, BiomeClimateData>> targetGroup =
                    isCaveBiome ? groupedUnderground : groupedSurface;

            targetGroup.computeIfAbsent(namespace, k -> new TreeMap<>(Comparator.comparing(ResourceLocation::getPath)))
                    .computeIfAbsent(loc, k -> new BiomeClimateData())
                    .addPoint(point);
        }
    }

    private RegistryAccess resolveRegistryAccess() {
        if (this.screen != null && this.screen.getSettings() != null) {
            WorldCreationContext settings = this.screen.getSettings();
            return settings.worldgenLoadContext();
        }
        return null;
    }

    @Override
    public Optional<Page> previous() {
        return Optional.of(new UndergroundSettingsPage(this.screen, this.preset));
    }

    @Override
    public Optional<Page> next() {
        return Optional.of(new TerrainSettingsPage(this.screen, this.preset));
    }

    private static class BiomeClimateData {
        private int pointCount = 0;
        private double totalGaussianWeight = 0.0;
        private final List<Climate.ParameterPoint> points = new ArrayList<>();

        private long minT = Long.MAX_VALUE, maxT = Long.MIN_VALUE;
        private long minH = Long.MAX_VALUE, maxH = Long.MIN_VALUE;
        private long minC = Long.MAX_VALUE, maxC = Long.MIN_VALUE;
        private long minE = Long.MAX_VALUE, maxE = Long.MIN_VALUE;
        private long minW = Long.MAX_VALUE, maxW = Long.MIN_VALUE;
        private long minD = Long.MAX_VALUE, maxD = Long.MIN_VALUE;
        private long minO = Long.MAX_VALUE, maxO = Long.MIN_VALUE;

        void addPoint(Climate.ParameterPoint point) {
            this.pointCount++;
            this.points.add(point);
            this.totalGaussianWeight += calculate5DGaussianWeight(point);

            minT = Math.min(minT, point.temperature().min());
            maxT = Math.max(maxT, point.temperature().max());

            minH = Math.min(minH, point.humidity().min());
            maxH = Math.max(maxH, point.humidity().max());

            minC = Math.min(minC, point.continentalness().min());
            maxC = Math.max(maxC, point.continentalness().max());

            minE = Math.min(minE, point.erosion().min());
            maxE = Math.max(maxE, point.erosion().max());

            minW = Math.min(minW, point.weirdness().min());
            maxW = Math.max(maxW, point.weirdness().max());

            minD = Math.min(minD, point.depth().min());
            maxD = Math.max(maxD, point.depth().max());

            minO = Math.min(minO, point.offset());
            maxO = Math.max(maxO, point.offset());
        }

        /**
         * Calculates 5D Gaussian probability density weight across T, H, C, E, and W.
         */
        private static double calculate5DGaussianWeight(Climate.ParameterPoint point) {
            double pT = getRangeProbability(point.temperature().min(), point.temperature().max());
            double pH = getRangeProbability(point.humidity().min(), point.humidity().max());
            double pC = getRangeProbability(point.continentalness().min(), point.continentalness().max());
            double pE = getRangeProbability(point.erosion().min(), point.erosion().max());
            double pW = getRangeProbability(point.weirdness().min(), point.weirdness().max());

            return pT * pH * pC * pE * pW;
        }

        /**
         * Integrates standard normal distribution over quantized interval [min, max].
         */
        private static double getRangeProbability(long min, long max) {
            if (min >= max) {
                // Point value fallback
                return normalCDF(min + 1.0, 0.0, NOISE_SIGMA) - normalCDF(min - 1.0, 0.0, NOISE_SIGMA);
            }
            return Math.max(1e-12, normalCDF(max, 0.0, NOISE_SIGMA) - normalCDF(min, 0.0, NOISE_SIGMA));
        }

        private static double normalCDF(double x, double mean, double stdDev) {
            double z = (x - mean) / stdDev;
            return 0.5 * (1.0 + erf(z / 1.4142135623730951)); // sqrt(2)
        }

        /**
         * Abramowitz and Stegun high-precision approximation for erf(z).
         */
        private static double erf(double z) {
            double sign = Math.signum(z);
            z = Math.abs(z);

            double a1 =  0.254829592;
            double a2 = -0.284496736;
            double a3 =  1.421413741;
            double a4 = -1.453152027;
            double a5 =  1.061405429;
            double p  =  0.3275911;

            double t = 1.0 / (1.0 + p * z);
            double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-z * z);
            return sign * y;
        }

        List<String> formatLines() {
            return List.of(
                    formatRow(minT, maxT, "temperature"),
                    formatRow(minH, maxH, "humidity"),
                    formatRow(minC, maxC, "continentalness"),
                    formatRow(minE, maxE, "erosion"),
                    formatRow(minW, maxW, "weirdness"),
                    formatRow(minD, maxD, "depth"),
                    formatRow(minO, maxO, "offset")
            );
        }

        private static String formatRow(long min, long max, String name) {
            float fMin = min / 10000.0f;
            float fMax = max / 10000.0f;
            return String.format(Locale.ROOT, "%5.2f -> %5.2f | %s", fMin, fMax, name);
        }
    }
}