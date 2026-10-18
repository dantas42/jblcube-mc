package dev.jblcube.data;

import dev.jblcube.source.UrlValidator;

import java.util.ArrayList;
import java.util.List;

public record MusicBoxData(List<MusicBoxTrack> tracks, int currentIndex, int positionMillis, float volume,
                           boolean repeat, boolean playing) {
    public static final float MIN_VOLUME = 0.0F;
    public static final float MAX_VOLUME = 1.0F;
    public static final float DEFAULT_VOLUME = 0.8F;
    public static final int MAX_POSITION_MILLIS = 24 * 60 * 60 * 1000;

    public static final MusicBoxData EMPTY = new MusicBoxData(List.of(), -1, 0, DEFAULT_VOLUME, false, false);

    public MusicBoxData {
        tracks = List.copyOf(tracks == null
                ? List.of()
                : tracks.subList(0, Math.min(tracks.size(), UrlValidator.MAX_TRACKS)));
        currentIndex = tracks.isEmpty() ? -1 : Math.max(-1, Math.min(currentIndex, tracks.size() - 1));
        positionMillis = Math.max(0, Math.min(positionMillis, MAX_POSITION_MILLIS));
        volume = clampVolume(volume);
        if (currentIndex < 0) {
            playing = false;
        }
    }

    public static MusicBoxData withTracks(List<MusicBoxTrack> tracks) {
        return new MusicBoxData(tracks, -1, 0, DEFAULT_VOLUME, false, false);
    }

    public boolean hasCurrentTrack() {
        return currentIndex >= 0 && currentIndex < tracks.size();
    }

    public MusicBoxTrack currentTrack() {
        return hasCurrentTrack() ? tracks.get(currentIndex) : null;
    }

    public int trackCount() {
        return tracks.size();
    }

    public MusicBoxData addTracks(List<MusicBoxTrack> additions) {
        List<MusicBoxTrack> merged = new ArrayList<>(tracks);
        if (additions != null) {
            for (MusicBoxTrack track : additions) {
                if (merged.size() >= UrlValidator.MAX_TRACKS) {
                    break;
                }
                if (track != null && !merged.contains(track)) {
                    merged.add(track);
                }
            }
        }
        return new MusicBoxData(merged, currentIndex, positionMillis, volume, repeat, playing);
    }

    public MusicBoxData removeTrack(int index) {
        if (index < 0 || index >= tracks.size()) {
            return this;
        }

        List<MusicBoxTrack> remaining = new ArrayList<>(tracks);
        remaining.remove(index);

        int newIndex = currentIndex;
        if (index < currentIndex) {
            newIndex = currentIndex - 1;
        } else if (index == currentIndex) {
            newIndex = Math.min(currentIndex, remaining.size() - 1);
        }

        boolean stillPlaying = playing && newIndex >= 0;
        return new MusicBoxData(remaining, newIndex, 0, volume, repeat, stillPlaying);
    }

    public MusicBoxData clearTracks() {
        // Same shortcut as removeTrack: an empty box has nothing to clear.
        if (tracks.isEmpty()) {
            return this;
        }
        return new MusicBoxData(List.of(), -1, 0, volume, repeat, false);
    }

    public MusicBoxData selectTrack(int index, boolean play) {
        if (index < 0 || index >= tracks.size()) {
            return new MusicBoxData(tracks, currentIndex, positionMillis, volume, repeat, false);
        }
        // Re-selecting the track that is already loaded must not rewind it, otherwise pausing and
        // resuming (or clicking the current entry) restarts the song from the beginning.
        int position = index == currentIndex ? positionMillis : 0;
        return new MusicBoxData(tracks, index, position, volume, repeat, play);
    }

    public MusicBoxData advance(boolean backwards) {
        if (tracks.isEmpty()) {
            return EMPTY;
        }

        if (currentIndex < 0) {
            return new MusicBoxData(tracks, 0, 0, volume, repeat, true);
        }

        if (backwards) {
            int previous = currentIndex - 1;
            if (previous < 0) {
                previous = repeat ? tracks.size() - 1 : 0;
            }
            return new MusicBoxData(tracks, previous, 0, volume, repeat, true);
        }

        int next = currentIndex + 1;
        if (next >= tracks.size()) {
            if (!repeat) {
                return new MusicBoxData(tracks, currentIndex, 0, volume, repeat, false);
            }
            next = 0;
        }
        return new MusicBoxData(tracks, next, 0, volume, repeat, true);
    }

    public MusicBoxData withPlaying(boolean play) {
        boolean canPlay = play && hasCurrentTrack();
        return new MusicBoxData(tracks, currentIndex, positionMillis, volume, repeat, canPlay);
    }

    public MusicBoxData withVolume(float newVolume) {
        return new MusicBoxData(tracks, currentIndex, positionMillis, newVolume, repeat, playing);
    }

    public MusicBoxData withRepeat(boolean newRepeat) {
        return new MusicBoxData(tracks, currentIndex, positionMillis, volume, newRepeat, playing);
    }

    public MusicBoxData withPosition(int millis) {
        return new MusicBoxData(tracks, currentIndex, millis, volume, repeat, playing);
    }

    public MusicBoxData withCurrentTrack(MusicBoxTrack track) {
        if (!hasCurrentTrack() || track == null) {
            return this;
        }
        List<MusicBoxTrack> updated = new ArrayList<>(tracks);
        updated.set(currentIndex, track);
        return new MusicBoxData(updated, currentIndex, positionMillis, volume, repeat, playing);
    }

    public static float clampVolume(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_VOLUME;
        }
        return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, value));
    }
}