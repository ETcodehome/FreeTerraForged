package etcodehome.freeterraforged.server.command;

import net.minecraft.core.BlockPos;

/** A typed locate outcome sent to the in-game preview without exposing internal failures. */
record LocatePreviewResult(Status status, BlockPos position) {
	static LocatePreviewResult found(BlockPos position) {
		return new LocatePreviewResult(Status.FOUND, position);
	}

	static LocatePreviewResult of(Status status) {
		return new LocatePreviewResult(status, BlockPos.ZERO);
	}

	enum Status {
		FOUND,
		NOT_FOUND,
		TIMED_OUT,
		IMPOSSIBLE,
		UNAVAILABLE
	}
}
