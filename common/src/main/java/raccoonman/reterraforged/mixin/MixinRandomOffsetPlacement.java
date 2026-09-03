package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

@Mixin(RandomOffsetPlacement.class)
public class MixinRandomOffsetPlacement {

    @Inject(method = "getPositions", at = @At("RETURN"), cancellable = true)
    private void clampOffsetToChunk(
            PlacementContext context,
            RandomSource random,
            BlockPos pos,
            CallbackInfoReturnable<Stream<BlockPos>> cir
    ) {
        // Cheap, bounded check on this modifier's own field - not a graph walk,
        // not a check on some other modifier's config. Skip entirely if this
        // instance has no horizontal spread to begin with.
        IntProvider xzSpread = ((RandomOffsetPlacementAccessor) this).reterraforged$getXzSpread();
        if (xzSpread == null || xzSpread.getMinValue() == xzSpread.getMaxValue()) {
            return;
        }

        // Clamp into the chunk containing the ORIGINAL pre-offset pos - this is
        // the exact call that produces the offset, so clamping here actually
        // constrains the artifact-causing spread instead of a different,
        // earlier-running modifier's output.
        int minX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getX()));
        int maxX = minX + 15;
        int minZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getZ()));
        int maxZ = minZ + 15;

        cir.setReturnValue(cir.getReturnValue().map(p -> new BlockPos(
                Mth.clamp(p.getX(), minX, maxX),
                p.getY(),
                Mth.clamp(p.getZ(), minZ, maxZ)
        )));
    }
}