package etcodehome.freeterraforged.mixin;

import java.util.List;

import etcodehome.freeterraforged.world.worldgen.cell.biome.spawn.SpawnFinderFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.cell.biome.spawn.SpawnFinderFix;

@Mixin(Climate.class)
public class MixinSpawnFinder {

    @Inject(method = "findSpawnPosition", at = @At("HEAD"), cancellable = true)
    private static void findSpawnPosition(List<Climate.ParameterPoint> list, Climate.Sampler sampler, CallbackInfoReturnable<BlockPos> cir) {
		if (!((Object) sampler instanceof FTFClimateSampler rtfSampler)
			|| rtfSampler.getWorldgenPlan() == null) {
			return;
		}
		FTFClimateSampler.SpawnSearch search = rtfSampler.getSpawnSearch();
        if (search.type() == SpawnType.USER_SELECTED || search.type() == SpawnType.WORLD_ORIGIN) {
            cir.setReturnValue(search.center());
            return;
        }
        cir.setReturnValue(new SpawnFinderFix(list, sampler).result.location());
    }
}
