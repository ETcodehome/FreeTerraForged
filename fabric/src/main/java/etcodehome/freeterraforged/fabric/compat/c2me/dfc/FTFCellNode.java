package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.ast.AstTransformer;

import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

public final class FTFCellNode implements AstNode {
	private static final AstNode[] NO_CHILDREN = new AstNode[0];

	private final CellSampler sampler;

	public FTFCellNode(CellSampler sampler) {
		this.sampler = sampler;
	}

	public CellSampler sampler() {
		return this.sampler;
	}

	@Override
	public AstNode[] getChildren() {
		return NO_CHILDREN;
	}

	@Override
	public AstNode transform(AstTransformer transformer) {
		return transformer.transform(this);
	}

	@Override
	public boolean relaxedEquals(AstNode other) {
		if(!(other instanceof FTFCellNode node)) {
			return false;
		}
		return node.sampler.field() == this.sampler.field()
			&& node.sampler.deferredLookup() == this.sampler.deferredLookup();
	}

	@Override
	public int relaxedHashCode() {
		return FTFCellNode.class.hashCode() * 31 + this.sampler.field().hashCode();
	}

	@Override
	public ReturnType getReturnType() {
		return ReturnType.F64;
	}
}
