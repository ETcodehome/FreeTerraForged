package raccoonman.reterraforged.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.cell.biome.spawn.SpawnFinderFix;

@Mixin(Climate.class)
public class MixinSpawnFinder {

    @Inject(method = "findSpawnPosition", at = @At("HEAD"), cancellable = true)
    private static void findSpawnPosition(List<Climate.ParameterPoint> list, Climate.Sampler sampler, CallbackInfoReturnable<BlockPos> cir) {

        if (WorldSettings.Properties.spawnType == SpawnType.USER_SELECTED) {
            int x = WorldSettings.Properties.spawnX;
            int z = WorldSettings.Properties.spawnZ;
            cir.setReturnValue(new BlockPos(x, 0, z));
            return;
        }

        if (WorldSettings.Properties.spawnType == SpawnType.WORLD_ORIGIN) {
            int x = 0;
            int z = 0;
            cir.setReturnValue(new BlockPos(x, 0, z));
            return;
        }

        // other spawn type handling where it is not so deterministic.
        cir.setReturnValue(new SpawnFinderFix(list, sampler).result.location());
    }
}