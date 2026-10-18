package dev.jblcube.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Decodes an audio file into PCM on a background thread and exposes it as an
 * {@link PcmAudioStream}. Uses the javax.sound.sampled MP3 SPI (mp3spi + JLayer),
 * which is the same decoder stack vanilla Minecraft relies on for its own music.
 */
public final class AudioDecoder implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/decoder");

    private static final int CHUNK_FRAMES = 4096;
    private static final int QUEUE_CHUNKS = 32;
    private static final int MAX_BOM_DURATION_MILLIS = 8 * 60 * 60 * 1000;
    /**
     * How much audio has to be decoded before the first sample is handed to OpenAL. Two seconds
     * sounded safe but meant every play waited for two seconds of decoding; half a second is
     * already several times the buffer the engine keeps queued, so playback starts at once and
     * still does not starve.
     */
    private static final long PRIME_MILLIS = 500L;

    private final File file;
    private final PcmAudioStream stream;
    private volatile Thread thread;
    private volatile AudioFormat resolvedFormat;
    private volatile long totalFrames = -1L;

    private AudioDecoder(File file, PcmAudioStream stream, Thread thread) {
        this.file = file;
        this.stream = stream;
        this.thread = thread;
    }

    public static AudioDecoder open(Path path, int startMillis) throws IOException, UnsupportedAudioFileException {
        File file = path.toFile();
        AudioInputStream source = AudioSystem.getAudioInputStream(file);
        AudioFormat sourceFormat = source.getFormat();

        // OpenAlUtil.audioFormatToOpenAl only knows mono and stereo 8/16 bit, and the sound engine
        // calls it from its own thread, so anything else has to be converted away here.
        int channels = Math.min(2, Math.max(1, sourceFormat.getChannels()));
        float sampleRate = sourceFormat.getSampleRate();
        if (sampleRate < 4000F || sampleRate > 192000F) {
            sampleRate = 44100F;
        }
        int rate = (int) sampleRate;
        int bits = 16;

        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels,
                channels * (bits / 8), (long) rate * channels * (bits / 8), false);
        AudioInputStream decoded = AudioSystem.getAudioInputStream(target, source);

        PcmAudioStream stream = new PcmAudioStream(target, QUEUE_CHUNKS);
        AudioDecoder decoder = new AudioDecoder(file, stream, null);
        decoder.resolvedFormat = target;
        decoder.totalFrames = probeFrames(file, target);

        Thread thread = new Thread(() -> decoder.run(decoded, startMillis), "Music Box Decoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        decoder.thread = thread;
        thread.start();

        return decoder;
    }

    private void run(AudioInputStream decoded, int startMillis) {
        int frameBytes = decoded.getFormat().getFrameSize();
        if (frameBytes <= 0) {
            frameBytes = 4;
        }
        byte[] buffer = new byte[CHUNK_FRAMES * frameBytes];
        byte[] carry = new byte[frameBytes];
        int carryLength = 0;

        try {
            if (startMillis > 0) {
                skip(decoded, frameBytes, startMillis);
            }

            while (!stream.isFinished()) {
                int read = decoded.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }

                // The sound engine only ever reads whole frames, so hold back a partial frame
                // instead of handing out a chunk that does not start on a frame boundary.
                int total = carryLength + read;
                int usable = total - total % frameBytes;
                if (usable > 0) {
                    byte[] block = new byte[usable];
                    if (carryLength > 0) {
                        System.arraycopy(carry, 0, block, 0, carryLength);
                        System.arraycopy(buffer, 0, block, carryLength, usable - carryLength);
                    } else {
                        System.arraycopy(buffer, 0, block, 0, usable);
                    }
                    stream.offer(ByteBuffer.wrap(block));
                }

                carryLength = total - usable;
                if (carryLength > 0) {
                    System.arraycopy(buffer, read - carryLength, carry, 0, carryLength);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!stream.isFinished()) {
                LOGGER.warn("Failed while decoding {}", file.getName(), e);
            }
        } finally {
            try {
                decoded.close();
            } catch (IOException ignored) {
            }
            stream.signalFinished();
        }
    }

    private static void skip(AudioInputStream decoded, int frameBytes, int startMillis) throws IOException {
        long bytesToSkip = (long) startMillis * (long) decoded.getFormat().getSampleRate() / 1000L * frameBytes;
        byte[] scratch = new byte[CHUNK_FRAMES * frameBytes];
        while (bytesToSkip > 0) {
            int wanted = (int) Math.min(scratch.length, bytesToSkip);
            int read = decoded.read(scratch, 0, wanted);
            if (read < 0) {
                return;
            }
            bytesToSkip -= read;
        }
    }

    private static long probeFrames(File file, AudioFormat target) {
        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            Object duration = fileFormat.properties().get("duration");
            if (duration instanceof Number number && number.longValue() > 0) {
                long millis = number.longValue() / 1000L;
                if (millis > 0 && millis < MAX_BOM_DURATION_MILLIS) {
                    return millis * (long) target.getSampleRate() / 1000L;
                }
            }
        } catch (IOException | UnsupportedAudioFileException | RuntimeException e) {
            LOGGER.debug("Could not probe duration of {}", file, e);
        }
        return -1L;
    }

    public PcmAudioStream stream() {
        return stream;
    }

    public AudioFormat format() {
        return resolvedFormat;
    }

    /**
     * @return the total length of the track in milliseconds, or {@code -1} when unknown.
     */
    public int durationMillis() {
        long frames = totalFrames;
        if (frames <= 0L) {
            return -1;
        }
        return (int) Math.min(MAX_BOM_DURATION_MILLIS, frames * 1000L / resolvedFormat.getSampleRate());
    }

    /**
     * Blocks until enough audio is buffered to feed the sound engine.
     */
    public boolean awaitPrimed(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (stream.isFinished()) {
                return true;
            }
            if (stream.bufferedMillis() >= PRIME_MILLIS) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return !stream.isFinished();
    }

    @Override
    public void close() {
        stream.close();
        if (thread != null) {
            thread.interrupt();
        }
    }
}