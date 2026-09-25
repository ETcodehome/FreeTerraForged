package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.ast.AstTransformer;

public final class FTFClampNode implements AstNode {
	private final AstNode child;
	private final int resolution;

	public FTFClampNode(AstNode child, int resolution) {
		this.child = child;
		this.resolution = resolution;
	}

	public AstNode child() {
		return this.child;
	}

	public int resolution() {
		return this.resolution;
	}

	@Override
	public AstNode[] getChildren() {
		return new AstNode[] { this.child };
	}

	@Override
	public AstNode transform(AstTransformer transformer) {
		AstNode transformedChild = this.child.transform(transformer);
		if(transformedChild == this.child) {
			return transformer.transform(this);
		}
		return transformer.transform(new FTFClampNode(transformedChild, this.resolution));
	}

	@Override
	public boolean relaxedEquals(AstNode other) {
		if(!(other instanceof FTFClampNode node)) {
			return false;
		}
		return node.resolution == this.resolution && node.child.relaxedEquals(this.child);
	}

	@Override
	public int relaxedHashCode() {
		return (FTFClampNode.class.hashCode() * 31 + this.resolution) * 31 + this.child.relaxedHashCode();
	}

	@Override
	public ReturnType getReturnType() {
		return ReturnType.F64;
	}
}
