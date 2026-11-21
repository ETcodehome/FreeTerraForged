package etcodehome.freeterraforged.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Runs an existing server command from an in-game client shortcut. */
public final class ClientCommandShortcut {
	private ClientCommandShortcut() {
	}

	public static void run(Minecraft minecraft, String command) {
		if (minecraft.screen != null || minecraft.player == null) {
			return;
		}

		ClientPacketListener connection = minecraft.getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
	}
}
