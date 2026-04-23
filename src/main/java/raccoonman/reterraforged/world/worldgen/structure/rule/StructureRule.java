package raccoonman.reterraforged.world.worldgen.structure.rule;

import java.util.function.Function;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.RandomState;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;

public interface StructureRule {
	public static final Codec<StructureRule> CODEC = RTFBuiltInRegistries.STRUCTURE_RULE_TYPE.byNameCodec()
			.dispatch("type",
					StructureRule::codec,
					(codec) -> (com.mojang.serialization.MapCodec<? extends StructureRule>) codec
			);

	boolean test(RandomState randomState, BlockPos pos);

	Codec<? extends StructureRule> codec();
}
