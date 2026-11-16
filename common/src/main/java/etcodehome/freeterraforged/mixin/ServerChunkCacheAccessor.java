package etcodehome.freeterraforged.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("clearCache")
    void freeterraforged$clearCache();

    @Invoker("runDistanceManagerUpdates")
    boolean freeterraforged$runDistanceManagerUpdates();
}
