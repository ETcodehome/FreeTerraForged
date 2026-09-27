package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;

public class PresetManager {

    public static class PM {
        public static boolean isLoaded = false;
        public static PresetListPage.PresetEntry cachedPreset; // used for migration ease. Should eventually be removed.
        public static WorldSettings worldSettings = WorldSettings.makeDefault();
        public static SurfaceSettings surfaceSettings = SurfaceSettings.makeDefault();
        public static CaveSettings caveSettings = CaveSettings.makeDefault();
        public static ClimateSettings climateSettings = ClimateSettings.makeDefault();
        public static TerrainSettings terrainSettings = TerrainSettings.makeDefault();
        public static RiverSettings riverSettings = RiverSettings.makeDefault();
        public static FlowSettings flowSettings = FlowSettings.makeDefault();
        public static IslandSettings islandSettings = IslandSettings.makeDefault();
        public static FilterSettings filterSettings = FilterSettings.makeDefault();
        public static MiscellaneousSettings miscellaneousSettings = MiscellaneousSettings.makeDefault();
        public static PresentationSettings presentationSettings = PresentationSettings.makeDefault();

        public static void ingestFromPreset(PresetListPage.PresetEntry preset){
            cachedPreset = preset;
            worldSettings = cachedPreset.getPreset().world();
            surfaceSettings = cachedPreset.getPreset().surface();
            caveSettings = cachedPreset.getPreset().caves();
            climateSettings = cachedPreset.getPreset().climate();
            terrainSettings = cachedPreset.getPreset().terrain();
            riverSettings = cachedPreset.getPreset().rivers();
            flowSettings = cachedPreset.getPreset().flow();
            islandSettings = cachedPreset.getPreset().island();
            filterSettings = cachedPreset.getPreset().filters();
            miscellaneousSettings = cachedPreset.getPreset().miscellaneous();
            presentationSettings = cachedPreset.getPreset().presentation();
            isLoaded = true;
        }
    }
}
