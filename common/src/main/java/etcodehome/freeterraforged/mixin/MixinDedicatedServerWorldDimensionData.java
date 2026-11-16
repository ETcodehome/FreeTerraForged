package etcodehome.freeterraforged.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresets;

@Mixin(targets = "net.minecraft.server.dedicated.DedicatedServerProperties$WorldDimensionData")
public abstract class MixinDedicatedServerWorldDimensionData {
	@Redirect(
		method = "create",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/resources/ResourceLocation;tryParse(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"
		),
		require = 1
	)
	private ResourceLocation freeterraforged$parseWorldTypeAlias(String levelType) {
		if (FTFWorldPresetDataPacks.selectsFreeTerraForged(levelType)) {
			return FTFWorldPresets.FREETERRAFORGED.location();
		}
		return ResourceLocation.tryParse(levelType);
	}
}
