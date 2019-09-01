package amidst.mojangapi.minecraftinterface.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;

import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.local.LocalMinecraftInterfaceCreationException;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.biome.BiomeColor;
import amidst.mojangapi.world.biome.BiomeType;
import amidst.mojangapi.world.biome.UnknownBiomeIndexException;
import amidst.remote.sbe.BiomeDataBlockDecoder;
import amidst.remote.sbe.BiomeDataRequestEncoder;
import amidst.remote.sbe.BiomeDataResponseDecoder;
import amidst.remote.sbe.BiomeListRequestEncoder;
import amidst.remote.sbe.BiomeListResponseDecoder;
import amidst.remote.sbe.BiomeListResponseDecoder.BiomeEntryDecoder;
import amidst.remote.sbe.BiomeDataResponseDecoder.BiomeDataDecoder;
import amidst.remote.sbe.BooleanType;
import amidst.remote.sbe.CreateWorldRequestEncoder;
import amidst.remote.sbe.MessageHeaderDecoder;
import amidst.remote.sbe.MessageHeaderEncoder;
import amidst.settings.biomeprofile.BiomeProfile;
import amidst.settings.biomeprofile.BiomeProfileSelection;
import io.aeron.Aeron;
import io.aeron.Aeron.Context;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import net.lessqq.amidstforge.Constants;
import net.lessqq.amidstforge.RemoteCommunicationException;

public class RemoteMinecraftInterface implements MinecraftInterface {

    private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    private final BiomeDataResponseDecoder biomeDataResponseDecoder = new BiomeDataResponseDecoder();
    private final BiomeListResponseDecoder biomeListResponseDecoder = new BiomeListResponseDecoder();

    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final BiomeDataRequestEncoder biomeDataRequestEncoder = new BiomeDataRequestEncoder();
    private final CreateWorldRequestEncoder createWorldRequestEncoder = new CreateWorldRequestEncoder();
    private final BiomeListRequestEncoder biomeListRequestEncoder = new BiomeListRequestEncoder();

    private final BiomeProfileSelection biomeProfileSelection;
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(1024);
    private final BufferClaim bufferClaim = new BufferClaim();
    private Context context;
    private Publication publication;
    private Subscription subscription;

    public static MinecraftInterface createRemoteMinecraftInterface(String remoteUrl,
            BiomeProfileSelection biomeProfileSelection) throws LocalMinecraftInterfaceCreationException {
        return new RemoteMinecraftInterface(remoteUrl, biomeProfileSelection);
    }

    public RemoteMinecraftInterface(String remoteUrl, BiomeProfileSelection biomeProfileSelection) {
        this.biomeProfileSelection = biomeProfileSelection;
        context = new Context().aeronDirectoryName(remoteUrl);
        final Aeron aeron = Aeron.connect(context);

        subscription = aeron.addSubscription("aeron:ipc", Constants.RESPONSE_STREAM_ID);
        publication = aeron.addPublication("aeron:ipc", Constants.REQUEST_STREAM_ID);
    }

    @Override
    public int[] getBiomeData(int x, int y, int width, int height, boolean useQuarterResolution)
            throws MinecraftInterfaceException {

        sendBiomeDataRequest(x, y, width, height, useQuarterResolution);
        return readBiomeDataResponse(width, height);
    }

    private int[] readBiomeDataResponse(int width, int height) {
        int[] data = new int[width * height];
        FragmentHandler reassemblingFragmentHandler = new FragmentAssembler(new FragmentHandler() {

            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
                messageHeaderDecoder.wrap(buffer, offset);
                final int schemaId = messageHeaderDecoder.schemaId();
                if (schemaId != MessageHeaderDecoder.SCHEMA_ID) {
                    throw new RemoteCommunicationException(
                            "expected schemaId=" + MessageHeaderDecoder.SCHEMA_ID + ", actual=" + schemaId);
                }
                final int templateId = messageHeaderDecoder.templateId();
                if (templateId != BiomeDataResponseDecoder.TEMPLATE_ID) {
                    throw new RemoteCommunicationException(
                            "expected templateId=" + BiomeDataResponseDecoder.TEMPLATE_ID + ", actual=" + templateId);
                }
                biomeDataResponseDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                        messageHeaderDecoder.blockLength(), messageHeaderDecoder.version());
                int totalPos = 0;
                for (BiomeDataDecoder d : biomeDataResponseDecoder.biomeData()) {
                    BiomeDataBlockDecoder blockDecoder = d.data();
                    int dataLength = blockDecoder.length();
                    for (int i = 0; i < dataLength; i++) {
                        data[totalPos++] = blockDecoder.data(i);
                    }
                }

            }
        });
        int readFrames = 0;
        while (readFrames == 0) {
            readFrames = subscription.poll(reassemblingFragmentHandler, 1);
        }
        return data;
    }

    private void sendBiomeDataRequest(int x, int y, int width, int height, boolean useQuarterResolution) {
        int bufferLength = MessageHeaderEncoder.ENCODED_LENGTH + BiomeDataRequestEncoder.BLOCK_LENGTH;
        while (true) {
            final long result = publication.tryClaim(bufferLength, bufferClaim);
            if (result > 0) {
                biomeDataRequestEncoder
                        .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder).x(x).y(y)
                        .width(width).height(height)
                        .useQuarterResolution(useQuarterResolution ? BooleanType.T : BooleanType.F);
                bufferClaim.commit();
                break;
            }
        }
    }

    @Override
    public void createWorld(long seed, WorldType worldType, String generatorOptions)
            throws MinecraftInterfaceException {
        sendCreateWorldRequest(mapWorldType(worldType), generatorOptions, seed);
        
        // now load the biome list from the remote instance and adjust ours accordingly
        sendBiomeListRequest();
        List<BiomeEntry> biomeList = readBiomeListResponse();
        Random colors = new Random();
        for (BiomeEntry entry : biomeList) {
            maybeAddBiome(colors, entry);
        }
        // regenerate the biome colors. required to avoid UnknownBiomeIndexExceptions
        biomeProfileSelection.set(BiomeProfile.getDefaultProfile());

    }

    private List<BiomeEntry> readBiomeListResponse() {
        List<BiomeEntry> result = new ArrayList<>();
        FragmentHandler reassemblingFragmentHandler = new FragmentAssembler(new FragmentHandler() {

            @Override
            public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
                messageHeaderDecoder.wrap(buffer, offset);
                final int schemaId = messageHeaderDecoder.schemaId();
                if (schemaId != MessageHeaderDecoder.SCHEMA_ID) {
                    throw new RemoteCommunicationException(
                            "expected schemaId=" + MessageHeaderDecoder.SCHEMA_ID + ", actual=" + schemaId);
                }
                final int templateId = messageHeaderDecoder.templateId();
                if (templateId != BiomeListResponseDecoder.TEMPLATE_ID) {
                    throw new RemoteCommunicationException(
                            "expected templateId=" + BiomeListResponseDecoder.TEMPLATE_ID + ", actual=" + templateId);
                }
                biomeListResponseDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                        messageHeaderDecoder.blockLength(), messageHeaderDecoder.version());
                for (BiomeEntryDecoder biomeEntryDecoder : biomeListResponseDecoder.biomeEntry()) {
                    result.add(new BiomeEntry(biomeEntryDecoder.biomeId(), biomeEntryDecoder.biomeName()));
                }
            }
        });
        int readFrames = 0;
        while (readFrames == 0) {
            readFrames = subscription.poll(reassemblingFragmentHandler, 1);
        }
        return result;
    }

    private void sendBiomeListRequest() {
        int bufferLength = MessageHeaderEncoder.ENCODED_LENGTH + BiomeListRequestEncoder.BLOCK_LENGTH;
        while (true) {
            final long result = publication.tryClaim(bufferLength, bufferClaim);
            if (result > 0) {
                biomeListRequestEncoder
                        .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder);
                bufferClaim.commit();
                break;
            }
        }
    }

    private void sendCreateWorldRequest(String worldType, String generatorOptions, long seed) {
        createWorldRequestEncoder.wrapAndApplyHeader(buffer, 0, messageHeaderEncoder).worldType(worldType)
                .generatorOptions(generatorOptions).seed(seed);
        publication.offer(buffer);
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
            amidst.mojangapi.world.biome.Biome.getByIndex(b.biomeId);
        } catch (UnknownBiomeIndexException e) {
            // this constructor call has side effects.
            new amidst.mojangapi.world.biome.Biome(b.biomeName, b.biomeId,
                    BiomeColor.from(random.nextInt(255), random.nextInt(255), random.nextInt(255)), BiomeType.OCEAN);
        }
    }

    @Override
    public RecognisedVersion getRecognisedVersion() {
        return RecognisedVersion.UNKNOWN;
    }
    
    private static class BiomeEntry {
        int biomeId;
        String biomeName;
        public BiomeEntry(int biomeId, String biomeName) {
            super();
            this.biomeId = biomeId;
            this.biomeName = biomeName;
        }
        
    }
}
