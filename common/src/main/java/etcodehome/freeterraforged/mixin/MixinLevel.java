package etcodehome.freeterraforged.mixin;

import java.util.Objects;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import etcodehome.freeterraforged.world.worldgen.FlowSettingsSnapshot;
import etcodehome.freeterraforged.world.worldgen.IFlowSettingsHolder;

@Mixin(Level.class)
public abstract class MixinLevel implements IFlowSettingsHolder {
	@Unique
	private volatile FlowSettingsSnapshot freeterraforged$flowSettings = FlowSettingsSnapshot.DISABLED;

	@Override
	public FlowSettingsSnapshot freeterraforged$getFlowSettings() {
		return this.freeterraforged$flowSettings;
	}

	@Override
	public void freeterraforged$setFlowSettings(FlowSettingsSnapshot settings) {
		this.freeterraforged$flowSettings = Objects.requireNonNull(settings, "settings");
	}
}
