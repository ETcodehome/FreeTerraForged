package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.client.gui.screen.presetconfig.PresetListPage;
import etcodehome.freeterraforged.data.worldgen.preset.settings.*;

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
        public static FilterSettings filterSettings = FilterSettings.makeDefault();
        public static MiscellaneousSettings miscellaneousSettings = MiscellaneousSettings.makeDefault();
        public static PresentationSettings presentationSettings = PresentationSettings.makeDefault();

        public static void ingestFromPreset(Preset preset) {
            worldSettings = preset.world();
            surfaceSettings = preset.surface();
            caveSettings = preset.caves();
            climateSettings = preset.climate();
            terrainSettings = preset.terrain();
            riverSettings = preset.rivers();
            flowSettings = preset.flow();
            islandSettings = preset.island();
            filterSettings = preset.filters();
            miscellaneousSettings = preset.miscellaneous();
            presentationSettings = preset.presentation();
            isLoaded = true;
        }

        public static void ingestFromPreset(PresetListPage.PresetEntry preset) {
            cachedPreset = preset;
            ingestFromPreset(preset.getPreset());
        }

        public static void loadCheck(){
            if (isLoaded != true){
                throw new NullPointerException("This worlds FreeTerraForged preset file was not successfully loaded before it was required to be ready.");
            }
        }
    }
}
