package dev.jblcube.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the buffer contract of {@link PcmAudioStream}.
 *
 * <p>{@code Channel.pumpBuffers} hands {@code SoundBuffer} whatever this stream returns, and
 * {@code SoundBuffer.getAlBuffer()} then calls
 * {@code alBufferData(buffer, format, data, (int) format.getSampleRate())}. The last argument is a
 * byte count taken from the sample rate, so a {@code read()} result shorter than the sample rate
 * makes OpenAL read past the end of the buffer. That is what crashed the game with
 * {@code EXCEPTION_ACCESS_VIOLATION} in {@code OpenAL.dll}, and the old implementation returned
 * 4 KiB silence buffers on every starvation.
 */
class PcmAudioStreamTest {
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int FRAME_SIZE = CHANNELS * 2;

    /** What vanilla {@code Channel.calculateBufferSize} asks for: frameSize * sampleRate. */
    private static final int ENGINE_MAX_BYTES = FRAME_SIZE * SAMPLE_RATE;

    private static AudioFormat stereo16k() {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS, FRAME_SIZE,
                (long) SAMPLE_RATE * FRAME_SIZE, false);
    }

    private static PcmAudioStream newStream() {
        return new PcmAudioStream(stereo16k(), 64);
    }

    @Test
    void uploadSizeMatchesTheSampleRateVanillaPassesToOpenAl() {
        PcmAudioStream stream = newStream();
        assertEquals(SAMPLE_RATE, stream.uploadBytes());
    }

    @Test
    void starvedReadStillCoversTheWholeOpenAlUpload() throws IOException {
        PcmAudioStream stream = newStream();

        // Nothing queued: this is the starvation path that used to hand out a 4 KiB buffer and
        // crash OpenAL roughly half a minute into playback.
        for (int i = 0; i < 3; i++) {
            ByteBuffer buffer = stream.read(ENGINE_MAX_BYTES);
            assertNotNull(buffer, "starved reads must return silence, not null");
            assertTrue(buffer.remaining() >= stream.uploadBytes(),
                    "read returned " + buffer.remaining() + " bytes, OpenAL would read " + stream.uploadBytes());
            assertTrue(buffer.remaining() <= ENGINE_MAX_BYTES,
                    "read returned more than the engine asked for");
            assertTrue(buffer.isDirect(), "OpenAL wants a direct buffer");
        }
    }

    @Test
    void starvedReadIsRealSilence() throws IOException {
        PcmAudioStream stream = newStream();

        ByteBuffer buffer = stream.read(ENGINE_MAX_BYTES);
        assertNotNull(buffer);
        while (buffer.hasRemaining()) {
            assertEquals(0, buffer.get() & 0xFF, "padding must be silence, not stale bytes");
        }
    }

    @Test
    void neverReturnsMoreThanTheEngineRequested() throws IOException {
        PcmAudioStream stream = newStream();

        ByteBuffer buffer = stream.read(1024);
        assertNotNull(buffer);
        assertTrue(buffer.remaining() <= 1024);
    }

    @Test
    void deliversQueuedPcmInOrderAndAdvancesPosition() throws IOException {
        PcmAudioStream stream = newStream();

        byte[] first = new byte[FRAME_SIZE * 100];
        byte[] second = new byte[FRAME_SIZE * 100];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i + 1);
            second[i] = (byte) 0xFF;
        }
        stream.offer(ByteBuffer.wrap(first));
        stream.offer(ByteBuffer.wrap(second));

        ByteBuffer buffer = stream.read(ENGINE_MAX_BYTES);
        assertNotNull(buffer);
        assertEquals(SAMPLE_RATE, buffer.remaining(), "a full upload worth of bytes comes back");

        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], buffer.get(), "PCM must survive the copy at byte " + i);
        }
        // 100 + 100 frames were consumed, the rest of the buffer is zero padding.
        assertEquals(200L * 1000L / SAMPLE_RATE, stream.positionMillis());
    }

    @Test
    void pausedStreamKeepsPositionAndQueuedAudio() throws IOException {
        PcmAudioStream stream = newStream();

        byte[] audio = new byte[FRAME_SIZE * 100];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i + 1);
        }
        stream.offer(ByteBuffer.wrap(audio));

        assertTrue(stream.read(ENGINE_MAX_BYTES).remaining() >= stream.uploadBytes());
        int positionBeforePause = stream.positionMillis();
        assertTrue(positionBeforePause > 0);

        stream.setPaused(true);
        assertTrue(stream.isPaused());

        // While paused the engine gets silence, but neither the queue nor the position moves, so
        // resuming continues on the same millisecond instead of starting over.
        for (int i = 0; i < 3; i++) {
            ByteBuffer buffer = stream.read(ENGINE_MAX_BYTES);
            assertNotNull(buffer, "a paused stream must keep answering the sound engine");
            assertTrue(buffer.remaining() >= stream.uploadBytes(),
                    "paused reads must still cover the OpenAL upload");
            while (buffer.hasRemaining()) {
                assertEquals(0, buffer.get() & 0xFF, "a paused stream must hand out silence");
            }
            assertEquals(positionBeforePause, stream.positionMillis());
        }
        assertEquals(0, stream.bufferedMillis(), "the first read already drained the small test queue");

        stream.setPaused(false);
        assertEquals(positionBeforePause, stream.positionMillis());
        assertTrue(stream.positionMillis() >= positionBeforePause);
    }

    @Test
    void pausingMidTrackHoldsBackTheAudioThatWasAlreadyDecoded() throws IOException {
        PcmAudioStream stream = newStream();

        // More than one engine upload, so pausing leaves real audio in the queue.
        int frames = ENGINE_MAX_BYTES / FRAME_SIZE * 2;
        byte[] audio = new byte[FRAME_SIZE * frames];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (byte) (i + 1);
        }
        stream.offer(ByteBuffer.wrap(audio));

        assertEquals(SAMPLE_RATE, stream.read(ENGINE_MAX_BYTES).remaining());
        int positionBeforePause = stream.positionMillis();

        stream.setPaused(true);
        assertTrue(stream.bufferedMillis() > 0, "decoded audio is held, not discarded");
        assertEquals(positionBeforePause, stream.positionMillis());

        stream.setPaused(false);
        ByteBuffer resumed = stream.read(ENGINE_MAX_BYTES);
        assertNotNull(resumed);
        assertEquals(SAMPLE_RATE, resumed.remaining(), "the held audio is still there to play");
        assertEquals((byte) (SAMPLE_RATE + 1), resumed.get(),
                "resume continues with the byte that was queued while paused");
    }

    @Test
    void closedWhilePausedStillEnds() throws IOException {
        PcmAudioStream stream = newStream();
        stream.setPaused(true);
        stream.close();

        assertNull(stream.read(ENGINE_MAX_BYTES));
        assertTrue(stream.isFinished());
    }

    @Test
    void reportsEndOfStreamOnceTheDecoderDrained() throws IOException {
        PcmAudioStream stream = newStream();
        stream.signalFinished();

        for (int i = 0; i < 3; i++) {
            ByteBuffer buffer = stream.read(ENGINE_MAX_BYTES);
            if (buffer == null) {
                return;
            }
            assertTrue(buffer.remaining() >= stream.uploadBytes(),
                    "trailing silence must still cover the OpenAL upload");
        }
        assertNull(stream.read(ENGINE_MAX_BYTES), "a drained stream must end");
    }

    @Test
    void closedStreamEndsInsteadOfThrowing() throws IOException {
        PcmAudioStream stream = newStream();
        stream.close();

        assertNull(stream.read(ENGINE_MAX_BYTES));
        assertTrue(stream.isFinished());
    }
}