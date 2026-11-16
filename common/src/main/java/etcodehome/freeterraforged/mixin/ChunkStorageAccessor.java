package etcodehome.freeterraforged.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {
    @Accessor("worker")
    IOWorker freeterraforged$getWorker();
}
