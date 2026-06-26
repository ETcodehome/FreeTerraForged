package raccoonman.reterraforged.world.worldgen;

import java.util.OptionalInt;

public interface RTFChunk {
	void setMaxHeight(int maxHeight);
	
	OptionalInt getMaxHeight();	
}
