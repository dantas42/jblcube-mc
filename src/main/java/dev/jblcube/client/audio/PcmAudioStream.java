package dev.jblcube.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Bridges the MP3 decoder thread with the Minecraft sound engine.
 *
 * <p><b>Why the buffers handed to {@link #read(int)} are always exactly one upload long.</b> The
 * sound engine does not upload "whatever the stream returned". {@code Channel.pumpBuffers} calls
 * {@code stream.read(streamingBufferSize)} with {@code streamingBufferSize == frameSize *
 * sampleRate}, and then {@code SoundBuffer.getAlBuffer()} does
 * {@code AL10.alBufferData(buffer, format, data, (int) format.getSampleRate())}. That last
 * argument is the number of <em>bytes</em> OpenAL reads from {@code data} and it is derived from
 * the sample rate alone. So {@code read()} must hand back a buffer whose {@code remaining()} is at
 * least {@code sampleRate}; a shorter buffer makes OpenAL read past its end and kills the JVM with
 * an {@code EXCEPTION_ACCESS_VIOLATION} inside {@code OpenAL.dll}.
 *
 * <p>So {@link #read(int)} always returns a direct buffer of exactly {@link #targetBytes(int)}
 * bytes: real PCM when the decoder keeps up, real PCM plus zero padding when it starves. Returning
 * a short buffer is what used to crash the game, so those paths are gone.
 *
 * <p>{@code read()} runs on the "Sound engine" thread, so it must never block for long; a starved
 * queue yields silence instead of stalling the engine.
 */
public final class PcmAudioStream implements AudioStream {
    private static final long STARVATION_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long IDLE_PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(500);
    private static final int MAX_POOLED_BUFFERS = 8;
    /** Silent buffers handed out after the decoder drained, so the tail is not clipped. */
    private static final int TRAILING_SILENCE_BUFFERS = 2;

    private final AudioFormat format;
    private final int bytesPerFrame;
    private final int frameRate;
    /** The exact byte count {@code SoundBuffer} passes to {@code alBufferData}. */
    private final int uploadBytes;
    private final BlockingQueue<ByteBuffer> queue;
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * Pausing keeps the decoder thread and the decoded audio alive and only stops the hand-off.
     * {@code read()} then answers with silence while the queue, and therefore
     * {@link #positionMillis()}, stays exactly where the user left it. That is what makes resume
     * instant: nothing is resolved, downloaded, opened or seeked again.
     */
    private final AtomicBoolean paused = new AtomicBoolean();

    private long framesDelivered;
    private int trailingSilence;

    PcmAudioStream(AudioFormat format, int queueCapacity) {
        this.format = format;
        // frameSize already accounts for the channel count, so multiplying by getChannels() again
        // would halve the reported playback position.
        this.bytesPerFrame = Math.max(1, format.getFrameSize());
        this.frameRate = Math.max(1, (int) format.getSampleRate());
        this.uploadBytes = this.frameRate;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
    }

    void offer(ByteBuffer chunk) {
        if (closed.get() || !chunk.hasRemaining()) {
            return;
        }
        try {
            while (!closed.get() && chunk.hasRemaining()) {
                if (paused.get()) {
                    // Hold the chunk instead of decoding ahead: a paused box must not drift.
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                    continue;
                }
                if (queue.offer(chunk)) {
                    return;
                }
                // Queue is full: hand over a smaller slice so a congested queue cannot deadlock us.
                ByteBuffer head = chunk.slice();
                int half = Math.max(bytesPerFrame, head.remaining() / 2);
                head.limit(half);
                if (queue.offer(head, 100, TimeUnit.MILLISECONDS)) {
                    chunk.position(chunk.position() + half);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void signalFinished() {
        if (finished.compareAndSet(false, true)) {
            queue.offer(ByteBuffer.allocate(0));
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * The number of bytes the sound engine will upload for one buffer. A {@code read()} result must
     * never be shorter than this or OpenAL overreads it.
     */
    public int uploadBytes() {
        return uploadBytes;
    }

    @Override
    public ByteBuffer read(int maxBytes) throws IOException {
        if (closed.get()) {
            return null;
        }

        int target = targetBytes(maxBytes);
        ByteBuffer out = acquire(target);

        if (paused.get()) {
            // Hand the engine silence without consuming the queue: the sound instance stays alive
            // (so nothing has to be reopened on resume) but no audio and no position advance.
            silence(out, target);
            return out;
        }

        int copied = 0;
        long idleDeadline = System.nanoTime() + STARVATION_NANOS;
        while (copied < target) {
            ByteBuffer chunk = queue.peek();
            if (chunk != null) {
                if (!chunk.hasRemaining()) {
                    queue.poll();
                    break;
                }

                int take = Math.min(target - copied, chunk.remaining());
                ByteBuffer head = chunk.slice();
                head.limit(take);
                out.put(head);
                chunk.position(chunk.position() + take);
                copied += take;

                if (!chunk.hasRemaining()) {
                    queue.poll();
                }
                idleDeadline = System.nanoTime() + STARVATION_NANOS;
                continue;
            }

            if (finished.get() || System.nanoTime() >= idleDeadline) {
                break;
            }
            LockSupport.parkNanos(IDLE_PARK_NANOS);
        }

        framesDelivered += copied / bytesPerFrame;

        boolean drained = finished.get() && queue.isEmpty();
        trailingSilence = drained && copied == 0 ? trailingSilence + 1 : 0;
        if (trailingSilence > TRAILING_SILENCE_BUFFERS) {
            recycle(out);
            return null;
        }

        // Pad with real silence instead of returning a short buffer: the tail must stay inside the
        // allocation OpenAL is told to read.
        out.limit(target);
        while (out.hasRemaining()) {
            out.put((byte) 0);
        }
        out.position(0);
        return out;
    }

    @Override
    public void close() {
        closed.set(true);
        finished.set(true);
        paused.set(false);
        queue.clear();
        pool.clear();
    }

    public void setPaused(boolean value) {
        // The decoder thread polls this flag every IDLE_PARK_NANOS while paused, so a resume takes
        // effect immediately without having to hand it the thread reference.
        paused.set(value);
    }

    public boolean isPaused() {
        return paused.get();
    }

    private static void silence(ByteBuffer target, int bytes) {
        target.clear();
        target.limit(bytes);
        while (target.hasRemaining()) {
            target.put((byte) 0);
        }
        target.position(0);
    }

    public boolean isFinished() {
        return finished.get() || closed.get();
    }

    public int positionMillis() {
        return (int) (framesDelivered * 1000L / frameRate);
    }

    public int bufferedMillis() {
        long frames = 0;
        for (ByteBuffer chunk : queue) {
            frames += chunk.remaining() / bytesPerFrame;
        }
        return (int) (frames * 1000L / frameRate);
    }

    /**
     * Frame aligned, never shorter than the {@code alBufferData} upload size and never longer than
     * what the sound engine asked for.
     */
    private int targetBytes(int maxBytes) {
        int wanted = alignUp(uploadBytes, bytesPerFrame);
        return Math.max(bytesPerFrame, Math.min(maxBytes, wanted));
    }

    private static int alignUp(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    private ByteBuffer acquire(int size) {
        ByteBuffer pooled;
        while ((pooled = pool.poll()) != null) {
            if (pooled.capacity() >= size) {
                pooled.clear();
                pooled.limit(size);
                return pooled;
            }
        }
        ByteBuffer fresh = ByteBuffer.allocateDirect(size);
        fresh.limit(size);
        return fresh;
    }

    private void recycle(ByteBuffer buffer) {
        if (pool.size() < MAX_POOLED_BUFFERS) {
            pool.add(buffer);
        }
    }
}