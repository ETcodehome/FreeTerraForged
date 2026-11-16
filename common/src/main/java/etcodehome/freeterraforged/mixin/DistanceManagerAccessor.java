package etcodehome.freeterraforged.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("tickets")
    Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> freeterraforged$getTickets();

    @Accessor("playersPerChunk")
    Long2ObjectMap<ObjectSet<ServerPlayer>> freeterraforged$getPlayersPerChunk();

    @Accessor("chunksToUpdateFutures")
    Set<ChunkHolder> freeterraforged$getChunksToUpdateFutures();

    @Accessor("ticketsToRelease")
    LongSet freeterraforged$getTicketsToRelease();
}
