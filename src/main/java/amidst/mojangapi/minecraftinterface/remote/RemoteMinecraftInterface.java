package amidst.mojangapi.minecraftinterface.remote;

import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.local.LocalMinecraftInterfaceCreationException;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.biome.BiomeColor;
import amidst.mojangapi.world.biome.BiomeType;
import amidst.mojangapi.world.biome.UnknownBiomeIndexException;
import amidst.remote.*;
import amidst.settings.biomeprofile.BiomeProfile;
import amidst.settings.biomeprofile.BiomeProfileSelection;
import com.google.flatbuffers.FlatBufferBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.Random;

public class RemoteMinecraftInterface implements MinecraftInterface {

	private final BiomeProfileSelection biomeProfileSelection;
	private AmidstInterfaceGrpc.AmidstInterfaceBlockingStub remoteInterface;

	public static MinecraftInterface createRemoteMinecraftInterface(String remoteUrl, BiomeProfileSelection biomeProfileSelection) throws LocalMinecraftInterfaceCreationException {
		return new RemoteMinecraftInterface(remoteUrl, biomeProfileSelection);
	}

	public RemoteMinecraftInterface(String remoteUrl, BiomeProfileSelection biomeProfileSelection) {
		this.biomeProfileSelection = biomeProfileSelection;
		ManagedChannel channel = ManagedChannelBuilder.forTarget(remoteUrl).usePlaintext(true).build();
		remoteInterface = AmidstInterfaceGrpc.newBlockingStub(channel);
	}

	@Override
	public int[] getBiomeData(int x, int y, int width, int height, boolean useQuarterResolution) throws MinecraftInterfaceException {
		FlatBufferBuilder builder = new FlatBufferBuilder();
		int biomeDataRequest = BiomeDataRequest.createBiomeDataRequest(builder, x, y, width, height, useQuarterResolution);
		builder.finish(biomeDataRequest);
		BiomeDataReply biomeData = remoteInterface.getBiomeData(BiomeDataRequest.getRootAsBiomeDataRequest(builder.dataBuffer()));
		int[] data = new int[biomeData.dataLength()];
		for (int i = 0; i < data.length; i++) {
			data[i] = biomeData.data(i);
		}
		return data;
	}

	@Override
	public void createWorld(long seed, WorldType worldType, String generatorOptions) throws MinecraftInterfaceException {
		FlatBufferBuilder builder = new FlatBufferBuilder();
		int worldTypeOffset = builder.createString(mapWorldType(worldType));
		int generatorOptionsOffset = builder.createString(generatorOptions);
		int createWorldRequest = CreateWorldRequest.createCreateWorldRequest(builder, seed, worldTypeOffset, generatorOptionsOffset);
		builder.finish(createWorldRequest);
		remoteInterface.createNewWorld(CreateWorldRequest.getRootAsCreateWorldRequest(builder.dataBuffer()));

		// now load the biome list from the remote instance and adjust ours accordingly
		builder = new FlatBufferBuilder();
		GetBiomeListRequest.startGetBiomeListRequest(builder);
		int biomeListRequest = GetBiomeListRequest.endGetBiomeListRequest(builder);
		builder.finish(biomeListRequest);
		BiomeListReply biomeList = remoteInterface.getBiomeList(GetBiomeListRequest.getRootAsGetBiomeListRequest(builder.dataBuffer()));
		BiomeEntry entry = new BiomeEntry();
		Random colors = new Random();
		for (int i = 0; i < biomeList.biomesLength(); i++) {
			entry = biomeList.biomes(entry, i);
			maybeAddBiome(colors, entry);
		}
		// regenerate the biome colors. required to avoid UnknownBiomeIndexExceptions
		biomeProfileSelection.set(BiomeProfile.getDefaultProfile());

	}

	private String mapWorldType(WorldType worldType) {
		switch (worldType) {
			case DEFAULT:
				return "DEFAULT";
			case FLAT:
				return "FLAT";
			case LARGE_BIOMES:
				return "LARGE_BIOMES";
			case AMPLIFIED:
				return "AMPLIFIED";
			case CUSTOMIZED:
				return "CUSTOMIZED";
			default:
				return "UNKNOWN";
		}
	}

	private void maybeAddBiome(Random random, BiomeEntry b) {
		try {
			amidst.mojangapi.world.biome.Biome.getByIndex(b.biomeId());
		} catch (UnknownBiomeIndexException e) {
			// this constructor call has side effects.
			new amidst.mojangapi.world.biome.Biome(b.biomeName(), b.biomeId(), BiomeColor.from(random.nextInt(255), random.nextInt(255), random.nextInt(255)), BiomeType.OCEAN);
		}
	}


	@Override
	public RecognisedVersion getRecognisedVersion() {
		return RecognisedVersion.UNKNOWN;
	}
}
