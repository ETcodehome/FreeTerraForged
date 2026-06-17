package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
	@Accessor("pieceIterator")
	ObjectListIterator<Beardifier.Rigid> getPieceIterator();
	
	@Accessor("junctionIterator")
	ObjectListIterator<JigsawJunction> getJunctionIterator();
}
