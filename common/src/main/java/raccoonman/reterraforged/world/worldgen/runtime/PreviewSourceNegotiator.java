package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/** Deterministic negotiation of a fresh preview source before the request plan is compiled. */
public final class PreviewSourceNegotiator {
	private PreviewSourceNegotiator() {
	}

	public static Result resolve(
		PreviewSourceContext context,
		List<? extends WorldgenCapabilityProvider> providers
	) {
		Objects.requireNonNull(context, "context");
		BiomeSource realized = context.realizedSource();
		List<WorldgenCapabilityProvider> ordered = new WorldgenPlanCompiler(providers).providers();
		Result selected = null;
		for (WorldgenCapabilityProvider provider : ordered) {
			if (!provider.ownerTypes().contains(WorldgenOwnerType.PREVIEW_REQUEST)) {
				continue;
			}
			Optional<RequestOwnedBiomeSource> candidate;
			try {
				candidate = Objects.requireNonNull(
					provider.previewSource(context), "provider preview source result"
				);
			} catch (RuntimeException | LinkageError failure) {
				closeAfterFailure(selected, failure);
				throw providerFailure(provider.id(), failure);
			} catch (Exception failure) {
				closeAfterFailure(selected, failure);
				throw providerFailure(provider.id(), failure);
			}
			if (candidate.isEmpty()) {
				continue;
			}
			RequestOwnedBiomeSource owned = Objects.requireNonNull(
				candidate.orElseThrow(), "provider preview source"
			);
			if (owned.source() == realized) {
				IllegalStateException failure = new IllegalStateException(
					"Preview source provider " + provider.id() + " returned the realized source identity"
				);
				closeAfterFailure(new Result(owned, Optional.of(provider.id())), failure);
				closeAfterFailure(selected, failure);
				throw failure;
			}
			Result next = new Result(owned, Optional.of(provider.id()));
			if (selected != null) {
				IllegalStateException failure = new IllegalStateException(
					"Request-owned preview source was supplied by both "
						+ selected.provider().orElseThrow() + " and " + provider.id()
				);
				closeAfterFailure(next, failure);
				closeAfterFailure(selected, failure);
				throw failure;
			}
			selected = next;
		}
		if (selected == null && realized instanceof MultiNoiseBiomeSource) {
			BiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(
				MinecraftBiomeSourceGraphs.multiNoiseEntries(realized, context.lookups())
			));
			return new Result(RequestOwnedBiomeSource.immutable(source), Optional.empty());
		}
		if (selected == null) {
			throw new IllegalStateException(
				"The selected biome source is an opaque root and exposes no request-owned preview factory: "
					+ realized.getClass().getName()
			);
		}
		return selected;
	}

	private static IllegalStateException providerFailure(ResourceLocation provider, Throwable cause) {
		return new IllegalStateException(
			"Preview source provider " + provider + " failed to create request-owned state", cause
		);
	}

	private static void closeAfterFailure(Result result, Throwable failure) {
		if (result == null) {
			return;
		}
		try {
			result.owned().close();
		} catch (Exception cleanup) {
			failure.addSuppressed(cleanup);
		}
	}

	public record Result(
		RequestOwnedBiomeSource owned,
		Optional<ResourceLocation> provider
	) {
		public Result {
			owned = Objects.requireNonNull(owned, "owned");
			provider = Objects.requireNonNull(provider, "provider");
		}
	}
}
