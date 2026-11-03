package etcodehome.freeterraforged.world.worldgen.cell.continent;

public interface SimpleContinent extends Continent {
	float getEdgeValue(float x, float z);

	default float getDistanceToEdge(int cx, int cz, float dx, float dy) {
		return 1.0F;
	}

	default float getDistanceToOcean(int cx, int cz, float dx, float dy) {
		return 1.0F;
	}

	default boolean isInBridgedSector(int cx, int cz, float dx, float dz) {
		return false;
	}

	default boolean isInBridgedSector(float x, float z) {
		return false;
	}
}
