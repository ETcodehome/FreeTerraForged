package etcodehome.freeterraforged.data.worldgen.preset;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.platform.DefaultPresetPackUtil;

public final class FTFWorldPresets {
	public static final ResourceKey<WorldPreset> FREETERRAFORGED = ResourceKey.create(
		Registries.WORLD_PRESET,
		FTFCommon.location("freeterraforged")
	);
	public static final String SERVER_LEVEL_TYPE = "ftf";
	public static final String LEGACY_SERVER_LEVEL_TYPE = "freeterraforged";
	public static final ResourceLocation DEFAULT_PRESET_PACK_ID = FTFCommon.location("ftf_default_preset");
	public static final String DEFAULT_PRESET_PACK_RESOURCE_PATH = "ftf_default_preset";
	public static final String GENERATED_PRESET_PACK_FILE_NAME = "freeterraforged-preset.zip";
	public static final String GENERATED_PRESET_PACK_ID = "file/" + GENERATED_PRESET_PACK_FILE_NAME;

	private FTFWorldPresets() {
	}

	public static String defaultPresetPackId() {
		return DefaultPresetPackUtil.defaultPresetPackId();
	}
}
