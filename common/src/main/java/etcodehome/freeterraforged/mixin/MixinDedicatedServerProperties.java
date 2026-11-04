package etcodehome.freeterraforged.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.DataPackConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.client.data.DefaultPresetArchive;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;

@Mixin(DedicatedServerProperties.class)
public abstract class MixinDedicatedServerProperties {
	@Shadow
	@Final
	public String levelName;

	@Shadow
	@Final
	@Mutable
	public DataPackConfig initialDataPackConfiguration;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void freeterraforged$enableDefaultPresetPackForSelectedWorldType(
		Properties properties,
		CallbackInfo callback
	) {
		String levelType = properties.getProperty("level-type", "minecraft:normal");
		if (FTFWorldPresetDataPacks.selectsFreeTerraForged(levelType)) {
			Path datapacksDir = Path.of(this.levelName, "datapacks");
			Path presetFile = datapacksDir.resolve(FTFWorldPresets.GENERATED_PRESET_PACK_FILE_NAME);
			if (!Files.isRegularFile(presetFile)) {
				try {
					Preset defaultPreset = DefaultPresetArchive.getActiveDefaultPreset();
					String presetName = DefaultPresetArchive.getActiveDefaultPresetName();
					FTFWorldPresetDataPacks.exportPresetAsDatapack(presetFile, defaultPreset, presetName, null);
				} catch (IOException exception) {
					FTFCommon.LOGGER.error("Failed to generate FreeTerraForged preset datapack for dedicated server", exception);
				}
			}
			this.initialDataPackConfiguration = FTFWorldPresetDataPacks.setCustomPresetEnabled(
				this.initialDataPackConfiguration,
				true
			);
		}
	}
}
