package dev.jblcube.net;

import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.net.ModPayloads.AudioChunkPayload;
import dev.jblcube.net.ModPayloads.ExpandResultPayload;
import dev.jblcube.net.ModPayloads.UploadAudioPayload;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips the newer payloads so a wrong stream codec cannot silently break the stream. */
class AudioPayloadsTest {

    @Test
    void expandResultRoundTripsTracks() {
        MusicBoxTrack one = new MusicBoxTrack("https://example.com/a.mp3", "A", 1000);
        MusicBoxTrack two = new MusicBoxTrack("https://example.com/b.mp3", "B", 2000);
        ExpandResultPayload payload = new ExpandResultPayload(7, List.of(one, two));

        ByteBuf buffer = Unpooled.buffer();
        ExpandResultPayload.STREAM_CODEC.encode(buffer, payload);
        ExpandResultPayload decoded = ExpandResultPayload.STREAM_CODEC.decode(buffer);

        assertEquals(7, decoded.id());
        assertEquals(List.of(one, two), decoded.tracks());
    }

    @Test
    void audioChunkRoundTripsBytesAndTheLastFlag() {
        byte[] data = new byte[8192];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7);
        }
        AudioChunkPayload payload = new AudioChunkPayload(42, data, true);

        ByteBuf buffer = Unpooled.buffer();
        AudioChunkPayload.STREAM_CODEC.encode(buffer, payload);
        AudioChunkPayload decoded = AudioChunkPayload.STREAM_CODEC.decode(buffer);

        assertEquals(42, decoded.id());
        assertTrue(decoded.last());
        assertArrayEquals(data, decoded.data());
    }

    @Test
    void audioChunkWithAnEmptySliceRoundTrips() {
        AudioChunkPayload payload = new AudioChunkPayload(1, new byte[0], true);
        ByteBuf buffer = Unpooled.buffer();
        AudioChunkPayload.STREAM_CODEC.encode(buffer, payload);
        AudioChunkPayload decoded = AudioChunkPayload.STREAM_CODEC.decode(buffer);
        assertEquals(0, decoded.data().length);
        assertTrue(decoded.last());
    }

    @Test
    void uploadAudioRoundTripsBytesUrlAndTheLastFlag() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 3);
        }
        UploadAudioPayload payload = new UploadAudioPayload(9, "https://example.com/song.mp3", data, false);

        ByteBuf buffer = Unpooled.buffer();
        UploadAudioPayload.STREAM_CODEC.encode(buffer, payload);
        UploadAudioPayload decoded = UploadAudioPayload.STREAM_CODEC.decode(buffer);

        assertEquals(9, decoded.id());
        assertEquals("https://example.com/song.mp3", decoded.url());
        assertArrayEquals(data, decoded.data());
        assertFalse(decoded.last());
    }
}