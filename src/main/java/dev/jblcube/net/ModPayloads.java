package dev.jblcube.net;

import dev.jblcube.data.MusicBoxCodecs;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.source.UrlValidator;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;
import java.util.UUID;

public final class ModPayloads {
    public static final StreamCodec<ByteBuf, List<MusicBoxTrack>> TRACK_LIST_CODEC =
            MusicBoxCodecs.TRACK_LIST_STREAM_CODEC;

    public static final StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(ByteBuf buffer) {
            return new UUID(buffer.readLong(), buffer.readLong());
        }

        @Override
        public void encode(ByteBuf buffer, UUID uuid) {
            buffer.writeLong(uuid.getMostSignificantBits());
            buffer.writeLong(uuid.getLeastSignificantBits());
        }
    };

    private ModPayloads() {
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        // Bumped to 2: the server now streams audio and expands playlists, so an older jar cannot
        // keep a connection to a newer one.
        PayloadRegistrar registrar = event.registrar("2");

        registrar.playToServer(BoxAddTracksPayload.TYPE, BoxAddTracksPayload.STREAM_CODEC, ServerPayloadHandler::handleAddTracks);
        registrar.playToServer(BoxRemoveTrackPayload.TYPE, BoxRemoveTrackPayload.STREAM_CODEC, ServerPayloadHandler::handleRemoveTrack);
        registrar.playToServer(BoxClearPayload.TYPE, BoxClearPayload.STREAM_CODEC, ServerPayloadHandler::handleClear);
        registrar.playToServer(BoxSelectPayload.TYPE, BoxSelectPayload.STREAM_CODEC, ServerPayloadHandler::handleSelect);
        registrar.playToServer(BoxSettingsPayload.TYPE, BoxSettingsPayload.STREAM_CODEC, ServerPayloadHandler::handleSettings);
        registrar.playToServer(BoxStatePayload.TYPE, BoxStatePayload.STREAM_CODEC, ServerPayloadHandler::handleState);
        registrar.playToServer(CubeStatePayload.TYPE, CubeStatePayload.STREAM_CODEC, ServerPayloadHandler::handleCubeState);
        registrar.playToServer(CubeLoadStatePayload.TYPE, CubeLoadStatePayload.STREAM_CODEC,
                ServerPayloadHandler::handleCubeLoading);
        // A client that cannot fetch the audio itself (no yt-dlp/ffmpeg installed) asks the server
        // to do the heavy lifting: expand a pasted URL or convert a song and stream the mp3 back.
        // No extra install is needed on the player's machine.
        registrar.playToServer(ExpandUrlPayload.TYPE, ExpandUrlPayload.STREAM_CODEC, ServerPayloadHandler::handleExpandUrl);
        registrar.playToServer(RequestAudioPayload.TYPE, RequestAudioPayload.STREAM_CODEC,
                ServerPayloadHandler::handleRequestAudio);

        registrar.playToClient(RelayPayload.TYPE, RelayPayload.STREAM_CODEC, ClientPayloadHandler::handleRelay);
        registrar.playToClient(ExpandResultPayload.TYPE, ExpandResultPayload.STREAM_CODEC,
                ClientPayloadHandler::handleExpandResult);
        registrar.playToClient(AudioChunkPayload.TYPE, AudioChunkPayload.STREAM_CODEC,
                ClientPayloadHandler::handleAudioChunk);
        registrar.playToClient(AudioFailedPayload.TYPE, AudioFailedPayload.STREAM_CODEC,
                ClientPayloadHandler::handleAudioFailed);
    }

    /**
     * A placed cube sends its whole playlist in one message instead of the incremental item
     * payloads, because the authoritative copy lives in the block entity rather than in a stack.
     */
    /**
     * The client that is decoding a placed cube reports that it started opening the audio (or that
     * it stopped), so the server can paint the status panel yellow while it loads. Only the loading
     * flag travels; the playlist stays in {@link CubeStatePayload}.
     *
 * <p>Synced without a stream codec on purpose: this is a one-off hint per playback start, and the
 * block entity update that follows redraws the cube for everyone anyway.
     */
    public record CubeLoadStatePayload(BlockPos pos, boolean loading) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CubeLoadStatePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "cube_loading"));

        public static final StreamCodec<ByteBuf, CubeLoadStatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, CubeLoadStatePayload::pos,
                        ByteBufCodecs.BOOL, CubeLoadStatePayload::loading,
                        CubeLoadStatePayload::new);

        @Override
        public CustomPacketPayload.Type<CubeLoadStatePayload> type() {
            return TYPE;
        }
    }

    public record CubeStatePayload(BlockPos pos, MusicBoxData data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CubeStatePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "cube_state"));

        public static final StreamCodec<ByteBuf, CubeStatePayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, CubeStatePayload::pos,
                MusicBoxCodecs.DATA_STREAM_CODEC, CubeStatePayload::data,
                CubeStatePayload::new);

        @Override
        public CustomPacketPayload.Type<CubeStatePayload> type() {
            return TYPE;
        }
    }

    public record BoxAddTracksPayload(List<MusicBoxTrack> tracks, boolean playFirst) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxAddTracksPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "add_tracks"));

        public static final StreamCodec<ByteBuf, BoxAddTracksPayload> STREAM_CODEC =
                StreamCodec.composite(
                        TRACK_LIST_CODEC, BoxAddTracksPayload::tracks,
                        ByteBufCodecs.BOOL, BoxAddTracksPayload::playFirst,
                        BoxAddTracksPayload::new);

        @Override
        public CustomPacketPayload.Type<BoxAddTracksPayload> type() {
            return TYPE;
        }
    }

    public record BoxRemoveTrackPayload(int index) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxRemoveTrackPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "remove_track"));

        public static final StreamCodec<ByteBuf, BoxRemoveTrackPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BoxRemoveTrackPayload::index,
                        BoxRemoveTrackPayload::new);

        @Override
        public CustomPacketPayload.Type<BoxRemoveTrackPayload> type() {
            return TYPE;
        }
    }

    /**
     * Wipes the whole playlist in one message. The flag is there because the packet still needs a
     * field to exist on the wire: clearing always stops playback, so the server ignores it and
     * {@link dev.jblcube.data.MusicBoxData#clearTracks()} already forces {@code playing = false}.
     */
    public record BoxClearPayload(boolean playing) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxClearPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "clear_tracks"));

        public static final StreamCodec<ByteBuf, BoxClearPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, BoxClearPayload::playing,
                        BoxClearPayload::new);

        @Override
        public CustomPacketPayload.Type<BoxClearPayload> type() {
            return TYPE;
        }
    }

    public record BoxSelectPayload(int index, boolean playing) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxSelectPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "select"));

        public static final StreamCodec<ByteBuf, BoxSelectPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BoxSelectPayload::index,
                        ByteBufCodecs.BOOL, BoxSelectPayload::playing,
                        BoxSelectPayload::new);

        @Override
        public CustomPacketPayload.Type<BoxSelectPayload> type() {
            return TYPE;
        }
    }

    public record BoxSettingsPayload(float volume, boolean repeat) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxSettingsPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "settings"));

        public static final StreamCodec<ByteBuf, BoxSettingsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.FLOAT, BoxSettingsPayload::volume,
                        ByteBufCodecs.BOOL, BoxSettingsPayload::repeat,
                        BoxSettingsPayload::new);

        @Override
        public CustomPacketPayload.Type<BoxSettingsPayload> type() {
            return TYPE;
        }
    }

    public record BoxStatePayload(int index, int positionMillis, float volume, boolean repeat, boolean playing)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxStatePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "state"));

        public static final StreamCodec<ByteBuf, BoxStatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BoxStatePayload::index,
                        ByteBufCodecs.VAR_INT, BoxStatePayload::positionMillis,
                        ByteBufCodecs.FLOAT, BoxStatePayload::volume,
                        ByteBufCodecs.BOOL, BoxStatePayload::repeat,
                        ByteBufCodecs.BOOL, BoxStatePayload::playing,
                        BoxStatePayload::new);

        @Override
        public CustomPacketPayload.Type<BoxStatePayload> type() {
            return TYPE;
        }
    }

    /** The client asks the server to turn a pasted URL into a track list, so the player does not
     * need yt-dlp installed to add a YouTube video or playlist. */
    public record ExpandUrlPayload(int id, String input) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ExpandUrlPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "expand_url"));

        public static final StreamCodec<ByteBuf, ExpandUrlPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ExpandUrlPayload::id,
                ByteBufCodecs.stringUtf8(UrlValidator.MAX_URL_LENGTH * 4), ExpandUrlPayload::input,
                ExpandUrlPayload::new);

        @Override
        public CustomPacketPayload.Type<ExpandUrlPayload> type() {
            return TYPE;
        }
    }

    public record ExpandResultPayload(int id, List<MusicBoxTrack> tracks) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ExpandResultPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "expand_result"));

        public static final StreamCodec<ByteBuf, ExpandResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ExpandResultPayload::id,
                TRACK_LIST_CODEC, ExpandResultPayload::tracks,
                ExpandResultPayload::new);

        @Override
        public CustomPacketPayload.Type<ExpandResultPayload> type() {
            return TYPE;
        }
    }

    /** The client asks the server to download and convert a song and stream the file back to it. */
    public record RequestAudioPayload(int id, String url) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestAudioPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "request_audio"));

        public static final StreamCodec<ByteBuf, RequestAudioPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RequestAudioPayload::id,
                ByteBufCodecs.stringUtf8(UrlValidator.MAX_URL_LENGTH), RequestAudioPayload::url,
                RequestAudioPayload::new);

        @Override
        public CustomPacketPayload.Type<RequestAudioPayload> type() {
            return TYPE;
        }
    }

    /** A slice of the converted audio file travelling from the server to one listener. */
    public record AudioChunkPayload(int id, byte[] data, boolean last) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AudioChunkPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "audio_chunk"));

        public static final StreamCodec<ByteBuf, AudioChunkPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AudioChunkPayload::id,
                BYTE_SLICE, AudioChunkPayload::data,
                ByteBufCodecs.BOOL, AudioChunkPayload::last,
                AudioChunkPayload::new);

        @Override
        public CustomPacketPayload.Type<AudioChunkPayload> type() {
            return TYPE;
        }
    }

    /** The server could not convert the requested song, so the client shows the usual failure. */
    public record AudioFailedPayload(int id) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AudioFailedPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "audio_failed"));

        public static final StreamCodec<ByteBuf, AudioFailedPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, AudioFailedPayload::id, AudioFailedPayload::new);

        @Override
        public CustomPacketPayload.Type<AudioFailedPayload> type() {
            return TYPE;
        }
    }

    /** Reads and writes a raw byte buffer that was sent with an int length prefix. */
    public static final StreamCodec<ByteBuf, byte[]> BYTE_SLICE = new StreamCodec<>() {
        @Override
        public byte[] decode(ByteBuf buffer) {
            int length = buffer.readInt();
            byte[] data = new byte[length];
            buffer.readBytes(data);
            return data;
        }

        @Override
        public void encode(ByteBuf buffer, byte[] data) {
            buffer.writeInt(data.length);
            buffer.writeBytes(data);
        }
    };

    public record RelayPayload(UUID holder, boolean playing, String url, String title, int positionMillis,
                                float volume, double x, double y, double z) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RelayPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("jblcube", "relay"));

        public static final StreamCodec<ByteBuf, RelayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public RelayPayload decode(ByteBuf buffer) {
                UUID holder = UUID_CODEC.decode(buffer);
                boolean playing = ByteBufCodecs.BOOL.decode(buffer);
                String url = ByteBufCodecs.stringUtf8(UrlValidator.MAX_URL_LENGTH).decode(buffer);
                String title = ByteBufCodecs.stringUtf8(UrlValidator.MAX_TITLE_LENGTH).decode(buffer);
                int positionMillis = ByteBufCodecs.VAR_INT.decode(buffer);
                float volume = ByteBufCodecs.FLOAT.decode(buffer);
                double x = ByteBufCodecs.DOUBLE.decode(buffer);
                double y = ByteBufCodecs.DOUBLE.decode(buffer);
                double z = ByteBufCodecs.DOUBLE.decode(buffer);
                return new RelayPayload(holder, playing, url, title, positionMillis, volume, x, y, z);
            }

            @Override
            public void encode(ByteBuf buffer, RelayPayload payload) {
                UUID_CODEC.encode(buffer, payload.holder());
                ByteBufCodecs.BOOL.encode(buffer, payload.playing());
                ByteBufCodecs.stringUtf8(UrlValidator.MAX_URL_LENGTH).encode(buffer, payload.url());
                ByteBufCodecs.stringUtf8(UrlValidator.MAX_TITLE_LENGTH).encode(buffer, payload.title());
                ByteBufCodecs.VAR_INT.encode(buffer, payload.positionMillis());
                ByteBufCodecs.FLOAT.encode(buffer, payload.volume());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.x());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.y());
                ByteBufCodecs.DOUBLE.encode(buffer, payload.z());
            }
        };

        @Override
        public CustomPacketPayload.Type<RelayPayload> type() {
            return TYPE;
        }
    }
}
