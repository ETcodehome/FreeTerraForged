package etcodehome.freeterraforged.fabric.compat.c2me.dfc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.Heightmap;
import etcodehome.freeterraforged.world.worldgen.cell.heightmap.WorldLookup;
import etcodehome.freeterraforged.world.worldgen.densityfunction.CellSampler;

public final class FTFCellData {
	public static final Object MARKER = new Object() {
		@Override
		public String toString() {
			return "freeterraforged:cell_data";
		}
	};

	public static final int HEADER_START_X = 0;
	public static final int HEADER_START_Z = 1;
	public static final int HEADER_SIZE_X = 2;
	public static final int HEADER_SIZE_Z = 3;
	public static final int HEADER_STRIDE = 4;
	public static final int HEADER_INTS = 8;

	public static final int ALIGNMENT = 32;

	private static final CellSampler.Field[] FIELDS = CellSampler.Field.values();

	private FTFCellData() {
	}

	public static int byteSize(int sizeX, int sizeZ) {
		return HEADER_INTS * Integer.BYTES + FIELDS.length * sizeX * sizeZ * Float.BYTES;
	}

	public static byte[] build(WorldLookup lookup, int startBlockX, int startBlockZ, int sizeX, int sizeZ, boolean blocking) {
		int stride = sizeX * sizeZ;
		ByteBuffer buffer = ByteBuffer.allocate(byteSize(sizeX, sizeZ)).order(ByteOrder.nativeOrder());

		buffer.putInt(HEADER_START_X * Integer.BYTES, startBlockX);
		buffer.putInt(HEADER_START_Z * Integer.BYTES, startBlockZ);
		buffer.putInt(HEADER_SIZE_X * Integer.BYTES, sizeX);
		buffer.putInt(HEADER_SIZE_Z * Integer.BYTES, sizeZ);
		buffer.putInt(HEADER_STRIDE * Integer.BYTES, stride);

		int valuesBase = HEADER_INTS * Integer.BYTES;
		Heightmap heightmap = lookup.getHeightmap();
		Cell cell = new Cell();

		for(int localZ = 0; localZ < sizeZ; localZ++) {
			for(int localX = 0; localX < sizeX; localX++) {
				lookup.applyCell(cell.reset(), startBlockX + localX, startBlockZ + localZ, blocking, true);

				int column = localZ * sizeX + localX;
				for(int field = 0; field < FIELDS.length; field++) {
					int offset = valuesBase + (field * stride + column) * Float.BYTES;
					buffer.putFloat(offset, FIELDS[field].read(cell, heightmap));
				}
			}
		}

		return buffer.array();
	}
}
