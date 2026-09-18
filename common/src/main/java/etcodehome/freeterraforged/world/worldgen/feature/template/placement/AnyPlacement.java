package etcodehome.freeterraforged.world.worldgen.feature.template.placement;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import etcodehome.freeterraforged.world.worldgen.feature.template.BlockUtils;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.Dimensions;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.NoopTemplateContext;

record AnyPlacement() implements TemplatePlacement<NoopTemplateContext> {
	public static final MapCodec<AnyPlacement> CODEC = MapCodec.unit(AnyPlacement::new);

	@Override
	public boolean canPlaceAt(LevelAccessor world, BlockPos pos, Dimensions dimensions) {
		return true;
	}

	@Override
	public boolean canReplaceAt(LevelAccessor world, BlockPos pos) {
		return !BlockUtils.isSolid(world, pos);
	}

	@Override
	public NoopTemplateContext createContext() {
		return new NoopTemplateContext();
	}

	@Override
	public MapCodec<AnyPlacement> codec() {
		return CODEC;
	}
}
