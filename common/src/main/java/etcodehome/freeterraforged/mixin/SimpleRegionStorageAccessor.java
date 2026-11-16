package etcodehome.freeterraforged.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {
    @Accessor("worker")
    IOWorker freeterraforged$getWorker();
}
