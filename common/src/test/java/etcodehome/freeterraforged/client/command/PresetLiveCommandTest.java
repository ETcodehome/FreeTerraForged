package etcodehome.freeterraforged.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.PresetTokens;
import etcodehome.freeterraforged.data.worldgen.preset.PresetTokens.SettingsToken;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Presets;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class PresetLiveCommandTest {

    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static List<String> feedbackMessages;
    private static CommandSourceStack testSource;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        dispatcher = new CommandDispatcher<>();
        FTFCommands.register(dispatcher);

        feedbackMessages = new ArrayList<>();
        CommandSource source = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                feedbackMessages.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };

        testSource = new CommandSourceStack(
                source,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                4,
                "TestRunner",
                Component.literal("TestRunner"),
                null,
                null
        );
    }

    @Test
    @DisplayName("Verify PresetTokens coverage across all categories")
    void testPresetTokensRegistryCoverage() {
        List<String> tokens = PresetTokens.getAllTokenNames();
        assertFalse(tokens.isEmpty(), "Tokens registry must not be empty");

        String[] requiredTokens = {
                "islands.spawnIslands", "islands.size", "islands.density", "islands.beachWidth",
                "erosionFilter.scale", "erosionFilter.strengthMultiplier", "erosionFilter.flowOctaves",
                "continent.continentScale", "continent.bridgeChance", "continent.coastalComplexity",
                "controlPoints.deepOcean", "controlPoints.shallowOcean", "controlPoints.beach", "controlPoints.coast", "controlPoints.inland",
                "properties.worldHeight", "properties.seaLevel", "properties.oceanDepth",
                "climate.temperature.scale", "climate.altitudeCoolingStrength", "climate.rainShadowStrength",
                "terrain.general.terrainRegionSize", "terrain.general.globalVerticalScale", "terrain.mountains.verticalScale",
                "rivers.riverCount", "rivers.mainRivers.bedDepth", "rivers.lakes.chance",
                "flow.flowParticles", "flow.boatFlowDynamics",
                "miscellaneous.strataDecorator", "miscellaneous.smoothLayerDecorator",
                "surface.scree.rockSteepness", "surface.scree.dirtSteepness",
                "caves.entranceCaveProbability", "caves.cheeseCaveProbability", "caves.largeOreVeins"
        };

        for (String req : requiredTokens) {
            assertTrue(PresetTokens.contains(req), "PresetTokens must contain token: " + req);
            assertNotNull(PresetTokens.get(req), "PresetTokens.get must not return null for: " + req);
        }
    }

    @Test
    @DisplayName("Verify token get, set, case-insensitivity, and validation")
    void testTokenGetSetAndValidation() {
        SettingsToken sizeToken = PresetTokens.get("islands.size");
        assertNotNull(sizeToken);
        float origSize = PresetManager.PM.islandSettings.size;

        try {
            sizeToken.set("325.5");
            assertEquals("325.5", sizeToken.get());
            assertEquals(325.5F, PresetManager.PM.islandSettings.size, 0.001F);

            // Case insensitivity
            SettingsToken sizeUpper = PresetTokens.get("ISLANDS.SIZE");
            assertNotNull(sizeUpper);
            assertEquals("325.5", sizeUpper.get());

            // Invalid float parsing
            assertThrows(IllegalArgumentException.class, () -> sizeToken.set("not-a-number"));

            // Boolean token
            SettingsToken spawnToken = PresetTokens.get("islands.spawnIslands");
            assertNotNull(spawnToken);
            spawnToken.set("true");
            assertEquals("true", spawnToken.get());
            assertTrue(PresetManager.PM.islandSettings.spawnIslands);

            spawnToken.set("false");
            assertEquals("false", spawnToken.get());
            assertFalse(PresetManager.PM.islandSettings.spawnIslands);

            assertEquals(List.of("true", "false"), spawnToken.suggestions());
            assertThrows(IllegalArgumentException.class, () -> spawnToken.set("invalid_bool"));
        } finally {
            PresetManager.PM.islandSettings.size = origSize;
        }
    }

    @Test
    @DisplayName("Verify Brigadier execution of /ftf live commands")
    void testBrigadierLiveCommandExecution() throws Exception {
        feedbackMessages.clear();

        // 1. /ftf live (help usage)
        int resHelp = dispatcher.execute("ftf live", testSource);
        assertEquals(1, resHelp);
        assertTrue(feedbackMessages.stream().anyMatch(m -> m.contains("Usage: /ftf live")), "Should display usage help");

        // 2. /ftf live islands.size (view value)
        feedbackMessages.clear();
        int resView = dispatcher.execute("ftf live islands.size", testSource);
        assertEquals(1, resView);
        assertTrue(feedbackMessages.stream().anyMatch(m -> m.contains("islands.size =")), "Should display current value");

        // 3. /ftf live islands.size 240
        feedbackMessages.clear();
        float originalSize = PresetManager.PM.islandSettings.size;
        try {
            int resSet = dispatcher.execute("ftf live islands.size 240", testSource);
            assertEquals(1, resSet);
            assertEquals(240.0F, PresetManager.PM.islandSettings.size, 0.001F);
            assertTrue(feedbackMessages.stream().anyMatch(m -> m.contains("Set islands.size to 240.0")), "Feedback should confirm new value");
        } finally {
            PresetManager.PM.islandSettings.size = originalSize;
        }

        // 4. Unknown token fails gracefully
        feedbackMessages.clear();
        int resUnknown = dispatcher.execute("ftf live unknown.invalid.token", testSource);
        assertEquals(0, resUnknown);
    }

    @Test
    @DisplayName("Verify tab completion / suggestions for /ftf live tokens")
    void testBrigadierTabCompletion() throws ExecutionException, InterruptedException {
        // Tab complete token names after "/ftf live "
        ParseResults<CommandSourceStack> parse = dispatcher.parse("ftf live ", testSource);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parse).get();
        assertFalse(suggestions.getList().isEmpty(), "Should suggest settings tokens");
        assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals("islands.size")));
        assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals("erosionFilter.scale")));

        // Filtered tab completion
        ParseResults<CommandSourceStack> filterParse = dispatcher.parse("ftf live isl", testSource);
        Suggestions filterSuggestions = dispatcher.getCompletionSuggestions(filterParse).get();
        assertFalse(filterSuggestions.getList().isEmpty());
        assertTrue(filterSuggestions.getList().stream().allMatch(s -> s.getText().toLowerCase().contains("isl")));

        // Value tab completion for boolean
        ParseResults<CommandSourceStack> boolValParse = dispatcher.parse("ftf live islands.spawnIslands ", testSource);
        Suggestions boolSuggestions = dispatcher.getCompletionSuggestions(boolValParse).get();
        List<String> boolTexts = boolSuggestions.getList().stream().map(s -> s.getText()).toList();
        assertTrue(boolTexts.contains("true"));
        assertTrue(boolTexts.contains("false"));
    }

    @Test
    @DisplayName("Verify real-time world generation update and 1:1 restoration fidelity")
    void testLiveWorldgenReactivityAndFidelity() {
        Preset preset = Presets.makeFTFDefault();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        HolderGetter<Noise> noiseLookup = preset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);

        GeneratorContext context = new GeneratorContext(preset, noiseLookup, 1234567L);
        try {
            Heightmap heightmap = context.getHeightmap();

            // Establish baseline with default preset
            Cell baseCell = new Cell();
            heightmap.apply(baseCell, 200.0F, 200.0F, true);
            float originalHeight = baseCell.height;
            float originalErosion = baseCell.erosion;
            float originalSediment = baseCell.sediment;

            // Track change listener notification
            AtomicBoolean listenerFired = new AtomicBoolean(false);
            PresetManager.PM.addChangeListener(token -> {
                if ("islands.size".equals(token)) {
                    listenerFired.set(true);
                }
            });

            // Modify islands settings via live tokens
            PresetTokens.get("islands.spawnIslands").set("true");
            PresetTokens.get("islands.size").set("350.0");
            PresetManager.PM.notifyChanged("islands.size");

            assertTrue(listenerFired.get(), "Change listener should be invoked on notifyChanged");

            // Evaluate modified worldgen
            Cell modCell = new Cell();
            context.getHeightmap().apply(modCell, 200.0F, 200.0F, true);

            // Restore original settings
            PresetTokens.get("islands.spawnIslands").set("false");
            PresetTokens.get("islands.size").set(String.valueOf(preset.island().size));
            PresetManager.PM.notifyChanged("islands.size");

            // Re-evaluate after restoring original settings: MUST be 1:1 identical to original baseline!
            Cell restoredCell = new Cell();
            context.getHeightmap().apply(restoredCell, 200.0F, 200.0F, true);

            assertEquals(originalHeight, restoredCell.height, 0.0F, "Restored height must be 1:1 identical to baseline");
            assertEquals(originalErosion, restoredCell.erosion, 0.0F, "Restored erosion must be 1:1 identical to baseline");
            assertEquals(originalSediment, restoredCell.sediment, 0.0F, "Restored sediment must be 1:1 identical to baseline");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Verify divergence between distinct presets across multiple GeneratorContext instances")
    void testPresetDivergenceAcrossMultipleContexts() {
        Preset stage1DefaultPreset = Presets.modernDefaultWithRivers().copy();
        Preset userSpawnPreset = Presets.makeLegacyBeautiful().copy();
        userSpawnPreset.world().properties.worldHeight = 384;
        userSpawnPreset.world().continent.continentScale = 5555;
        userSpawnPreset.island().spawnIslands = true;
        userSpawnPreset.island().mountainChance = 0.77F;
        userSpawnPreset.erosionFilters().strengthMultiplier = 1.65F;

        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        HolderGetter<Noise> noiseLookup1 = stage1DefaultPreset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);
        HolderGetter<Noise> noiseLookup2 = userSpawnPreset.buildPreviewLookups(registries).lookupOrThrow(FTFRegistries.NOISE);

        long paritySeed = 42L;
        try (GeneratorContext worldCtx = GeneratorContext.make(userSpawnPreset, noiseLookup2, paritySeed);
             GeneratorContext staleCtx = GeneratorContext.make(stage1DefaultPreset, noiseLookup1, paritySeed)) {

            int customSpawnX = 1337;
            int customSpawnZ = -2048;
            int previewSize = 64;
            int zoomLevel = 50;

            int staleDivergentSamples = 0;
            int maxStaleElevationDelta = 0;
            Cell worldCell = new Cell();
            Cell staleCell = new Cell();

            for (int iz = 8; iz < previewSize - 8; iz += 4) {
                for (int ix = 8; ix < previewSize - 8; ix += 4) {
                    int worldX = customSpawnX + (ix - previewSize / 2) * zoomLevel;
                    int worldZ = customSpawnZ + (iz - previewSize / 2) * zoomLevel;

                    worldCtx.heightmap.apply(worldCell.reset(), worldX, worldZ, true);
                    staleCtx.heightmap.apply(staleCell.reset(), worldX, worldZ, true);

                    int worldY = worldCtx.levels.scale(worldCell.height);
                    int staleY = staleCtx.levels.scale(staleCell.height);

                    int staleDelta = Math.abs(worldY - staleY);
                    if (staleDelta > 0 || !worldCell.terrain.equals(staleCell.terrain)) {
                        staleDivergentSamples++;
                        maxStaleElevationDelta = Math.max(maxStaleElevationDelta, staleDelta);
                    }
                }
            }

            assertTrue(staleDivergentSamples > 0 && maxStaleElevationDelta >= 5,
                    "Expected divergence between Stage 1 default and Stage 2 custom, but got divergent="
                            + staleDivergentSamples + ", maxDelta=" + maxStaleElevationDelta);
        }
    }
}
