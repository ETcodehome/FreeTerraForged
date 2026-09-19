package etcodehome.freeterraforged.mixin;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter.StepFeatureData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import etcodehome.freeterraforged.world.worldgen.runtime.BiomeDecorationPlan;
import etcodehome.freeterraforged.world.worldgen.runtime.PlanBackedBiomeDecoration;

@Mixin(ChunkGenerator.class)
public abstract class MixinPlanBackedBiomeDecoration {

	@WrapOperation(
		method = "applyBiomeDecoration",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/core/Registry;stream()Ljava/util/stream/Stream;"
		)
	)
	private Stream<?> ftf$structureValues(Registry<?> registry, Operation<Stream<?>> original) {
		BiomeDecorationPlan plan = this.ftf$activePlan();
		return plan == null ? original.call(registry) : plan.structureValues();
	}

	@WrapOperation(
		method = "applyBiomeDecoration",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"
		)
	)
	private Object ftf$featureSteps(Supplier<?> supplier, Operation<Object> original) {
		BiomeDecorationPlan plan = this.ftf$activePlan();
		return plan == null ? original.call(supplier) : plan.featureSteps();
	}

	@WrapOperation(
		method = "applyBiomeDecoration",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"
		)
	)
	@SuppressWarnings("unchecked")
	private Object ftf$generationSettings(
		Function<?, ?> function,
		Object biome,
		Operation<Object> original
	) {
		BiomeDecorationPlan plan = this.ftf$activePlan();
		return plan == null
			? original.call(function, biome)
				: plan.generationSettings((Holder<Biome>) biome);
	}

	@WrapOperation(
		method = "applyBiomeDecoration",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/BiomeSource;possibleBiomes()Ljava/util/Set;"
		)
	)
	private Set<Holder<Biome>> ftf$possibleBiomes(
		BiomeSource source,
		Operation<Set<Holder<Biome>>> original
	) {
		BiomeDecorationPlan plan = this.ftf$activePlan();
		return plan == null ? original.call(source) : plan.possibleBiomes();
	}

	private BiomeDecorationPlan ftf$activePlan() {
		Object generator = this;
		return generator instanceof PlanBackedBiomeDecoration planBacked
			? planBacked.activeBiomeDecorationPlan()
			: null;
	}
}
