package raccoonman.reterraforged.world.worldgen.biome;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;

public record ClimateQuerySemantics(
	ClimateQueryPolicy policy,
	@Nullable Preset preset,
	long seed,
	@Nullable GeneratorContext surfaceContext,
	Object cacheOwner
) {
	public ClimateQuerySemantics {
		policy = Objects.requireNonNull(policy, "policy");
		cacheOwner = Objects.requireNonNull(cacheOwner, "cacheOwner");
	}

	public static ClimateQuerySemantics passthrough() {
		return new ClimateQuerySemantics(
			ClimateQueryPolicy.PASSTHROUGH, null, 0L, null, new Object()
		);
	}
}
