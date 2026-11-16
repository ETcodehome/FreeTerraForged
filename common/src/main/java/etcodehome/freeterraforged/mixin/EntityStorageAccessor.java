package etcodehome.freeterraforged.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage freeterraforged$getSimpleRegionStorage();

    @Accessor("emptyChunks")
    LongSet freeterraforged$getEmptyChunks();
}
