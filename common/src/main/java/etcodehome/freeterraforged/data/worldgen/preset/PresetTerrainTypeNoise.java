package etcodehome.freeterraforged.data.worldgen.preset;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noise;

public class PresetTerrainTypeNoise {
	public static final ResourceKey<Noise> GROUND = PresetTerrainNoise.createKey("ground");
	
	public static void bootstrap(Preset preset, BootstrapContext<Noise> ctx) {
		WorldSettings worldSettings = preset.world();
		WorldSettings.Properties properties = worldSettings.properties;
		float seaLevel = properties.seaLevel;
		int terrainScaler = properties.terrainScaler();

		ctx.register(GROUND, Noises.constant(seaLevel / (float)terrainScaler));
	}
}
