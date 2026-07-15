package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;

@Pseudo
@Mixin(targets = "com.ishland.c2me.opts.dfc.common.ast.McToAst", remap = false)
public class MixinC2MECompilerShield {

    @Inject(
            method = "toAst(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lcom/ishland/c2me/opts/dfc/common/ast/AstNode;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void rtf$abortC2MECompilation(DensityFunction df, CallbackInfoReturnable<Object> cir) {
        if (df.getClass().getName().startsWith("raccoonman.reterraforged")) {
            throw new UnsupportedOperationException("C2ME Density Function Compilation is incompatible with ReTerraForged. \nEdit .minecraft\\config\\c2me.toml and set \nuseDensityFunctionCompiler = false");
        }
    }
}