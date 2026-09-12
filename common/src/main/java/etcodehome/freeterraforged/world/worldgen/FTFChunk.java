package etcodehome.freeterraforged.world.worldgen;

import java.util.OptionalInt;

public interface FTFChunk {
	void setMaxHeight(int maxHeight);
	
	OptionalInt getMaxHeight();	
}
