package raccoonman.reterraforged.server.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import raccoonman.reterraforged.RTFCommon;

// Automatically register this class to the NeoForge event bus
@EventBusSubscriber(modid = RTFCommon.MOD_ID)
public class RTFCommands {
	private static final List<BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext>> REGISTRARS = new ArrayList<>();

	public static void bootstrap() {
		register(LocateTerrainCommand::register);
		register(ExportHeightmapCommand::register);
	}

	/**
	 * Adds a command registrar to the internal list.
	 */
	public static void register(BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext> register) {
		REGISTRARS.add(register);
	}

	/**
	 * NeoForge event handler that actually performs the command registration.
	 */
	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		// Ensure bootstrap has been called so the list isn't empty
		bootstrap();

		for (var registrar : REGISTRARS) {
			registrar.accept(event.getDispatcher(), event.getBuildContext());
		}
	}
}