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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client half of the "no install needed" pipeline: tries the local resolver first (cached files,
 * direct mp3 links or a machine that does have yt-dlp), and when that cannot produce audio it asks
 * the server to expand the URL or convert and stream the song.
 */
public final class NetworkAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/network-audio");

    private static final long EXPAND_TIMEOUT_MILLIS = 60_000L;
    private static final long AUDIO_TIMEOUT_MILLIS = 180_000L;

    public static final NetworkAudio INSTANCE = new NetworkAudio();

    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentMap<Integer, CompletableFuture<List<MusicBoxTrack>>> expands = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, AudioSink> sinks = new ConcurrentHashMap<>();

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
     * Returns an audio file for the track: from the local cache or tools when possible, otherwise by
     * asking the server to download and convert it and streaming the result back.
     */
    public Optional<Path> fetchFile(MusicBoxTrack track) {
        Optional<Path> local = SourceResolver.fetchAudioFile(track);
        if (local.isPresent()) {
            return local;
        }
        return fetchFromServer(track.url());
    }

    private Optional<Path> fetchFromServer(String url) {
        int id = ids.incrementAndGet();
        TrackCache.ensureDirectory();
        Path target = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".mp3");
        AudioSink sink = new AudioSink(target);
        sinks.put(id, sink);
        PacketDistributor.sendToServer(new ModPayloads.RequestAudioPayload(id, url));
        try {
            return Optional.ofNullable(sink.future.get(AUDIO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
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