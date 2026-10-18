package dev.jblcube.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the MP3 SPI bundled with the mod is actually reachable through {@link AudioSystem}.
 *
 * <p>The service files of mp3spi/tritonus are merged into the shaded jar, so a packaging mistake
 * (for example dropping the service entries) shows up here instead of at runtime in the game.
 */
class Mp3SpiTest {
    private static final long FIXTURE_MILLIS = 2000L;

    @Test
    void decodesFixtureWithBundledSpi() throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream source = openFixture()) {
            AudioFormat sourceFormat = source.getFormat();
            float rate = sourceFormat.getSampleRate();
            int channels = sourceFormat.getChannels();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                    channels * 2, rate, false);

            try (AudioInputStream stream = AudioSystem.getAudioInputStream(target, source)) {
                AudioFormat format = stream.getFormat();
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
                assertEquals(16, format.getSampleSizeInBits());
                assertTrue(Math.abs(format.getSampleRate() - 44100.0F) < 1.0F);
                assertEquals(2, format.getChannels());

                byte[] pcm = stream.readAllBytes();
                long frameBytes = format.getFrameSize();
                assertTrue(frameBytes > 0);
                assertEquals(0L, pcm.length % frameBytes);

                long frames = pcm.length / frameBytes;
                long expectedFrames = (long) (FIXTURE_MILLIS * format.getSampleRate() / 1000L);
                // MP3 encoders add a small amount of padding, so allow a generous tolerance.
                assertTrue(Math.abs(frames - expectedFrames) < expectedFrames * 0.05,
                        "decoded " + frames + " frames, expected about " + expectedFrames);
                assertTrue(rms(pcm) > 100.0D, "fixture should not be silent");
            }
        }
    }

    @Test
    void reportsMp3FileFormat() throws IOException, UnsupportedAudioFileException {
        AudioFileFormat format = AudioSystem.getAudioFileFormat(fixtureFile());
        assertNotNull(format);
        assertTrue(format.getType() != AudioFileFormat.Type.WAVE, "mp3 must not be reported as a WAVE file");
        assertTrue(format.getFormat().getChannels() >= 1);
        Object duration = format.properties().get("duration");
        assertNotNull(duration, "the SPI must expose the track duration for the progress bar");
        assertTrue(((Number) duration).longValue() > 1500L);
    }

    private static File fixtureFile() {
        URL resource = Mp3SpiTest.class.getResource("/fixtures/tone.mp3");
        assertNotNull(resource, "fixtures/tone.mp3 is missing from the test resources");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AudioInputStream openFixture() throws IOException, UnsupportedAudioFileException {
        return AudioSystem.getAudioInputStream(new BufferedInputStream(new FileInputStream(fixtureFile())));
    }

    private static double rms(byte[] pcm) {
        if (pcm.length < 2) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            double value = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
            sum += value * value;
        }
        return Math.sqrt(sum / (pcm.length / 2));
    }
}