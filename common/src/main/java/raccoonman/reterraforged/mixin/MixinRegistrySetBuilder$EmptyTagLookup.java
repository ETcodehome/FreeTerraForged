package raccoonman.reterraforged.mixin;

import net.minecraft.core.HolderOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.core.RegistrySetBuilder$EmptyTagLookup")
public interface MixinRegistrySetBuilder$EmptyTagLookup<T> {
    @Accessor
    HolderOwner<T> getOwner();
}
