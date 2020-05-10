package amidst.mojangapi.minecraftinterface.remote;

import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.biome.BiomeColor;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.biome.BiomeType;
import amidst.mojangapi.world.versionfeatures.VersionFeature;
import amidst.remote.AmidstRemoteClient;
import amidst.remote.BiomeDataRequest;
import amidst.remote.BiomeEntry;
import amidst.settings.biomeprofile.BiomeProfile;
import amidst.settings.biomeprofile.BiomeProfileSelection;
import com.google.flatbuffers.FlatBufferBuilder;

import java.util.Random;

public class RemoteMinecraftInterface implements MinecraftInterface {

    private final BiomeProfileSelection biomeProfileSelection;
    private AmidstRemoteClient remoteInterface;

    public static MinecraftInterface createRemoteMinecraftInterface(String remoteUrl, BiomeProfileSelection biomeProfileSelection) throws MinecraftInterfaceCreationException {
        return new RemoteMinecraftInterface(remoteUrl, biomeProfileSelection);
    }

    public RemoteMinecraftInterface(String remoteUrl, BiomeProfileSelection biomeProfileSelection) {
        this.biomeProfileSelection = biomeProfileSelection;
        remoteInterface = new AmidstRemoteClient(Integer.parseInt(remoteUrl));
    }

    @Override
    public int[] getBiomeData(int x, int y, int width, int height, boolean useQuarterResolution) throws MinecraftInterfaceException {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int biomeDataRequest = BiomeDataRequest.createBiomeDataRequest(builder, x, y, width, height, useQuarterResolution);
        builder.finish(biomeDataRequest);
        return remoteInterface.getBiomeData(x, y, width, height, useQuarterResolution, biomeData -> {
            int[] data1 = new int[biomeData.dataLength()];
            for (int i = 0; i < data1.length; i++) {
                data1[i] = biomeData.data(i);
            }
            return data1;
        });
    }

    @Override
    public void createWorld(long seed, WorldType worldType, String generatorOptions) throws MinecraftInterfaceException {
        remoteInterface.createNewWorld(seed, mapWorldType(worldType), generatorOptions, r -> "");
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

    @Override
    public VersionFeature<BiomeList> getBiomeListVersionFeature() {
        BiomeList biomeList = new BiomeList();

        // map the remote biomes to a local BiomeList
        Random colors = new Random();
        remoteInterface.getBiomeList(rBiomeList -> {
            BiomeEntry entry = new BiomeEntry();
            for (int i = 0; i < rBiomeList.biomesLength(); i++) {
                entry = rBiomeList.biomes(entry, i);
                biomeList.add(new amidst.mojangapi.world.biome.Biome(entry.biomeId(), entry.biomeName(), BiomeType.OCEAN));

            }
            return "";
        });
        // regenerate the biome colors. required to avoid UnknownBiomeIndexExceptions
        BiomeColor.from(colors.nextInt(255), colors.nextInt(255), colors.nextInt(255));
        biomeProfileSelection.set(BiomeProfile.getDefaultProfile());


        return VersionFeature.constant(BiomeList.construct(biomeList));
    }

    @Override
    public RecognisedVersion getRecognisedVersion() {
        return RecognisedVersion.UNKNOWN;
    }
}
