package raccoonman.reterraforged.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.SpawnType;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.cell.biome.spawn.SpawnFinderFix;

@Mixin(Climate.class)
public class MixinSpawnFinder {

    @Inject(method = "findSpawnPosition", at = @At("HEAD"), cancellable = true)
    private static void findSpawnPosition(List<Climate.ParameterPoint> list, Climate.Sampler sampler, CallbackInfoReturnable<BlockPos> cir) {
		if (!((Object) sampler instanceof RTFClimateSampler rtfSampler)
			|| rtfSampler.getWorldgenPlan() == null) {
			return;
		}
		RTFClimateSampler.SpawnSearch search = rtfSampler.getSpawnSearch();
        if (search.type() == SpawnType.USER_SELECTED || search.type() == SpawnType.WORLD_ORIGIN) {
            cir.setReturnValue(search.center());
            return;
        }
        cir.setReturnValue(new SpawnFinderFix(list, sampler).result.location());
    }
}
