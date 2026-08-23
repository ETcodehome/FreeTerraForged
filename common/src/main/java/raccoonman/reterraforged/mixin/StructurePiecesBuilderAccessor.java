package raccoonman.reterraforged.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

@Mixin(StructurePiecesBuilder.class)
public interface StructurePiecesBuilderAccessor {
	@Accessor("pieces")
	List<StructurePiece> rtf$getPieces();
}
