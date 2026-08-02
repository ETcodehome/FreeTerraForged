package raccoonman.reterraforged.mixin.terrablender;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.SurfaceRules;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderSurfaceRules;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;

@Mixin(NamespacedSurfaceRuleSource.class)
public abstract class MixinNamespacedSurfaceRuleSource {
	@Inject(
		method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void reterraforged$unwrapRedundantNamespacedRule(
		SurfaceRules.Context context,
		CallbackInfoReturnable<SurfaceRules.SurfaceRule> callback
	) {
		NamespacedSurfaceRuleSource source = (NamespacedSurfaceRuleSource) (Object) this;
		if (TerraBlenderSurfaceRules.hasOnlyMinecraftNamespace(source.sources().keySet())) {
			SurfaceRules.SurfaceRule minecraftRule = source.sources().get("minecraft").apply(context);
			SurfaceRules.SurfaceRule baseRule = source.base().apply(context);
			callback.setReturnValue((x, y, z) -> TerraBlenderSurfaceRules.select(
				minecraftRule.tryApply(x, y, z),
				baseRule.tryApply(x, y, z),
				() -> {
					Holder<Biome> biome = ((SurfaceRulesContextAccessor) (Object) context).reterraforged$getBiome().get();
					return biome.unwrapKey()
						.map(ResourceKey::location)
						.map(location -> location.getNamespace().equals("minecraft"))
						.orElse(false);
				}
			));
		}
	}
}
