package com.ishland.c2me.opts.dfc.common.ast;

import net.minecraft.world.level.levelgen.DensityFunction;

public class McToAst {
	public static final FrontendRegistry<AstEmitter<? extends DensityFunction>> REGISTRY = null;

	public static <T extends DensityFunction> AstNode toAst(T function) {
		return null;
	}
}
