package etcodehome.freeterraforged.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoiManager.class)
public interface PoiManagerAccessor {
    @Accessor("loadedChunks")
    LongSet freeterraforged$getLoadedChunks();
}
