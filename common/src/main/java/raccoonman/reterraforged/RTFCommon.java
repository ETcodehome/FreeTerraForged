package raccoonman.reterraforged;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.resources.ResourceLocation;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.biome.modifier.BiomeModifiers;
import raccoonman.reterraforged.world.worldgen.densityfunction.RTFDensityFunctions;
import raccoonman.reterraforged.world.worldgen.feature.RTFFeatures;
import raccoonman.reterraforged.world.worldgen.feature.chance.RTFChanceModifiers;
import raccoonman.reterraforged.world.worldgen.feature.placement.RTFPlacementModifiers;
import raccoonman.reterraforged.world.worldgen.feature.template.decorator.TemplateDecorators;
import raccoonman.reterraforged.world.worldgen.feature.template.placement.TemplatePlacements;
import raccoonman.reterraforged.world.worldgen.floatproviders.RTFFloatProviderTypes;
import raccoonman.reterraforged.world.worldgen.heightproviders.RTFHeightProviderTypes;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRule;
import raccoonman.reterraforged.world.worldgen.structure.rule.StructureRules;
import raccoonman.reterraforged.world.worldgen.surface.rule.RTFSurfaceRules;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import raccoonman.reterraforged.network.FlowFieldSyncPayload;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

public class RTFCommon {
	public static final String MOD_ID = "reterraforged";
	public static final String LEGACY_MOD_ID = "terraforged";
	public static final Logger LOGGER = LogManager.getLogger("ReTerraForged");

	public static void bootstrap() {
		RTFBuiltInRegistries.bootstrap();
		TemplatePlacements.bootstrap();
		TemplateDecorators.bootstrap();
		RTFChanceModifiers.bootstrap();
		RTFPlacementModifiers.bootstrap();
		RTFDensityFunctions.bootstrap();
		Noises.bootstrap();
		Domains.bootstrap();
		CurveFunctions.bootstrap();
		RTFFeatures.bootstrap();
		RTFHeightProviderTypes.bootstrap();
		RTFFloatProviderTypes.bootstrap();
		BiomeModifiers.bootstrap();
		RTFSurfaceRules.bootstrap();
		StructureRules.bootstrap();

		RegistryUtil.createDataRegistry(RTFRegistries.NOISE, Noise.DIRECT_CODEC, false);
		RegistryUtil.createDataRegistry(RTFRegistries.PRESET, Preset.DIRECT_CODEC, false);
		RegistryUtil.createDataRegistry(RTFRegistries.STRUCTURE_RULE, StructureRule.DIRECT_CODEC, false);

		// Register the payload codec and the Client-side receiver handler
		NetworkManager.registerReceiver(
				NetworkManager.Side.S2C, // Server-to-Client direction
				FlowFieldSyncPayload.TYPE,
				FlowFieldSyncPayload.CODEC,
				(payload, context) -> {
					// Queue the data execution safely on the main client thread
					context.queue(() -> {
						if (context.getPlayer().level() instanceof ClientLevel clientLevel) {
							// Pull the local client-side chunk
							ChunkAccess chunk = clientLevel.getChunk(payload.pos().x, payload.pos().z, ChunkStatus.FULL, false);

							if (chunk == null) {
								// CASE 1: Packet arrived too early! The client doesn't know this chunk exists yet.
								System.out.println("[RTF-CLIENT] ERROR: Received river data for chunk " + payload.pos() + " but the client chunk isn't loaded yet!");
							} else if (chunk instanceof IFlowFieldHolder holder) {
								// Apply the real server bytes directly to the client's memory map
								holder.reterraforged$getFlowField().loadRawGrid(payload.rawGrid());

								// Count actual values to make absolutely sure the payload wasn't stripped during serialization
								int nonZeroBytes = 0;
								for (byte b : holder.reterraforged$getFlowField().getRawGrid()) {
									if (b != 0) nonZeroBytes++;
								}

								// CASE 2: Success! Check if the client-side object actually registers the rivers
								System.out.println("[RTF-CLIENT] SUCCESS: Applied grid to client chunk " + payload.pos() +
										" | Has Rivers: " + holder.reterraforged$getFlowField().hasRivers() +
										" | Non-Zero Cells: " + nonZeroBytes);
							} else {
								// CASE 3: Interface breakdown on client
								System.out.println("[RTF-CLIENT] ERROR: Chunk found at " + payload.pos() + " but it fails 'instanceof IFlowFieldHolder' on the client side!");
							}
						} else {
							System.out.println("[RTF-CLIENT] ERROR: Packet handler context player level is not a ClientLevel.");
						}
					});
				}
		);

	}

	public static ResourceLocation location(String name) {
		if (name.contains(":")) return ResourceLocation.parse(name);
		return ResourceLocation.fromNamespaceAndPath(RTFCommon.MOD_ID, name);
	}
}

