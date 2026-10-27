package etcodehome.freeterraforged.world.worldgen.surface.rule;

import java.util.List;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

public record AcquiredSurfaceRule(SurfaceRules.RuleSource root, List<Source> sources, List<String> failures)
	implements SurfaceRules.RuleSource {
	public static final String TYPE = "freeterraforged:acquired_surface";
	public static final MapCodec<AcquiredSurfaceRule> CODEC = RecordCodecBuilder.<AcquiredSurfaceRule>mapCodec(instance -> instance.group(
		SurfaceRules.RuleSource.CODEC.fieldOf("root").forGetter(AcquiredSurfaceRule::root),
		Source.CODEC.listOf().fieldOf("sources").forGetter(AcquiredSurfaceRule::sources),
		Codec.STRING.listOf().optionalFieldOf("failures", List.of()).forGetter(AcquiredSurfaceRule::failures)
	).apply(instance, AcquiredSurfaceRule::new)).flatXmap(DataResult::success,
		rule -> rule.failures().isEmpty() ? DataResult.success(rule) : DataResult.error(() -> String.join("; ", rule.failures())));

	public AcquiredSurfaceRule {
		sources = List.copyOf(sources);
		failures = List.copyOf(failures);
	}

	@Override
	public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
		if (!this.failures.isEmpty()) throw new IllegalStateException(String.join("; ", this.failures));
		return this.root.apply(context);
	}

	@Override
	public KeyDispatchDataCodec<AcquiredSurfaceRule> codec() {
		return new KeyDispatchDataCodec<>(CODEC);
	}

	public record Source(String resource, String pack, int priority, String fingerprint, String operation, List<String> biomes) {
		public static final Codec<Source> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("resource").forGetter(Source::resource),
			Codec.STRING.fieldOf("pack").forGetter(Source::pack),
			Codec.INT.fieldOf("priority").forGetter(Source::priority),
			Codec.STRING.fieldOf("fingerprint").forGetter(Source::fingerprint),
			Codec.STRING.fieldOf("operation").forGetter(Source::operation),
			Codec.STRING.listOf().fieldOf("biomes").forGetter(Source::biomes)
		).apply(instance, Source::new));

		public Source {
			biomes = List.copyOf(biomes);
		}
	}
}
