package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.gen.dot.DotEmitter;
import com.ishland.c2me.opts.dfc.common.gen.dot.DotGen;

public final class FTFCellDotEmitter implements DotEmitter<FTFCellNode> {
	public static final FTFCellDotEmitter INSTANCE = new FTFCellDotEmitter();

	private FTFCellDotEmitter() {
	}

	@Override
	public int doDotGen(FTFCellNode node, DotGen.Context context, DotGen.Context.Builder builder) {
		return builder
			.boxShape()
			.label("FreeTerraForged cell\n" + node.sampler().field())
			.tooltip("Tiled heightmap sample, evaluated on the CPU")
			.build();
	}
}
