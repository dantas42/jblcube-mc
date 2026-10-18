package dev.jblcube.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistParserTest {
    @Test
    void parsesExtendedM3u() {
        String content = """
                #EXTM3U
                #EXTINF:123,First Song
                https://example.com/one.mp3
                #EXTINF:-1,Second   Song
                https://example.com/two.mp3
                """;

        List<PlaylistParser.Entry> entries = PlaylistParser.parse(content);

        assertEquals(2, entries.size());
        assertEquals("https://example.com/one.mp3", entries.get(0).url());
        assertEquals("First Song", entries.get(0).title());
        assertEquals("https://example.com/two.mp3", entries.get(1).url());
        assertEquals("Second Song", entries.get(1).title());
    }

    @Test
    void parsesPls() {
        String content = """
                [playlist]
                NumberOfEntries=2
                File1=https://example.com/one.mp3
                Title1=First Song
                File2=https://example.com/two.mp3
                Title2=Second Song
                """;

        List<PlaylistParser.Entry> entries = PlaylistParser.parse(content);

        assertEquals(2, entries.size());
        assertEquals("First Song", entries.get(0).title());
        assertEquals("Second Song", entries.get(1).title());
        assertEquals("https://example.com/two.mp3", entries.get(1).url());
    }

    @Test
    void parsesPlainUrlList() {
        List<PlaylistParser.Entry> entries = PlaylistParser.parse("""
                # just a comment
                https://example.com/one.mp3

                https://example.com/two.mp3
                """);

        assertEquals(2, entries.size());
        assertNull(entries.get(0).title());
    }

    @Test
    void handlesEmptyInput() {
        assertTrue(PlaylistParser.parse(null).isEmpty());
        assertTrue(PlaylistParser.parse("   ").isEmpty());
    }

    @Test
    void truncatesVeryLongTitles() {
        String content = "#EXTM3U\n#EXTINF:1," + "t".repeat(500) + "\nhttps://example.com/one.mp3\n";
        List<PlaylistParser.Entry> entries = PlaylistParser.parse(content);

        assertEquals(1, entries.size());
        assertEquals(UrlValidator.MAX_TITLE_LENGTH, entries.get(0).title().length());
    }
}