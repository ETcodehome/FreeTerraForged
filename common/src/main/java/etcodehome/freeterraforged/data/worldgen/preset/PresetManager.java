package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.data.worldgen.preset.settings.*;

public class PresetManager {

    public static class PM {
        static boolean isLoaded = false;
        static WorldSettings worldSettings = WorldSettings.makeDefault();
        static SurfaceSettings surfaceSettings = SurfaceSettings.makeDefault();
        static CaveSettings caveSettings = CaveSettings.makeDefault();
        static ClimateSettings climateSettings = ClimateSettings.makeDefault();
        static TerrainSettings terrainSettings = TerrainSettings.makeDefault();
        static RiverSettings riverSettings = RiverSettings.makeDefault();
        static FlowSettings flowSettings = FlowSettings.makeDefault();
        static IslandSettings islandSettings = IslandSettings.makeDefault();
        static FilterSettings filterSettings = FilterSettings.makeDefault();
        static MiscellaneousSettings miscellaneousSettings = MiscellaneousSettings.makeDefault();
        static PresentationSettings presentationSettings = PresentationSettings.makeDefault();
    }
}
