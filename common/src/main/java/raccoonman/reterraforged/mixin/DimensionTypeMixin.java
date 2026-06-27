package raccoonman.reterraforged.mixin;

import net.minecraft.world.level.dimension.DimensionType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DimensionType.class)
public class DimensionTypeMixin {

    @Shadow @Mutable public static int Y_SIZE;
    @Shadow @Mutable public static int MAX_Y;
    @Shadow @Mutable public static int MIN_Y;
    @Shadow @Mutable public static int WAY_ABOVE_MAX_Y;
    @Shadow @Mutable public static int WAY_BELOW_MIN_Y;

    @Inject(
            method = "<clinit>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;DIRECT_CODEC:Lcom/mojang/serialization/Codec;",
                    opcode = Opcodes.PUTSTATIC,
                    shift = At.Shift.BEFORE
            )
    )
    private static void reterraforged$relaxWorldHeight(CallbackInfo ci) {
        Y_SIZE = 8128;                 // must be a multiple of 16 — pick what you actually want
        MAX_Y = (Y_SIZE >> 1) - 1;
        MIN_Y = MAX_Y - Y_SIZE + 1;
        WAY_ABOVE_MAX_Y = MAX_Y << 4;
        WAY_BELOW_MIN_Y = MIN_Y << 4;
    }
}