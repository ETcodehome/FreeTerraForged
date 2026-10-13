package etcodehome.freeterraforged.fabric;

import etcodehome.freeterraforged.server.command.FTFCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** Registers FreeTerraForged's server commands, kept apart from FTFFabric so the two entrypoints can change independently. */
public class FTFFabricCommands implements ModInitializer {

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> FTFCommands.register(dispatcher));
	}
}
