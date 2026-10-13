package etcodehome.freeterraforged.neoforge;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.server.command.FTFCommands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers FreeTerraForged's server commands. */
@EventBusSubscriber(modid = FTFCommon.MOD_ID)
public class FTFNeoForgeCommands {

	@SubscribeEvent
	public static void registerCommands(RegisterCommandsEvent event) {
		FTFCommands.register(event.getDispatcher());
	}
}
