package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.ast.AstEmitter;
import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.ast.McToAst;

import etcodehome.freeterraforged.world.worldgen.densityfunction.ClampToNearestUnit;

public final class FTFClampFrontend implements AstEmitter<ClampToNearestUnit> {
	public static final FTFClampFrontend INSTANCE = new FTFClampFrontend();

	private FTFClampFrontend() {
	}

	@Override
	public AstNode toAst(ClampToNearestUnit function) {
		return new FTFClampNode(McToAst.toAst(function.function()), function.resolution());
	}
}
