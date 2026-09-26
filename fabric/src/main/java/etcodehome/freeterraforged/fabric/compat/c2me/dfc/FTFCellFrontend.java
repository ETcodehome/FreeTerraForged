package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.ast.AstEmitter;
import com.ishland.c2me.opts.dfc.common.ast.AstNode;

import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

public final class FTFCellFrontend implements AstEmitter<CellSampler> {
	public static final FTFCellFrontend INSTANCE = new FTFCellFrontend();

	private FTFCellFrontend() {
	}

	@Override
	public AstNode toAst(CellSampler function) {
		return new FTFCellNode(function);
	}
}
