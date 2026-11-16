package etcodehome.freeterraforged.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("storage")
    RegionFileStorage freeterraforged$getStorage();

    @Accessor("pendingWrites")
    Map<ChunkPos, ?> freeterraforged$getPendingWrites();
}
