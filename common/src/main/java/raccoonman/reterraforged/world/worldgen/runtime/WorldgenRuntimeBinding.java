package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;

public final class WorldgenRuntimeBinding {
	private final AtomicReference<State> state;

	private WorldgenRuntimeBinding(State state) {
		this.state = new AtomicReference<>(Objects.requireNonNull(state, "state"));
	}

	static WorldgenRuntimeBinding create(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		return new WorldgenRuntimeBinding(new State(epoch, plan, generationSettings));
	}

	public WorldgenEpoch epoch() {
		return this.current().epoch();
	}

	public WorldgenPlan plan() {
		return this.current().plan();
	}

	State current() {
		State current = this.state.get();
		if (current == null) {
			throw new IllegalStateException("Worldgen runtime binding is closed");
		}
		return current;
	}

	State replace(
		State expected,
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		State replacement = new State(epoch, plan, generationSettings);
		if (!this.state.compareAndSet(expected, replacement)) {
			throw new IllegalStateException("Worldgen runtime changed while a replacement plan was prepared");
		}
		return expected;
	}

	State close() {
		State current = this.state.getAndSet(null);
		if (current == null) {
			throw new IllegalStateException("Worldgen runtime binding is already closed");
		}
		return current;
	}

	record State(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		Set<Holder<Biome>> possibleBiomes
	) {
		State(
			WorldgenEpoch epoch,
			WorldgenPlan plan,
			Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
		) {
			this(epoch, plan, generationSettings, WorldgenBiomeSelection.possibleBiomes(plan));
		}

		State {
			epoch = Objects.requireNonNull(epoch, "epoch");
			plan = Objects.requireNonNull(plan, "plan");
			generationSettings = Map.copyOf(generationSettings);
			possibleBiomes = Set.copyOf(possibleBiomes);
			if (!epoch.id().equals(plan.owner().id())) {
				throw new IllegalArgumentException("Worldgen plan is owned by a different epoch");
			}
		}
	}
}
