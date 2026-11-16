package etcodehome.freeterraforged.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage freeterraforged$getSimpleRegionStorage();

    @Accessor("storage")
    Long2ObjectMap<?> freeterraforged$getStorage();

    @Accessor("dirty")
    LongLinkedOpenHashSet freeterraforged$getDirty();
}
