package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/** Explicit partial-order edge between mechanism providers. */
public record ProviderOrder(ResourceLocation before, ResourceLocation after, boolean required) {
	public ProviderOrder {
		before = Objects.requireNonNull(before, "before");
		after = Objects.requireNonNull(after, "after");
		if (before.equals(after)) {
			throw new IllegalArgumentException("A provider cannot be ordered relative to itself: " + before);
		}
	}

	public ProviderOrder(ResourceLocation before, ResourceLocation after) {
		this(before, after, true);
	}

	public static ProviderOrder optional(ResourceLocation before, ResourceLocation after) {
		return new ProviderOrder(before, after, false);
	}
}
