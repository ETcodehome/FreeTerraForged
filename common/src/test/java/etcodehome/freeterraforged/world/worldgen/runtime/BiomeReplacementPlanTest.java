package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.datafixers.util.Pair;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;

class BiomeReplacementPlanTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void appliesChainsAtomicallyRatherThanRecursively() {
		Holder<Biome> first = biome(0.1F);
		Holder<Biome> second = biome(0.5F);
		Holder<Biome> third = biome(0.9F);
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions = new IdentityHashMap<>();
		decisions.put(first, new BiomeReplacementPlan.Replace(
			second, rule(0, 1, BiomeReplacementConfig.ActionKind.REPLACE)
		));
		decisions.put(second, new BiomeReplacementPlan.Replace(
			third, rule(1, 2, BiomeReplacementConfig.ActionKind.REPLACE)
		));
		BiomeReplacementPlan plan = plan(decisions, Set.of(), Set.of(second, third));

		assertSame(second, plan.apply(first, first));
		assertSame(third, plan.apply(second, second));
		assertEquals(Set.of(second, third), plan.transformPossible(List.of(first, second)));
	}

	@Test
	void swapsRemainOneHopAndDeterministic() {
		Holder<Biome> first = biome(0.2F);
		Holder<Biome> second = biome(0.8F);
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions = new IdentityHashMap<>();
		decisions.put(first, new BiomeReplacementPlan.Replace(
			second, rule(0, 1, BiomeReplacementConfig.ActionKind.REPLACE)
		));
		decisions.put(second, new BiomeReplacementPlan.Replace(
			first, rule(1, 2, BiomeReplacementConfig.ActionKind.REPLACE)
		));
		BiomeReplacementPlan plan = plan(decisions, Set.of(), Set.of(first, second));

		assertSame(second, plan.apply(first, first));
		assertSame(first, plan.apply(second, second));
	}

	@Test
	void removalUsesProviderBaseAndCarriesPreviewProvenance() {
		Holder<Biome> removed = biome(0.3F);
		Holder<Biome> providerBase = biome(0.6F);
		BiomeReplacementPlan.RuleInfo rule = rule(
			0, 4, BiomeReplacementConfig.ActionKind.REMOVE
		);
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions = new IdentityHashMap<>();
		decisions.put(removed, new BiomeReplacementPlan.Remove(rule));
		BiomeReplacementPlan plan = plan(decisions, Set.of(removed), Set.of());

		assertSame(providerBase, plan.apply(removed, providerBase));
		BiomeReplacementPlan.PreviewSelection preview = plan.describe(removed, providerBase);
		assertSame(providerBase, preview.finalBiome());
		assertSame(removed, preview.preReplacementBiome());
		assertEquals(BiomeReplacementPlan.OverrideKind.REMOVAL_FALLBACK, preview.overrideKind());
		assertSame(rule, preview.rule());
		assertEquals(Set.of(providerBase), plan.transformPossible(List.of(removed, providerBase)));
		assertThrows(IllegalStateException.class, () -> plan.apply(removed, null));
	}

	@Test
	void candidateRemovalFiltersRequiredTablesAndRejectsEmptyTables() {
		Holder<Biome> removed = biome(0.3F);
		Holder<Biome> retained = biome(0.6F);
		IdentityHashMap<Holder<Biome>, BiomeReplacementPlan.Decision> decisions = new IdentityHashMap<>();
		decisions.put(removed, new BiomeReplacementPlan.Remove(rule(
			0, 4, BiomeReplacementConfig.ActionKind.REMOVE
		)));
		BiomeReplacementPlan plan = plan(decisions, Set.of(removed), Set.of());
		Climate.ParameterPoint point = Climate.parameters(0, 0, 0, 0, 0, 0, 0);
		Climate.ParameterList<Holder<Biome>> table = new Climate.ParameterList<>(List.of(
			Pair.of(point, removed), Pair.of(point, retained)
		));

		Climate.ParameterList<Holder<Biome>> filtered =
			MinecraftWorldgenPlanCompiler.filterRequiredCandidateTable(
				table, plan, "test provider"
			);
		assertEquals(List.of(Pair.of(point, retained)), filtered.values());
		assertThrows(IllegalStateException.class, () ->
			MinecraftWorldgenPlanCompiler.filterRequiredCandidateTable(
				new Climate.ParameterList<>(List.of(Pair.of(point, removed))),
				plan, "test provider"
			)
		);
	}

	@Test
	void inactivePlanReturnsOriginalHolderWithoutDecisionLookupResultAllocation() {
		Holder<Biome> original = biome(0.4F);
		BiomeReplacementPlan plan = plan(new IdentityHashMap<>(), Set.of(), Set.of());
		assertSame(original, plan.apply(original, null));
	}

	private static BiomeReplacementPlan plan(
		Map<Holder<Biome>, BiomeReplacementPlan.Decision> decisions,
		Set<Holder<Biome>> removals,
		Set<Holder<Biome>> targets
	) {
		String content = "format_version=1\nvalidation=strict\nplacement=preserve\n";
		BiomeReplacementConfigSnapshot snapshot = new BiomeReplacementConfigSnapshot(
			1L, "fingerprint", "test", BiomeReplacementConfigSnapshot.Origin.NATIVE,
			content, BiomeReplacementConfig.parse(content), false
		);
		return new BiomeReplacementPlan(
			snapshot, ResourceLocation.withDefaultNamespace("overworld"),
			decisions, removals, targets, List.of()
		);
	}

	private static BiomeReplacementPlan.RuleInfo rule(
		int index,
		int line,
		BiomeReplacementConfig.ActionKind actionKind
	) {
		return new BiomeReplacementPlan.RuleInfo(
			index, line, null, "minecraft:test",
			BiomeReplacementConfig.SelectorKind.BIOME,
			actionKind
		);
	}

	private static Holder<Biome> biome(float temperature) {
		BiomeSpecialEffects effects = new BiomeSpecialEffects.Builder()
			.waterColor(0x3F76E4)
			.waterFogColor(0x050533)
			.fogColor(0xC0D8FF)
			.skyColor(0x78A7FF)
			.ambientMoodSound(AmbientMoodSettings.LEGACY_CAVE_SETTINGS)
			.build();
		Biome value = new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(temperature)
			.downfall(0.5F)
			.specialEffects(effects)
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build();
		return Holder.direct(value);
	}
}
