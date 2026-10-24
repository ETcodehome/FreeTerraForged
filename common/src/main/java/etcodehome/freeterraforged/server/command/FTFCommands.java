package etcodehome.freeterraforged.server.command;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;

/** Registers FreeTerraForged's server commands; called by each loader's command registration hook. */
public final class FTFCommands {

	private FTFCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LocateTerrainCommand.register(dispatcher);
	}
}
