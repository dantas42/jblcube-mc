package dev.jblcube.server;

import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.net.ModPayloads;
import dev.jblcube.source.SourceResolver;
import dev.jblcube.source.TrackCache;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server-side half of the "no install needed for friends" pipeline.
 *
 * <p>A client without yt-dlp/ffmpeg asks the server to expand a pasted URL into tracks or to
 * download, transcode and stream a song back. Resolution runs on a worker thread; the mp3 bytes are
 * then pushed to the requester a few packets per tick, so no heavy work ever runs on the game
 * thread. The converted files live in the normal {@link TrackCache}, so a song requested by several
 * friends is downloaded once and streamed from disk for each of them.
 */
public final class ServerAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/server-audio");

    /** How long a slice of the file can be: small enough to not choke the network, big enough to not
     * need thousands of packets for a song. */
    public static final int CHUNK_BYTES = 32 * 1024;

    /** How many slices one stream may hand out per tick, so a few listeners stay under ~250KB/s each. */
    private static final int CHUNKS_PER_TICK = 4;

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "JBL Cube Server Audio");
        thread.setDaemon(true);
        return thread;
    });

    /** In-flight stream per request id, keyed by the id the requesting client chose. */
    private static final ConcurrentMap<Integer, StreamTask> STREAMS = new ConcurrentHashMap<>();

    private ServerAudio() {
    }

    /** Expands a pasted URL on a worker and sends the track list back to the requester. */
    public static void expand(int id, String input, ServerPlayer requester) {
        WORKERS.execute(() -> {
            List<MusicBoxTrack> tracks;
            try {
                tracks = SourceResolver.expand(input);
            } catch (RuntimeException e) {
                LOGGER.warn("Server could not expand {}", input, e);
                tracks = List.of();
            }
            List<MusicBoxTrack> result = tracks;
            requester.server.execute(() -> sendIfConnected(requester, new ModPayloads.ExpandResultPayload(id, result)));
        });
    }

    /** Downloads and converts one song, then starts streaming it to the requester. */
    public static void requestAudio(int id, String url, ServerPlayer requester) {
        if (STREAMS.containsKey(id)) {
            return;
        }
        WORKERS.execute(() -> {
            Optional<Path> file = SourceResolver.fetchAudioFile(new MusicBoxTrack(url, "", 0));
            if (file.isEmpty()) {
                requester.server.execute(() -> sendIfConnected(requester, new ModPayloads.AudioFailedPayload(id)));
                return;
            }
            Path path = file.get();
            requester.server.execute(() -> {
                if (!requester.isRemoved() && requester.connection != null) {
                    STREAMS.put(id, new StreamTask(id, requester, path));
                }
            });
        });
    }

    /** Pushes a few slices of every in-flight stream to its listener. Runs on the server thread. */
    public static void tick() {
        if (STREAMS.isEmpty()) {
            return;
        }
        Iterator<StreamTask> iterator = STREAMS.values().iterator();
        while (iterator.hasNext()) {
            StreamTask stream = iterator.next();
            if (stream.requester.isRemoved() || stream.requester.connection == null) {
                iterator.remove();
                continue;
            }
            if (!stream.step()) {
                iterator.remove();
            }
        }
    }

    /** Drops everything still owed to a player that left. */
    public static void forget(ServerPlayer player) {
        STREAMS.values().removeIf(stream -> stream.requester == player);
    }

    private static void sendIfConnected(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player != null && !player.isRemoved() && player.connection != null) {
            player.connection.send(payload);
        }
    }

    private static final class StreamTask {
        private final int id;
        private final ServerPlayer requester;
        private final Path file;
        private long offset;
        private boolean last;
        private boolean done;

        private StreamTask(int id, ServerPlayer requester, Path file) {
            this.id = id;
            this.requester = requester;
            this.file = file;
        }

        /**
         * Sends up to {@link #CHUNKS_PER_TICK} slices. Returns true while there is still data owed.
         */
        private boolean step() {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(offset);
                for (int i = 0; i < CHUNKS_PER_TICK && !done; i++) {
                    long remaining = raf.length() - offset;
                    if (remaining <= 0 && i > 0) {
                        done = true;
                        break;
                    }
                    int want = (int) Math.min(CHUNK_BYTES, remaining);
                    byte[] data = new byte[Math.max(1, want)];
                    int read = raf.read(data);
                    if (read <= 0) {
                        if (i == 0) {
                            // Empty or already fully sent file: just close the stream.
                            byte[] empty = new byte[0];
                            requester.connection.send(new ModPayloads.AudioChunkPayload(id, empty, true));
                        }
                        done = true;
                        break;
                    }
                    offset += read;
                    boolean isLast = offset >= raf.length();
                    byte[] slice = read == data.length ? data : java.util.Arrays.copyOf(data, read);
                    last = isLast;
                    requester.connection.send(new ModPayloads.AudioChunkPayload(id, slice, isLast));
                    if (isLast) {
                        done = true;
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Could not stream {} to {}: {}", file, requester.getName().getString(), e.toString());
                requester.connection.send(new ModPayloads.AudioFailedPayload(id));
                done = true;
            }
            return !done;
        }
    }
}