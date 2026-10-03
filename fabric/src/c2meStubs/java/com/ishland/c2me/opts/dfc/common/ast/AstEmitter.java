package com.ishland.c2me.opts.dfc.common.ast;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface AstEmitter<T extends DensityFunction> {
	AstNode toAst(T function);
}
