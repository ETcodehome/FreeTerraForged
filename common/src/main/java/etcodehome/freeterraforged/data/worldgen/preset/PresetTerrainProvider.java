package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.TerrainSettings;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class PresetTerrainProvider {

	public static DensityFunction createOffsetSpline(Preset preset, DensityFunction continent, DensityFunction erosion, DensityFunction ridge) {
		WorldSettings worldSettings = preset.world();
		WorldSettings.Properties properties = worldSettings.properties;
		WorldSettings.ControlPoints controlPoints = worldSettings.controlPoints;
		
		TerrainSettings terrainSettings = preset.terrain();
		
//		return LinearSplineFunction.builder(ridge)
//			.addPoint(-1.0D, -0.4D)
//			.build();
		return DensityFunctions.constant(0.0D);
	}
}
