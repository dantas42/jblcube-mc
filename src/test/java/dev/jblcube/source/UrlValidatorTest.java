package dev.jblcube.source;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlValidatorTest {
    @Test
    void acceptsHttpAndHttps() {
        assertTrue(UrlValidator.parse("https://example.com/song.mp3").isPresent());
        assertTrue(UrlValidator.parse("http://example.com/song.mp3").isPresent());
        assertEquals(Optional.of(URI.create("https://example.com/song.mp3")),
                UrlValidator.parse("  https://example.com/song.mp3  "));
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertTrue(UrlValidator.parse("file:///etc/passwd").isEmpty());
        assertTrue(UrlValidator.parse("ftp://example.com/a.mp3").isEmpty());
        assertTrue(UrlValidator.parse("javascript:alert(1)").isEmpty());
        assertTrue(UrlValidator.parse("data:audio/mp3;base64,AAAA").isEmpty());
    }

    @Test
    void rejectsCredentialsAndOversizedInput() {
        assertTrue(UrlValidator.parse("https://user:pass@example.com/a.mp3").isEmpty());
        assertTrue(UrlValidator.parse("https://example.com/" + "a".repeat(600)).isEmpty());
        assertTrue(UrlValidator.parse(null).isEmpty());
        assertTrue(UrlValidator.parse("   ").isEmpty());
    }

    @Test
    void detectsPlaylists() {
        assertTrue(UrlValidator.isPlaylistUri(URI.create("https://example.com/list.m3u8")));
        assertTrue(UrlValidator.isPlaylistUri(URI.create("https://example.com/list.m3u?token=abc")));
        assertTrue(UrlValidator.isPlaylistUri(URI.create("https://example.com/list.pls")));
        assertFalse(UrlValidator.isPlaylistUri(URI.create("https://example.com/song.mp3")));
    }

    @Test
    void extractsYouTubeVideoIds() {
        assertEquals(Optional.of("dQw4w9WgXcQ"),
                UrlValidator.youTubeVideoId(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ")));
        assertEquals(Optional.of("dQw4w9WgXcQ"),
                UrlValidator.youTubeVideoId(URI.create("https://youtu.be/dQw4w9WgXcQ")));
        assertEquals(Optional.of("dQw4w9WgXcQ"),
                UrlValidator.youTubeVideoId(URI.create("https://www.youtube.com/embed/dQw4w9WgXcQ")));
        assertEquals(Optional.of("dQw4w9WgXcQ"),
                UrlValidator.youTubeVideoId(URI.create("https://www.youtube.com/shorts/dQw4w9WgXcQ")));
        assertTrue(UrlValidator.youTubeVideoId(URI.create("https://www.youtube.com/watch?v=tooshort")).isEmpty());
        assertTrue(UrlValidator.youTubeVideoId(URI.create("https://example.com/watch?v=dQw4w9WgXcQ")).isEmpty());
    }

    @Test
    void extractsYouTubePlaylistIds() {
        assertEquals(Optional.of("PL1234567890"),
                UrlValidator.youTubePlaylistId(URI.create("https://www.youtube.com/playlist?list=PL1234567890")));
        assertEquals(Optional.of("PLabcdefghij"),
                UrlValidator.youTubePlaylistId(URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghij")));
        assertTrue(UrlValidator.youTubePlaylistId(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEmpty());
    }

    @Test
    void sanitizesTitles() {
        assertEquals("Song Name", UrlValidator.sanitizeTitle("  Song\tName \n"));
        assertEquals("", UrlValidator.sanitizeTitle(null));
        assertEquals("clean", UrlValidator.sanitizeTitle("clean"));
        assertEquals(UrlValidator.MAX_TITLE_LENGTH,
                UrlValidator.sanitizeTitle("x".repeat(400)).length());
    }
}