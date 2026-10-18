package dev.jblcube.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jblcube.data.MusicBoxTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns whatever the player pasted into the screen into a list of playable tracks.
 * Order of preference: yt-dlp (videos and playlists), remote playlists (m3u/pls),
 * direct media links, then Invidious as a last resort for YouTube.
 *
 * <p>Runs on both sides now: a client without the tools asks the (singleplayer or dedicated) server
 * to do this for it, so nobody has to install yt-dlp and ffmpeg just to add a song.
 */
public final class SourceResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/resolver");

    private static final int MAX_TRACKS = UrlValidator.MAX_TRACKS;
    private static final long YTDLP_PLAYLIST_TIMEOUT_SECONDS = 90;
    private static final long YTDLP_DOWNLOAD_TIMEOUT_SECONDS = 300;
    private static final String[] INVIDIOUS_INSTANCES = {
            "yewtu.be",
            "inv.nadeko.net",
            "invidious.privacyredirect.com",
            "inv.tux.pizza"
    };

    private SourceResolver() {
    }

    public static List<MusicBoxTrack> expand(String rawInput) {
        return UrlValidator.parse(rawInput)
                .map(SourceResolver::expandUri)
                .orElseGet(List::of);
    }

    private static List<MusicBoxTrack> expandUri(URI uri) {
        if (UrlValidator.isPlaylistUri(uri)) {
            List<MusicBoxTrack> remote = expandRemotePlaylist(uri);
            if (!remote.isEmpty()) {
                return remote;
            }
        }

        if (UrlValidator.isYouTubeUri(uri)) {
            List<MusicBoxTrack> ytDlp = expandWithYtDlp(uri.toString());
            if (!ytDlp.isEmpty()) {
                return ytDlp;
            }
            return expandWithInvidious(uri);
        }

        if (UrlValidator.isPlaylistUri(uri)) {
            LOGGER.warn("Could not read remote playlist {}", uri);
            return List.of();
        }

        List<MusicBoxTrack> direct = new ArrayList<>();
        direct.add(TrackCache.trackOf(uri.toString(), null, 0));
        return List.copyOf(direct);
    }

    private static List<MusicBoxTrack> expandRemotePlaylist(URI uri) {
        return TrackCache.downloadText(uri.toString())
                .map(PlaylistParser::parse)
                .map(entries -> entries.stream()
                        .filter(entry -> UrlValidator.parse(entry.url()).isPresent())
                        .limit(MAX_TRACKS)
                        .map(entry -> TrackCache.trackOf(entry.url(), entry.title(), 0))
                        .toList())
                .orElseGet(List::of);
    }

    private static List<MusicBoxTrack> expandWithYtDlp(String url) {
        if (!ExternalTools.isYtDlpAvailable()) {
            LOGGER.warn("yt-dlp was not found on PATH; set the {} environment variable", ExternalTools.YTDLP_ENV);
            return List.of();
        }

        Optional<String> output = ExternalTools.run(List.of(
                ExternalTools.ytDlpCommand(),
                "--dump-single-json",
                "--flat-playlist",
                "--no-warnings",
                "--no-progress",
                "--ignore-errors",
                url), YTDLP_PLAYLIST_TIMEOUT_SECONDS);

        return output.map(SourceResolver::parseYtDlpJson).orElseGet(List::of);
    }

    private static List<MusicBoxTrack> parseYtDlpJson(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return List.of();
            }
            JsonObject object = root.getAsJsonObject();

            JsonArray entries = object.has("_type") && "playlist".equals(stringOf(object, "_type"))
                    && object.has("entries") ? object.getAsJsonArray("entries") : null;

            if (entries == null) {
                MusicBoxTrack track = TrackCache.trackOf(urlOrEmpty(object), stringOf(object, "title"),
                        intOf(object, "duration"));
                return track.url().isBlank() ? List.of() : List.of(track);
            }

            List<MusicBoxTrack> tracks = new ArrayList<>();
            for (JsonElement element : entries) {
                if (tracks.size() >= MAX_TRACKS || !element.isJsonObject()) {
                    break;
                }
                JsonObject entry = element.getAsJsonObject();
                String entryUrl = urlOrEmpty(entry);
                if (entryUrl.isBlank() && entry.has("id")) {
                    entryUrl = "https://www.youtube.com/watch?v=" + entry.get("id").getAsString();
                }
                if (entryUrl.isBlank()) {
                    continue;
                }
                tracks.add(TrackCache.trackOf(entryUrl, stringOf(entry, "title"), intOf(entry, "duration")));
            }
            return List.copyOf(tracks);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not parse yt-dlp output", e);
            return List.of();
        }
    }

    private static List<MusicBoxTrack> expandWithInvidious(URI uri) {
        Optional<String> videoId = UrlValidator.youTubeVideoId(uri);
        if (videoId.isEmpty()) {
            return List.of();
        }

        for (String instance : INVIDIOUS_INSTANCES) {
            Optional<JsonObject> video = fetchJson("https://" + instance + "/api/v1/videos/" + videoId.get());
            if (video.isEmpty()) {
                continue;
            }

            JsonObject object = video.get();
            String title = stringOf(object, "title");
            int durationSeconds = intOf(object, "lengthSeconds");
            String url = invidiousAudioUrl(object);
            if (url.isBlank()) {
                LOGGER.warn("Invidious instance {} returned no audio stream", instance);
                continue;
            }
            return List.of(TrackCache.trackOf(url, title, durationSeconds * 1000));
        }

        LOGGER.warn("No Invidious instance could resolve {}", uri);
        return List.of();
    }

    private static Optional<JsonObject> fetchJson(String url) {
        return TrackCache.downloadText(url).map(text -> {
            try {
                JsonElement element = JsonParser.parseString(text);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }).filter(java.util.Objects::nonNull);
    }

    private static String invidiousAudioUrl(JsonObject video) {
        if (!video.has("adaptiveFormats") || !video.get("adaptiveFormats").isJsonArray()) {
            return "";
        }

        String best = "";
        int bestBitrate = -1;
        for (JsonElement element : video.getAsJsonArray("adaptiveFormats")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject format = element.getAsJsonObject();
            String mime = stringOf(format, "mimeType");
            if (!mime.startsWith("audio") || mime.contains("codecs=\"opus\"")) {
                continue;
            }
            int bitrate = intOf(format, "bitrate");
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = stringOf(format, "url");
            }
        }
        return best;
    }

    /**
     * Makes sure a local audio file exists for the given track, downloading it if needed.
     *
     * @return the audio file, or empty when the source cannot be fetched
     */
    public static Optional<Path> fetchAudioFile(MusicBoxTrack track) {
        TrackCache.ensureDirectory();
        String url = track.url();

        // Cache first, always. A YouTube link has no usable file extension ("watch?v=..."), so the
        // old extension check skipped the cache for it and yt-dlp was spawned again on every play,
        // every pause and every resume. yt-dlp always writes mp3, so probe that name too.
        Optional<Path> cached = TrackCache.cachedFile(url, extensionOf(url));
        if (cached.isPresent()) {
            return cached;
        }
        cached = TrackCache.cachedFile(url, ".mp3");
        if (cached.isPresent()) {
            return cached;
        }

        if (UrlValidator.isYouTubeUri(URI.create(url))) {
            return downloadWithYtDlp(url);
        }

        if (ExternalTools.isFfmpegAvailable()) {
            return downloadDirect(url);
        }

        return TrackCache.cachedFile(url, extensionOf(url));
    }

    /** True when the track audio is already on disk, so starting it does not need the network. */
    public static boolean isCached(MusicBoxTrack track) {
        if (track == null || track.url().isBlank()) {
            return false;
        }
        String url = track.url();
        return TrackCache.cachedFile(url, extensionOf(url)).isPresent()
                || TrackCache.cachedFile(url, ".mp3").isPresent();
    }

    private static Optional<Path> downloadWithYtDlp(String url) {
        if (!ExternalTools.isYtDlpAvailable()) {
            LOGGER.warn("yt-dlp is required to download {}", url);
            return Optional.empty();
        }

        TrackCache.ensureDirectory();
        Path outputTemplate = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".%(ext)s");
        Optional<String> result = ExternalTools.run(List.of(
                ExternalTools.ytDlpCommand(),
                "--no-playlist",
                "--no-warnings",
                "--no-progress",
                "--extract-audio",
                "--audio-format",
                "mp3",
                "--audio-quality",
                "128K",
                "--output",
                outputTemplate.toString(),
                url), YTDLP_DOWNLOAD_TIMEOUT_SECONDS);

        if (result.isEmpty()) {
            return Optional.empty();
        }

        Path mp3 = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".mp3");
        return ExternalTools.exists(mp3) ? Optional.of(mp3) : Optional.empty();
    }

    private static Optional<Path> downloadDirect(String url) {
        String extension = extensionOf(url);
        try {
            Optional<Path> direct = TrackCache.download(url, extension);
            if (direct.isPresent()) {
                return direct;
            }
        } catch (IOException e) {
            LOGGER.warn("Direct download of {} failed", url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Direct download of {} was interrupted", url);
        }
        return convertWithFfmpeg(url);
    }

    private static Optional<Path> convertWithFfmpeg(String url) {
        if (!ExternalTools.isFfmpegAvailable()) {
            return Optional.empty();
        }

        TrackCache.ensureDirectory();
        Path target = TrackCache.directory().resolve(TrackCache.cacheKey(url) + ".mp3");
        Path source = null;
        try {
            source = TrackCache.download(url, ".media").orElseThrow();
            ExternalTools.run(List.of(
                    ExternalTools.ffmpegCommand(),
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", source.toString(),
                    "-vn",
                    "-acodec", "libmp3lame",
                    "-q:a", "4",
                    target.toString()), 300);
            return ExternalTools.exists(target) ? Optional.of(target) : Optional.empty();
        } catch (IOException e) {
            LOGGER.warn("Could not transcode {}", url, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Transcoding {} was interrupted", url);
            return Optional.empty();
        } finally {
            if (source != null) {
                try {
                    java.nio.file.Files.deleteIfExists(source);
                } catch (IOException e) {
                    LOGGER.debug("Could not delete temporary file {}", source, e);
                }
            }
        }
    }

    private static String fileNameOf(String url) {
        int slash = url.lastIndexOf('/');
        int query = url.indexOf('?', slash + 1);
        if (query > 0) {
            return url.substring(slash + 1, query);
        }
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static String extensionOf(String url) {
        String name = fileNameOf(url).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : ".mp3";
    }

    private static String stringOf(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String urlOrEmpty(JsonObject object) {
        String url = stringOf(object, "webpage_url");
        if (url.isBlank()) {
            url = stringOf(object, "original_url");
        }
        if (url.isBlank()) {
            url = stringOf(object, "url");
        }
        return url;
    }
}