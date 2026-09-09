package raccoonman.reterraforged.mixin;

import java.util.Objects;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;
import raccoonman.reterraforged.world.worldgen.IFlowSettingsHolder;

@Mixin(Level.class)
public abstract class MixinLevel implements IFlowSettingsHolder {
	@Unique
	private volatile FlowSettingsSnapshot reterraforged$flowSettings = FlowSettingsSnapshot.DISABLED;

	@Override
	public FlowSettingsSnapshot reterraforged$getFlowSettings() {
		return this.reterraforged$flowSettings;
	}

	@Override
	public void reterraforged$setFlowSettings(FlowSettingsSnapshot settings) {
		this.reterraforged$flowSettings = Objects.requireNonNull(settings, "settings");
	}
}
