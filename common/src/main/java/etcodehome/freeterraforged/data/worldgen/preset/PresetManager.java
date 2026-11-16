package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PresetManager {

    public static class PM {
        public static boolean isLoaded = false;
        public static PresetListPage.PresetEntry cachedPreset; // only cached during new world creation menu
        public static WorldSettings worldSettings = WorldSettings.makeDefault();
        public static SurfaceSettings surfaceSettings = SurfaceSettings.makeDefault();
        public static CaveSettings caveSettings = CaveSettings.makeDefault();
        public static ClimateSettings climateSettings = ClimateSettings.makeDefault();
        public static TerrainSettings terrainSettings = TerrainSettings.makeDefault();
        public static RiverSettings riverSettings = RiverSettings.makeDefault();
        public static FlowSettings flowSettings = FlowSettings.makeDefault();
        public static IslandSettings islandSettings = IslandSettings.makeDefault();
        public static ErosionFilterSettings erosionFilterSettings = ErosionFilterSettings.makeDefault();
        public static MiscellaneousSettings miscellaneousSettings = MiscellaneousSettings.makeDefault();
        public static PresentationSettings presentationSettings = PresentationSettings.makeDefault();

        private static volatile long version = 0L;
        private static final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();
        private static final Set<GeneratorContext> activeContexts = Collections.newSetFromMap(new ConcurrentHashMap<>());

        public static void registerActiveContext(GeneratorContext ctx) {
            if (ctx != null) {
                activeContexts.add(ctx);
            }
        }

        public static void unregisterActiveContext(GeneratorContext ctx) {
            if (ctx != null) {
                activeContexts.remove(ctx);
            }
        }

        public static void addChangeListener(Consumer<String> listener) {
            changeListeners.add(listener);
        }

        public static void removeChangeListener(Consumer<String> listener) {
            changeListeners.remove(listener);
        }

        public static long getVersion() {
            return version;
        }

        public static void notifyChanged(String token) {
            version++;
            for (GeneratorContext ctx : activeContexts) {
                try {
                    ctx.reload();
                } catch (Exception e) {
                    FTFCommon.LOGGER.error("Failed to reload active GeneratorContext on setting change (token={}): {}", token, e.getMessage(), e);
                }
            }
            for (Consumer<String> listener : changeListeners) {
                try {
                    listener.accept(token);
                } catch (Exception e) {
                    FTFCommon.LOGGER.error("Error invoking setting change listener for token {}: {}", token, e.getMessage(), e);
                }
            }
        }

        public static void ingestFromPreset(Preset preset) {
            if (preset == null) {
                return;
            }
            worldSettings = preset.world();
            surfaceSettings = preset.surface();
            caveSettings = preset.caves();
            climateSettings = preset.climate();
            terrainSettings = preset.terrain();
            riverSettings = preset.rivers();
            flowSettings = preset.flow();
            islandSettings = preset.island();
            erosionFilterSettings = preset.erosionFilters();
            miscellaneousSettings = preset.miscellaneous();
            presentationSettings = preset.presentation();
            isLoaded = true;
        }

        public static void ingestFromPreset(PresetListPage.PresetEntry preset) {
            cachedPreset = preset;
            if (preset != null) {
                ingestFromPreset(preset.getPreset());
            }
        }

        public static Preset toPreset() {
            return new Preset(
                worldSettings,
                surfaceSettings,
                caveSettings,
                climateSettings,
                terrainSettings,
                riverSettings,
                flowSettings,
                islandSettings,
                erosionFilterSettings,
                miscellaneousSettings,
                presentationSettings
            );
        }

        public static Preset getPreset() {
            return toPreset();
        }

        public static void loadCheck() {
            if (!isLoaded) {
                throw new NullPointerException("This world's FreeTerraForged preset file was not successfully loaded before it was required to be ready.");
            }
        }
    }
}
