package dev.jblcube.data;

import dev.jblcube.source.UrlValidator;

import java.util.List;

public record MusicBoxTrack(String url, String title, int durationMillis) {
    public MusicBoxTrack {
        title = title == null ? "" : title;
        durationMillis = Math.max(0, Math.min(durationMillis, 6 * 60 * 60 * 1000));
    }

    public MusicBoxTrack(String url, String title) {
        this(url, title, 0);
    }

    public boolean hasKnownDuration() {
        return durationMillis > 0;
    }
}