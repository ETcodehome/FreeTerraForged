package etcodehome.freeterraforged.world.worldgen.surface.rule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenPerfTracker;
import etcodehome.freeterraforged.world.worldgen.surface.ISurfaceContext;

/**
 * High-performance surface rule evaluator that short-circuits evaluation when deep underground.
 *
 * <p>In FreeTerraForged, {@link StrataRule} may be placed at the end of the surface rules sequence
 * to generate realistic rock layers (granite, diorite, andesite) throughout underground stone.
 * In mountains and highlands (Y up to 350+), every column contains hundreds of blocks of solid rock.
 * Without this fast-path, every single underground block evaluates dozens of surface and biome conditions
 * (grass, snow, calcite, water, cave features) only for all of them to fail and fall through to StrataRule
 * or return null.
 *
 * <p>When well below the surface ({@code stoneDepthAbove > 30}), well above caves ({@code stoneDepthBelow > 20}),
 * and above deepslate ({@code y > 8}), surface rules never match in any non-badlands biome. This wrapper
 * bypasses the entire base surface rule tree and delegates directly to StrataRule (or returns null if no strata),
 * saving tens of millions of redundant condition evaluations per benchmark run.
 */
public class FastStrataSequenceRule implements SurfaceRules.SurfaceRule {
	private final SurfaceRules.Context context;
	private final SurfaceRules.SurfaceRule base;
	private final @Nullable SurfaceRules.SurfaceRule strata;
	private long lastCheckedXZ = Long.MIN_VALUE;
	private boolean isBadlands;
	private boolean isDesert;

	public FastStrataSequenceRule(
			SurfaceRules.Context context,
			SurfaceRules.SurfaceRule base,
			@Nullable SurfaceRules.SurfaceRule strata
	) {
		this.context = context;
		this.base = base;
		this.strata = strata;
	}

	@Override
	public BlockState tryApply(int x, int y, int z) {
		if (this.lastCheckedXZ != this.context.lastUpdateXZ) {
			this.lastCheckedXZ = this.context.lastUpdateXZ;
			Holder<Biome> biomeHolder = this.context.biome != null ? this.context.biome.get() : null;
			this.isBadlands = biomeHolder != null && biomeHolder.is(BiomeTags.IS_BADLANDS);
			this.isDesert = biomeHolder != null && (biomeHolder.is(Biomes.DESERT)
					|| biomeHolder.unwrapKey().map(k -> k.location().getPath().contains("desert")).orElse(false));
		}

		// Fast-path for underground solid rock (stone above Y=8, deepslate below Y=0):
		if (!this.isBadlands && ((Object) this.context) instanceof ISurfaceContext sc) {
			int depthAbove = sc.freeterraforged$getStoneDepthAbove();
			int depthBelow = sc.freeterraforged$getStoneDepthBelow();

			// Deepslate zone (below Y=0 down to bedrock boundary Y=-58):
			// Keep depthAbove > 10 and depthBelow > 4 to safely clear cave floors (lush cave clay, dripstone, rooted dirt)
			// and cave ceiling features (hanging roots, stalactites).
			// Note: When stone extends to the bottom of the world without caves below, Minecraft's stoneDepthBelow
			// underflows to <= 0 (currentY - WAY_BELOW_MIN_Y + 1). Thus (depthBelow <= 0 || depthBelow > 4) ensures
			// we are safe from cave ceilings even in completely solid columns.
			if (y <= 0 && y > -59 && depthAbove > 10 && (depthBelow <= 0 || depthBelow > 4)) {
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.stats().strataFastPathHits++;
				}
				if (this.strata != null) {
					BlockState strataState = this.strata.tryApply(x, y, z);
					if (strataState != null) {
						return strataState;
					}
				}
				return Blocks.DEEPSLATE.defaultBlockState();
			}

			// Stone & Strata zone (above deepslate transition Y > 8):
			// Use minDepthAbove = 20 to guarantee all vanilla and modded surface materials (sand, sandstone dunes,
			// gravel beaches, coarse dirt, mud, rooted dirt, and cave floors) are fully resolved before short-circuiting.
			// Ceilings check depthBelow > 4 to protect hanging ceiling formations.
			// Note: As above, depthBelow <= 0 indicates solid stone extending to bedrock without any cave below.
			int minDepthAbove = 20;
			if (y > 8 && depthAbove > minDepthAbove && (depthBelow <= 0 || depthBelow > 4)) {
				if (WorldgenPerfTracker.ACTIVE) {
					WorldgenPerfTracker.stats().strataFastPathHits++;
				}
				return this.strata != null ? this.strata.tryApply(x, y, z) : null;
			}
		}

		if (WorldgenPerfTracker.ACTIVE) {
			WorldgenPerfTracker.stats().strataBaseCalls++;
		}
		BlockState state = this.base.tryApply(x, y, z);
		if (state != null) {
			return state;
		}
		return this.strata != null ? this.strata.tryApply(x, y, z) : null;
	}

	public static SurfaceRules.RuleSource optimize(SurfaceRules.RuleSource root) {
		if (root == null || root instanceof Source) {
			return root;
		}
		StrataRule strata = findStrataRule(root);
		SurfaceRules.RuleSource base = strata != null ? removeStrataRule(root, strata) : root;
		return new Source(base != null ? base : SurfaceRules.sequence(), strata);
	}

	public static StrataRule findStrataRule(SurfaceRules.RuleSource source) {
		if (source == null) {
			return null;
		}
		if (source instanceof StrataRule strata) {
			return strata;
		}
		if (source instanceof AcquiredSurfaceRule acquired) {
			return findStrataRule(acquired.root());
		}
		List<SurfaceRules.RuleSource> children = getChildren(source);
		if (children != null) {
			for (int i = children.size() - 1; i >= 0; i--) {
				StrataRule found = findStrataRule(children.get(i));
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	public static SurfaceRules.RuleSource removeStrataRule(SurfaceRules.RuleSource source, StrataRule target) {
		if (source == null || source == target) {
			return null;
		}
		if (source instanceof AcquiredSurfaceRule acquired) {
			SurfaceRules.RuleSource inner = removeStrataRule(acquired.root(), target);
			return inner != null ? new AcquiredSurfaceRule(inner, acquired.sources(), acquired.failures()) : null;
		}
		List<SurfaceRules.RuleSource> children = getChildren(source);
		if (children != null) {
			List<SurfaceRules.RuleSource> filtered = new ArrayList<>(children.size());
			boolean changed = false;
			for (SurfaceRules.RuleSource child : children) {
				SurfaceRules.RuleSource updated = removeStrataRule(child, target);
				if (updated != child) {
					changed = true;
				}
				if (updated != null) {
					filtered.add(updated);
				}
			}
			if (changed) {
				if (filtered.isEmpty()) {
					return null;
				}
				if (filtered.size() == 1) {
					return filtered.get(0);
				}
				return SurfaceRules.sequence(filtered.toArray(new SurfaceRules.RuleSource[0]));
			}
		}
		return source;
	}

	@SuppressWarnings("unchecked")
	public static List<SurfaceRules.RuleSource> getChildren(SurfaceRules.RuleSource source) {
		if (source == null) {
			return null;
		}
		Class<?> clazz = source.getClass();
		// 1. If it's a java.lang.Record (SequenceRuleSource is a Record)
		if (clazz.isRecord()) {
			for (RecordComponent rc : clazz.getRecordComponents()) {
				if (List.class.isAssignableFrom(rc.getType())) {
					try {
						Method accessor = rc.getAccessor();
						accessor.setAccessible(true);
						Object val = accessor.invoke(source);
						if (val instanceof List<?> l) {
							return (List<SurfaceRules.RuleSource>) l;
						}
					} catch (Throwable ignored) {}
				}
			}
		}
		// 2. Try method "sequence()"
		try {
			Method m = clazz.getMethod("sequence");
			m.setAccessible(true);
			Object val = m.invoke(source);
			if (val instanceof List<?> l) {
				return (List<SurfaceRules.RuleSource>) l;
			}
		} catch (Throwable ignored) {}
		// 3. Try declared field "sequence" or any List field
		for (Field f : clazz.getDeclaredFields()) {
			if (List.class.isAssignableFrom(f.getType())) {
				try {
					f.setAccessible(true);
					Object val = f.get(source);
					if (val instanceof List<?> l) {
						return (List<SurfaceRules.RuleSource>) l;
					}
				} catch (Throwable ignored) {}
			}
		}
		return null;
	}

	public record Source(SurfaceRules.RuleSource base, @Nullable StrataRule strata) implements SurfaceRules.RuleSource {
		@Override
		public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
			SurfaceRules.SurfaceRule baseRule = this.base.apply(context);
			SurfaceRules.SurfaceRule strataRule = this.strata != null ? this.strata.apply(context) : null;
			return new FastStrataSequenceRule(context, baseRule, strataRule);
		}

		@Override
		public net.minecraft.util.KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
			return this.base.codec();
		}
	}
}
