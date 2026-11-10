package dev.jblcube.server;

import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.net.ModPayloads;
import dev.jblcube.source.SourceResolver;
import dev.jblcube.source.TrackCache;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server-side half of the audio pipeline.
 *
 * <p>When a player wants a song, the server serves it from its own cache. If it is not cached, the
 * server fetches and transcodes it once (auto-downloading yt-dlp/ffmpeg the first time) and then
 * streams it to everyone who asked. A client that already has the mp3 can also seed the cache by
 * uploading it, which is the fallback that keeps things working on a rented panel with no GitHub or
 * YouTube access: the DJ fetches locally and uploads, and every listener is served from that upload.
 */
public final class ServerAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/server-audio");

    /** How long a slice of the file can be: small enough to not choke the network, big enough to not
     * need thousands of packets for a song. */
    public static final int CHUNK_BYTES = 32 * 1024;

    /** How many slices one stream may hand out per tick, so a few listeners stay under ~250KB/s each. */
    private static final int CHUNKS_PER_TICK = 4;

    /** How many requests may wait for a song that has not been cached yet. */
    private static final int MAX_PENDING = 64;

    /** How long a request may sit waiting before it is dropped (a touch past the client timeout). */
    private static final long PENDING_TIMEOUT_MILLIS = 200_000L;

    /** A failed server-side fetch is not retried again until this much time has passed. */
    private static final long FETCH_FAIL_COOLDOWN_MILLIS = 60_000L;

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "JBL Cube Server Audio");
        thread.setDaemon(true);
        return thread;
    });

    /** In-flight stream per request id, keyed by the id the requesting client chose. */
    private static final ConcurrentMap<Integer, StreamTask> STREAMS = new ConcurrentHashMap<>();

    /** In-flight upload per id, keyed by the id the uploading client chose. */
    private static final ConcurrentMap<Integer, UploadSink> UPLOADS = new ConcurrentHashMap<>();

    /** Requests waiting for a song, keyed by source URL. */
    private static final ConcurrentMap<String, List<Pending>> PENDING = new ConcurrentHashMap<>();

    /** URLs the server is currently fetching, to avoid downloading the same song twice. */
    private static final ConcurrentMap<String, Boolean> FETCHING = new ConcurrentHashMap<>();

    /** When a server-side fetch last failed for a URL, so it is not retried in a tight loop. */
    private static final ConcurrentMap<String, Long> FAILED = new ConcurrentHashMap<>();

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

    /**
     * Serves one song to a player. The mp3 comes from the server's cache (seeded by a previous fetch
     * or by a DJ's upload). On a cache miss the server tries to fetch it; if that fails it keeps the
     * listener waiting for an upload while telling the DJ (the {@code owner}) to fall back to its own
     * tools.
     */
    public static void requestAudio(int id, String url, boolean owner, ServerPlayer requester) {
        if (STREAMS.containsKey(id)) {
            return;
        }
        Optional<Path> cached = cachedMp3(url);
        if (cached.isPresent()) {
            startStream(id, requester, cached.get());
            return;
        }
        List<Pending> waiters = PENDING.computeIfAbsent(url, k -> new ArrayList<>());
        synchronized (waiters) {
            if (waiters.size() >= MAX_PENDING) {
                failRequest(id, requester);
                return;
            }
            waiters.add(new Pending(id, requester, owner, System.currentTimeMillis()));
        }
        tryFetch(url);
    }

    /** Accumulates an mp3 upload and, on the final chunk, writes it to the cache and serves waiters. */
    public static void receiveUploadChunk(int id, String url, byte[] data, boolean last) {
        if (url == null || url.isBlank() || data == null) {
            return;
        }
        UploadSink sink = UPLOADS.computeIfAbsent(id, k -> new UploadSink(url));
        if (!sink.accept(data, last)) {
            return;
        }
        UPLOADS.remove(id);
        sink.finish();
    }

    private static Optional<Path> cachedMp3(String url) {
        TrackCache.ensureDirectory();
        return TrackCache.cachedFile(url, ".mp3");
    }

    /** Fetches and transcodes a song once, then hands it to everyone still waiting on it. */
    private static void tryFetch(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastFail = FAILED.get(url);
        if (lastFail != null && now - lastFail < FETCH_FAIL_COOLDOWN_MILLIS) {
            return;
        }
        if (FETCHING.putIfAbsent(url, Boolean.TRUE) != null) {
            return;
        }
        WORKERS.execute(() -> {
            boolean ok;
            try {
                Optional<Path> file = SourceResolver.fetchAudioFile(new MusicBoxTrack(url, "", 0));
                ok = file.isPresent();
                if (ok) {
                    serveWaiters(url, file.get());
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Server could not fetch {}", url, e);
                ok = false;
            } finally {
                FETCHING.remove(url);
            }
            if (!ok) {
                FAILED.put(url, System.currentTimeMillis());
                // DJs fall back to their own tools; listeners keep waiting for an upload.
                failOwners(url);
            }
        });
    }

    /** Starts a stream on the server thread, so it always runs inside the game loop. */
    private static void startStream(int id, ServerPlayer requester, Path path) {
        requester.server.execute(() -> {
            if (!requester.isRemoved() && requester.connection != null) {
                STREAMS.put(id, new StreamTask(id, requester, path));
            }
        });
    }

    /** Hands a freshly cached file to every player that was waiting on it. */
    private static void serveWaiters(String url, Path path) {
        List<Pending> waiters = PENDING.remove(url);
        if (waiters == null) {
            return;
        }
        synchronized (waiters) {
            for (Pending pending : waiters) {
                startStream(pending.id(), pending.requester(), path);
            }
        }
    }

    /** Tells the DJs waiting on a URL to fall back to their own tools, leaving listeners waiting. */
    private static void failOwners(String url) {
        List<Pending> waiters = PENDING.get(url);
        if (waiters == null) {
            return;
        }
        synchronized (waiters) {
            waiters.removeIf(pending -> {
                if (pending.owner()) {
                    failRequest(pending.id(), pending.requester());
                    return true;
                }
                return false;
            });
        }
        if (waiters.isEmpty()) {
            PENDING.remove(url, waiters);
        }
    }

    private static void failRequest(int id, ServerPlayer requester) {
        requester.server.execute(() -> sendIfConnected(requester, new ModPayloads.AudioFailedPayload(id)));
    }

    /** Pushes slices of every in-flight stream and expires stale waiters. Runs on the server thread. */
    public static void tick() {
        if (!STREAMS.isEmpty()) {
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

        long now = System.currentTimeMillis();
        Iterator<List<Pending>> waiters = PENDING.values().iterator();
        while (waiters.hasNext()) {
            List<Pending> list = waiters.next();
            synchronized (list) {
                list.removeIf(pending -> now - pending.addedAt() > PENDING_TIMEOUT_MILLIS);
            }
            if (list.isEmpty()) {
                waiters.remove();
            }
        }
        FAILED.values().removeIf(failedAt -> now - failedAt > FETCH_FAIL_COOLDOWN_MILLIS);
    }

    /** Drops everything still owed to a player that left. */
    public static void forget(ServerPlayer player) {
        STREAMS.values().removeIf(stream -> stream.requester == player);
        for (List<Pending> list : PENDING.values()) {
            synchronized (list) {
                list.removeIf(pending -> pending.requester() == player);
            }
        }
        PENDING.values().removeIf(List::isEmpty);
    }

    private static void sendIfConnected(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player != null && !player.isRemoved() && player.connection != null) {
            player.connection.send(payload);
        }
    }

    /** A player waiting for a song that has not reached the server cache yet. */
    private record Pending(int id, ServerPlayer requester, boolean owner, long addedAt) {
    }

    /** Reassembles an mp3 uploaded by a client. */
    private static final class UploadSink {
        private final String url;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);

        private UploadSink(String url) {
            this.url = url;
        }

        /** Appends a slice. Returns true when the upload is complete and should be finalised. */
        private boolean accept(byte[] data, boolean last) {
            synchronized (bytes) {
                bytes.write(data, 0, data.length);
            }
            return last;
        }

        private void finish() {
            byte[] all = bytes.toByteArray();
            WORKERS.execute(() -> {
                try {
                    TrackCache.ensureDirectory();
                    Path target = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".mp3");
                    // Never clobber an existing, verified cache entry with a partial upload.
                    if (!Files.exists(target)) {
                        Path partial = target.resolveSibling(target.getFileName() + ".part");
                        Files.write(partial, all);
                        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    serveWaiters(url, target);
                } catch (IOException e) {
                    // The upload itself is broken; leave waiters pending so another fetch or upload
                    // can still satisfy them before their own timeout.
                    LOGGER.warn("Could not store uploaded audio for {}", url, e);
                }
            });
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
