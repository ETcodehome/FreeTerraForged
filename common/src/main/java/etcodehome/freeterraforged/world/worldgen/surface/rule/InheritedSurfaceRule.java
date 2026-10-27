package etcodehome.freeterraforged.world.worldgen.surface.rule;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

public record InheritedSurfaceRule(SurfaceRules.RuleSource fallback, String policy) implements SurfaceRules.RuleSource {
	public static final String TYPE = "freeterraforged:inherited_surface";
	public static final String REPLACE = "replace";
	public static final String BIOME_DOMAINS = "bundled_biome_domains";
	public static final MapCodec<InheritedSurfaceRule> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		SurfaceRules.RuleSource.CODEC.fieldOf("fallback").forGetter(InheritedSurfaceRule::fallback),
		Codec.STRING.optionalFieldOf("policy", REPLACE).forGetter(InheritedSurfaceRule::policy)
	).apply(instance, InheritedSurfaceRule::new));

	public InheritedSurfaceRule(SurfaceRules.RuleSource fallback) {
		this(fallback, REPLACE);
	}

	public InheritedSurfaceRule {
		if (!REPLACE.equals(policy) && !BIOME_DOMAINS.equals(policy)) {
			throw new IllegalArgumentException("Unknown surface acquisition policy: " + policy);
		}
	}

	@Override
	public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
		throw new IllegalStateException("FTF inherited surface input reached execution without resource acquisition");
	}

	@Override
	public KeyDispatchDataCodec<InheritedSurfaceRule> codec() {
		return new KeyDispatchDataCodec<>(CODEC);
	}
}
