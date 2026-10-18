package dev.jblcube.source;

import dev.jblcube.data.MusicBoxTrack;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * On-disk cache of downloaded audio, keyed by source URL. On a dedicated server the game dir is the
 * server folder, so the same cache logic serves both singleplayer and a friend's box.
 */
public final class TrackCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/cache");
    private static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private TrackCache() {
    }

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("jblcube").resolve("cache");
    }

    public static String cacheKey(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<Path> cachedFile(String url, String extension) {
        Path file = directory().resolve(cacheKey(url) + extension);
        try {
            long size = Files.size(file);
            if (size > 512) {
                return Optional.of(file);
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    public static void ensureDirectory() {
        try {
            Files.createDirectories(directory());
        } catch (IOException e) {
            LOGGER.warn("Could not create music box cache directory", e);
        }
    }

    /**
     * Downloads a direct media URL into the cache and returns the stored file.
     */
    public static Optional<Path> download(String url, String extension) throws IOException, InterruptedException {
        ensureDirectory();
        Path target = directory().resolve(cacheKey(url) + extension);
        Path partial = directory().resolve(cacheKey(url) + extension + ".part");

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "JblCube/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }

        try (InputStream body = response.body()) {
            Files.copy(body, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(target);
    }

    public static Optional<String> downloadText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "JblCube/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            byte[] body = response.body();
            if (body.length > MAX_PLAYLIST_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.debug("Failed to download playlist {}", url, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public static MusicBoxTrack trackOf(String url, String title, int durationMillis) {
        return new MusicBoxTrack(url, titleOrUrl(url, title), durationMillis);
    }

    static String titleOrUrl(String url, String title) {
        if (title != null && !title.isBlank()) {
            return UrlValidator.sanitizeTitle(title);
        }

        int slash = url.lastIndexOf('/');
        String tail = slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : url;
        int dot = tail.lastIndexOf('.');
        String cleaned = dot > 0 ? tail.substring(0, dot) : tail;
        return UrlValidator.sanitizeTitle(cleaned.replace('+', ' ').replace('_', ' ').replace('%', ' '));
    }
}