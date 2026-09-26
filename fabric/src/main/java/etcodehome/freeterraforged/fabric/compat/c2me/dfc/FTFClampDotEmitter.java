package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.gen.dot.DotEmitter;
import com.ishland.c2me.opts.dfc.common.gen.dot.DotGen;

public final class FTFClampDotEmitter implements DotEmitter<FTFClampNode> {
	public static final FTFClampDotEmitter INSTANCE = new FTFClampDotEmitter();

	private FTFClampDotEmitter() {
	}

	@Override
	public int doDotGen(FTFClampNode node, DotGen.Context context, DotGen.Context.Builder builder) {
		int child = context.generate(node.child());
		return builder
			.boxShape()
			.label("FreeTerraForged clamp to unit\nresolution " + node.resolution())
			.edge(child)
			.finish()
			.build();
	}
}
