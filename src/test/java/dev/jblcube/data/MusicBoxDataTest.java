package dev.jblcube.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicBoxDataTest {
    private static MusicBoxData withTracks(int count) {
        List<MusicBoxTrack> tracks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tracks.add(new MusicBoxTrack("https://example.com/" + i + ".mp3", "Track " + i, 1000));
        }
        return MusicBoxData.withTracks(tracks);
    }

    @Test
    void emptyHasNoSelection() {
        assertFalse(MusicBoxData.EMPTY.hasCurrentTrack());
        assertNull(MusicBoxData.EMPTY.currentTrack());
        assertEquals(-1, MusicBoxData.EMPTY.currentIndex());
        assertFalse(MusicBoxData.EMPTY.playing());
    }

    @Test
    void clampsOutOfRangeConstructorArguments() {
        MusicBoxData data = new MusicBoxData(List.of(new MusicBoxTrack("https://a/b.mp3", "b")),
                99, -50, 5.0F, false, true);

        assertEquals(0, data.currentIndex());
        assertEquals(0, data.positionMillis());
        assertEquals(MusicBoxData.MAX_VOLUME, data.volume());

        MusicBoxData empty = new MusicBoxData(List.of(), 3, 10, 0.5F, false, true);
        assertEquals(-1, empty.currentIndex());
        assertFalse(empty.playing());
    }

    @Test
    void clampsVolumeIncludingNaN() {
        assertEquals(0.5F, MusicBoxData.clampVolume(0.5F));
        assertEquals(0.0F, MusicBoxData.clampVolume(-1F));
        assertEquals(1.0F, MusicBoxData.clampVolume(2F));
        assertEquals(0.8F, MusicBoxData.clampVolume(Float.NaN));
    }

    @Test
    void addsTracksWithoutDuplicatesAndRespectsLimit() {
        MusicBoxTrack existing = new MusicBoxTrack("https://example.com/0.mp3", "Track 0", 1000);
        MusicBoxData data = withTracks(1).addTracks(List.of(
                existing,
                new MusicBoxTrack("https://example.com/new.mp3", "New", 0)));

        assertEquals(2, data.trackCount());

        List<MusicBoxTrack> overflow = new ArrayList<>();
        for (int i = 0; i < dev.jblcube.source.UrlValidator.MAX_TRACKS + 50; i++) {
            overflow.add(new MusicBoxTrack("https://example.com/x" + i + ".mp3", "x" + i, 0));
        }
        assertEquals(dev.jblcube.source.UrlValidator.MAX_TRACKS, withTracks(0).addTracks(overflow).trackCount());
    }

    @Test
    void removeTrackKeepsSelectionConsistent() {
        MusicBoxData data = withTracks(3).selectTrack(2, true);

        // Removing the current track continues with the track that takes its place.
        MusicBoxData afterCurrent = data.removeTrack(2);
        assertEquals(1, afterCurrent.currentIndex());
        assertTrue(afterCurrent.playing());
        assertFalse(data.removeTrack(2).selectTrack(1, false).playing());
        assertEquals(1, data.removeTrack(0).currentIndex());
        assertSame(data, data.removeTrack(9));
        MusicBoxData lastTrackLeft = data.removeTrack(0).removeTrack(1).removeTrack(0);
        assertEquals(-1, lastTrackLeft.currentIndex());
        assertFalse(lastTrackLeft.playing());
    }

    @Test
    void advanceStopsAtEndWithoutRepeat() {
        MusicBoxData data = withTracks(3).selectTrack(2, true);

        MusicBoxData advanced = data.advance(false);
        assertEquals(2, advanced.currentIndex());
        assertFalse(advanced.playing());
    }

    @Test
    void advanceWrapsWithRepeat() {
        MusicBoxData data = withTracks(3).selectTrack(2, true).withRepeat(true);

        MusicBoxData advanced = data.advance(false);
        assertEquals(0, advanced.currentIndex());
        assertTrue(advanced.playing());
        assertEquals(1, data.advance(true).currentIndex());
        assertEquals(2, withTracks(3).selectTrack(0, true).withRepeat(true).advance(true).currentIndex());
        assertEquals(0, withTracks(3).selectTrack(0, true).advance(true).currentIndex());
    }

    @Test
    void advanceStartsFirstTrackWhenNothingSelected() {
        MusicBoxData data = withTracks(3).advance(false);

        assertEquals(0, data.currentIndex());
        assertTrue(data.playing());
    }

    @Test
    void selectTrackRejectsInvalidIndex() {
        MusicBoxData data = withTracks(2).selectTrack(1, true);

        assertFalse(data.selectTrack(5, true).playing());
        assertEquals(1, data.selectTrack(5, true).currentIndex());
    }

    @Test
    void clearTracksKeepsPreferencesAndStopsPlayback() {
        MusicBoxData data = withTracks(3).selectTrack(1, true).withRepeat(true).withVolume(0.25F);

        MusicBoxData cleared = data.clearTracks();

        assertEquals(0, cleared.trackCount());
        assertEquals(-1, cleared.currentIndex());
        assertNull(cleared.currentTrack());
        assertFalse(cleared.playing());
        assertTrue(cleared.repeat());
        assertEquals(0.25F, cleared.volume());
        assertEquals(0, cleared.positionMillis());

        // Same story for an empty box: no crash, nothing changes.
        assertSame(MusicBoxData.EMPTY, MusicBoxData.EMPTY.clearTracks());
        assertEquals(3, data.trackCount());
    }

    @Test
    void withPlayingRequiresSelection() {
        MusicBoxData data = withTracks(2);

        assertFalse(data.withPlaying(true).playing());
        assertTrue(data.selectTrack(0, false).withPlaying(true).playing());
    }
}