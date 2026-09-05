package raccoonman.reterraforged.mixin.compat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import dev.worldgen.lithostitched.api.event.AddBiomeInjectorsEvent;
import dev.worldgen.lithostitched.api.event.AddRegionsEvent;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.BiomeInjector;
import dev.worldgen.lithostitched.api.worldgen.util.DensityFunctionWrapper;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.region.Region;
import dev.worldgen.lithostitched.impl.event.LithostitchedEvent;
import dev.worldgen.lithostitched.mixin.common.ChunkGeneratorAccessor;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import raccoonman.reterraforged.world.worldgen.lithostitched.LithostitchedInjectionBridge;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.BiomeInjectorManager", remap = false)
public abstract class MixinLithostitchedBiomeInjectorManager {
	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/impl/event/LithostitchedEvent;invoker()Ljava/lang/Object;",
			ordinal = 0,
			remap = false
		),
		remap = false,
		require = 1
	)
	@SuppressWarnings("unchecked")
	private static Object rtf$resolveBiomeInjectorEvent(
		LithostitchedEvent<?> event,
		RegistryAccess registries,
		Registry<LevelStem> dimensions,
		long seed
	) {
		return LithostitchedInjectionBridge.biomeInjectorEvent(
			(LithostitchedEvent<AddBiomeInjectorsEvent>) event, registries, dimensions
		);
	}

	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/impl/event/LithostitchedEvent;invoker()Ljava/lang/Object;",
			ordinal = 1,
			remap = false
		),
		remap = false,
		require = 1
	)
	@SuppressWarnings("unchecked")
	private static Object rtf$resolveRegionEvent(
		LithostitchedEvent<?> event,
		RegistryAccess registries,
		Registry<LevelStem> dimensions,
		long seed
	) {
		return LithostitchedInjectionBridge.regionEvent(
			(LithostitchedEvent<AddRegionsEvent>) event, registries, dimensions
		);
	}

	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/mixin/common/ChunkGeneratorAccessor;getBiomeSource()Lnet/minecraft/world/level/biome/BiomeSource;",
			remap = true
		),
		remap = false,
		require = 2
	)
	private static BiomeSource rtf$readAcquisitionSource(ChunkGeneratorAccessor accessor) {
		return (Object) accessor instanceof TerraForgedChunkGenerator terraForged
			? terraForged.acquisitionBiomeSource()
			: accessor.getBiomeSource();
	}

	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/impl/worldgen/biomeinjector/internal/InjectorBiomeSource;applyInjectors(Ljava/util/Map;Ljava/util/Optional;Ljava/util/Map;Ldev/worldgen/lithostitched/api/worldgen/util/DensityFunctionWrapper;)V",
			remap = false
		),
		remap = false,
		require = 1
	)
	private static void rtf$captureFinalInjectors(
		InjectorBiomeSource source,
		Map<ResourceLocation, BiomeInjector> injectors,
		Optional<DensityFunction> regionFunction,
		Map<ResourceKey<Region>, Region> regions,
		DensityFunctionWrapper noiseHelper,
		RegistryAccess registries,
		Registry<LevelStem> dimensions,
		long seed
	) {
		LithostitchedInjectionBridge.applyAndCapture(
			source, injectors, regionFunction, regions, noiseHelper, registries, seed
		);
	}

	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/mixin/common/ChunkGeneratorAccessor;setBiomeSource(Lnet/minecraft/world/level/biome/BiomeSource;)V",
			remap = true
		),
		remap = false,
		require = 1
	)
	private static void rtf$retainRuntimeSource(ChunkGeneratorAccessor accessor, BiomeSource source) {
		if ((Object) accessor instanceof TerraForgedChunkGenerator terraForged) {
			LithostitchedInjectionBridge.snapshot(source).ifPresent(snapshot ->
				LithostitchedInjectionBridge.bind(terraForged.acquisitionBiomeSource(), snapshot)
			);
			return;
		}
		accessor.setBiomeSource(source);
	}

	@Redirect(
		method = "applyBiomeInjectors",
		at = @At(
			value = "INVOKE",
			target = "Ldev/worldgen/lithostitched/mixin/common/ChunkGeneratorAccessor;setFeaturesPerStep(Ljava/util/function/Supplier;)V",
			remap = false
		),
		remap = false,
		require = 1
	)
	private static void rtf$retainRuntimeFeatures(
		ChunkGeneratorAccessor accessor,
		Supplier<List<FeatureSorter.StepFeatureData>> features
	) {
		if (!((Object) accessor instanceof TerraForgedChunkGenerator)) {
			accessor.setFeaturesPerStep(features);
		}
	}
}
