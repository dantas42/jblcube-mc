package dev.jblcube.client;

import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.net.ModPayloads;
import dev.jblcube.source.SourceResolver;
import dev.jblcube.source.TrackCache;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client half of the audio pipeline. The owner (the DJ whose box is playing) and the listeners
 * both ask the server for the mp3, which the server serves from its cache or fetches once. When the
 * server cannot fetch, the DJ falls back to its own tools (auto-downloaded on first use) and seeds
 * the server for everyone else, so listeners never need yt-dlp/ffmpeg themselves.
 */
public final class NetworkAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/network-audio");

    private static final long EXPAND_TIMEOUT_MILLIS = 60_000L;

    /** Size of one slice when a client seeds the server with an mp3 it already fetched. */
    private static final int UPLOAD_CHUNK_BYTES = 32 * 1024;

    public static final NetworkAudio INSTANCE = new NetworkAudio();

    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentMap<Integer, CompletableFuture<List<MusicBoxTrack>>> expands = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, AudioSink> sinks = new ConcurrentHashMap<>();
    /** URLs already sent to the server this session, so a seek or re-play does not re-upload. */
    private final Set<String> uploadedUrls = ConcurrentHashMap.newKeySet();

    private NetworkAudio() {
    }

    /** Tries to expand a pasted URL locally and falls back to asking the server. */
    public List<MusicBoxTrack> expand(String input) {
        List<MusicBoxTrack> local = SourceResolver.expand(input);
        if (!local.isEmpty()) {
            return local;
        }
        int id = ids.incrementAndGet();
        CompletableFuture<List<MusicBoxTrack>> future = new CompletableFuture<>();
        expands.put(id, future);
        PacketDistributor.sendToServer(new ModPayloads.ExpandUrlPayload(id, input));
        try {
            return future.get(EXPAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.debug("Server did not expand {}", input, e);
            expands.remove(id);
            return List.of();
        }
    }

    /**
     * Returns an audio file for the track.
     *
     * <p>Both the owner and the listeners ask the server first, which serves its cache or fetches the
     * source. When the server cannot deliver, the <b>owner</b> (the DJ) falls back to its local tools
     * (auto-downloaded once) and seeds the server, while a <b>listener</b> just gives up — it never
     * spawns yt-dlp/ffmpeg on its own machine.
     */
    public Optional<Path> fetchFile(MusicBoxTrack track, boolean owner, long timeoutMillis) {
        Optional<Path> cached = SourceResolver.cachedFile(track);
        if (cached.isPresent()) {
            if (owner) {
                upload(track.url(), cached.get());
            }
            return cached;
        }
        // Ask the server first: it serves from its cache or fetches the source, so neither the DJ
        // nor the listeners have to download yt-dlp/ffmpeg.
        Optional<Path> fromServer = fetchFromServer(track.url(), timeoutMillis, owner);
        if (fromServer.isPresent()) {
            return fromServer;
        }
        // Last resort for the DJ: fetch locally (auto-downloads the tools once) and seed the server.
        if (owner) {
            Optional<Path> fetched = SourceResolver.fetchAudioFile(track);
            if (fetched.isPresent()) {
                upload(track.url(), fetched.get());
                return fetched;
            }
        }
        return Optional.empty();
    }

    /**
     * Seeds the server with an mp3 this machine already has, once per URL per session. Runs on a
     * daemon thread so a slow upload never blocks playback or the game thread.
     */
    private void upload(String url, Path path) {
        if (url == null || url.isBlank() || !uploadedUrls.add(url)) {
            return;
        }
        Thread thread = new Thread(() -> {
            int id = ids.incrementAndGet();
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[UPLOAD_CHUNK_BYTES];
                int read = in.read(buffer);
                while (read != -1) {
                    byte[] chunk = Arrays.copyOf(buffer, read);
                    int next = in.read(buffer);
                    boolean last = next == -1;
                    PacketDistributor.sendToServer(new ModPayloads.UploadAudioPayload(id, url, chunk, last));
                    read = next;
                }
            } catch (IOException e) {
                LOGGER.warn("Could not upload {} to the server", url, e);
            }
        }, "Music Box Upload");
        thread.setDaemon(true);
        thread.start();
    }

    private Optional<Path> fetchFromServer(String url, long timeoutMillis, boolean owner) {
        int id = ids.incrementAndGet();
        TrackCache.ensureDirectory();
        Path target = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".mp3");
        AudioSink sink = new AudioSink(target);
        sinks.put(id, sink);
        PacketDistributor.sendToServer(new ModPayloads.RequestAudioPayload(id, url, owner));
        try {
            return Optional.ofNullable(sink.future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            sinks.remove(id);
            LOGGER.warn("Server did not deliver audio for {}", url);
            return Optional.empty();
        }
    }

    public void receiveExpandResult(int id, List<MusicBoxTrack> tracks) {
        CompletableFuture<List<MusicBoxTrack>> future = expands.remove(id);
        if (future != null) {
            future.complete(tracks);
        }
    }

    public void receiveAudioChunk(int id, byte[] data, boolean last) {
        AudioSink sink = sinks.get(id);
        if (sink == null) {
            return;
        }
        sink.accept(data, last);
        if (last) {
            sinks.remove(id);
        }
    }

    public void receiveAudioFailed(int id) {
        AudioSink sink = sinks.remove(id);
        if (sink != null) {
            sink.failed();
        }
    }

    private static final class AudioSink {
        private final CompletableFuture<Path> future = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        /** Written under the URL key, so the next play of this track hits the local cache. */
        private final Path target;

        private AudioSink(Path target) {
            this.target = target;
        }

        private void accept(byte[] data, boolean last) {
            try {
                synchronized (bytes) {
                    bytes.write(data);
                    if (last) {
                        future.complete(store());
                    }
                }
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        }

        private Path store() throws IOException {
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.write(partial, bytes.toByteArray());
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        }

        private void failed() {
            future.completeExceptionally(new IOException("Server could not fetch the audio"));
        }
    }
}