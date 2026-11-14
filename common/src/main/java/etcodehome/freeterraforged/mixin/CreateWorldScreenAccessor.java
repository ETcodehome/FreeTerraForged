package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {
	@Accessor("recreated")
	boolean freeterraforged$isRecreated();
}
