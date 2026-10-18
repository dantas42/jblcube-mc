package dev.jblcube.source;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlValidator {
    public static final int MAX_URL_LENGTH = 512;
    public static final int MAX_TITLE_LENGTH = 160;
    public static final int MAX_TRACKS = 256;

    private static final Pattern YOUTUBE_HOSTS = Pattern.compile("(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST_EXTENSION = Pattern.compile("\\.(m3u8?|pls)(\\?.*)?$",
            Pattern.CASE_INSENSITIVE);

    private UrlValidator() {
    }

    public static Optional<URI> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }

        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return Optional.empty();
        }

        try {
            URI uri = URI.create(trimmed);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                return Optional.empty();
            }
            if (uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isPlaylistUri(URI uri) {
        String path = uri.getPath();
        return path != null && PLAYLIST_EXTENSION.matcher(path).find();
    }

    public static boolean isYouTubeUri(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        return YOUTUBE_HOSTS.matcher(host.toLowerCase(Locale.ROOT)).find();
    }

    public static Optional<String> youTubeVideoId(URI uri) {
        if (!isYouTubeUri(uri)) {
            return Optional.empty();
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();

        if (host.endsWith("youtu.be")) {
            String candidate = stripLeadingSlash(path);
            return isVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        Matcher matcher = Pattern.compile("^/(?:embed|shorts|v|live|e)/([^/]+)").matcher(path);
        if (matcher.find() && isVideoId(matcher.group(1))) {
            return Optional.of(matcher.group(1));
        }

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.regionMatches(0, "v=", 0, 2) && isVideoId(pair.substring(eq + 1))) {
                return Optional.of(pair.substring(eq + 1));
            }
        }

        return Optional.empty();
    }

    public static Optional<String> youTubePlaylistId(URI uri) {
        if (!isYouTubeUri(uri)) {
            return Optional.empty();
        }

        String query = uri.getRawQuery();
        if (query == null) {
            return Optional.empty();
        }

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.regionMatches(0, "list=", 0, 5)) {
                String value = pair.substring(eq + 1);
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

    public static String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c)) {
                builder.append(' ');
            } else {
                builder.append(c);
            }
        }

        String cleaned = builder.toString().replaceAll("\\s+", " ").trim();
        if (cleaned.length() > MAX_TITLE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_TITLE_LENGTH).trim();
        }
        return cleaned;
    }

    private static String stripLeadingSlash(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    private static boolean isVideoId(String candidate) {
        return candidate != null && candidate.length() == 11 && candidate.matches("[A-Za-z0-9_-]{11}");
    }
}
