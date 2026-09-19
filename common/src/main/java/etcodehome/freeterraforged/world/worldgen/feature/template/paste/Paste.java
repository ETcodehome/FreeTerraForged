package etcodehome.freeterraforged.world.worldgen.feature.template.paste;

import etcodehome.freeterraforged.world.worldgen.feature.template.placement.TemplatePlacement;
import etcodehome.freeterraforged.world.worldgen.feature.template.template.TemplateContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public interface Paste {
    <T extends TemplateContext>	boolean apply(LevelAccessor world, T ctx, BlockPos origin, Mirror mirror, Rotation rotation, TemplatePlacement<T> placement, PasteConfig config);
}
