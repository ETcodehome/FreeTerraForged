package etcodehome.freeterraforged.mixin;

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
import etcodehome.freeterraforged.data.worldgen.preset.FTFWorldPresetDataPacks;

@Mixin(DedicatedServerProperties.class)
public abstract class MixinDedicatedServerProperties {
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
			this.initialDataPackConfiguration = FTFWorldPresetDataPacks.setDefaultPresetEnabled(
				this.initialDataPackConfiguration,
				true
			);
		}
	}
}
