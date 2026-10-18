package dev.jblcube.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class PlaylistParser {
    public record Entry(String url, String title) {
    }

    private PlaylistParser() {
    }

    public static List<Entry> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String trimmed = content.stripLeading();
        if (trimmed.regionMatches(true, 0, "[playlist]", 0, 10)) {
            return parsePls(content);
        }
        if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            return parseM3u(content);
        }

        List<Entry> entries = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String candidate = line.strip();
            if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                entries.add(new Entry(candidate, null));
            }
        }
        return List.copyOf(entries);
    }

    private static List<Entry> parseM3u(String content) {
        List<Entry> entries = new ArrayList<>();
        String pendingTitle = null;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                int comma = line.indexOf(',');
                pendingTitle = comma >= 0 && comma + 1 < line.length()
                        ? UrlValidator.sanitizeTitle(line.substring(comma + 1))
                        : null;
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            entries.add(new Entry(line, pendingTitle));
            pendingTitle = null;
        }

        return List.copyOf(entries);
    }

    private static List<Entry> parsePls(String content) {
        Map<Integer, String> files = new TreeMap<>();
        Map<Integer, String> titles = new TreeMap<>();
        int unnamed = 0;
        int currentIndex = -1;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).strip();
            if (key.startsWith("file")) {
                currentIndex = indexOfSuffix(key, unnamed++);
                files.put(currentIndex, value);
            } else if (key.startsWith("title")) {
                titles.put(indexOfSuffix(key, currentIndex), UrlValidator.sanitizeTitle(value));
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Integer, String> file : files.entrySet()) {
            entries.add(new Entry(file.getValue(), titles.get(file.getKey())));
        }
        return List.copyOf(entries);
    }

    private static int indexOfSuffix(String key, int fallback) {
        String digits = key.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return fallback < 0 ? 0 : fallback;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback < 0 ? 0 : fallback;
        }
    }
}
