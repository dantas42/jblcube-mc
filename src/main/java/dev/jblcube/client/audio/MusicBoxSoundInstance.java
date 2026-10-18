package dev.jblcube.client.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Streaming sound instance backed by our own {@link PcmAudioStream}. The sound engine
 * refreshes volume and position from this instance on every tick, so the position
 * supplier keeps remote boxes glued to their holder.
 */
public final class MusicBoxSoundInstance extends AbstractTickableSoundInstance {
    private final PcmAudioStream stream;
    private final Runnable onFinished;
    private final Supplier<Float> volumeSupplier;
    private final Supplier<Vec3> positionSupplier;
    private final BooleanSupplier keepPlaying;
    private boolean finished;

    private MusicBoxSoundInstance(SoundEvent event, PcmAudioStream stream, float volume, boolean relative,
                                  Supplier<Vec3> positionSupplier, Supplier<Float> volumeSupplier,
                                  BooleanSupplier keepPlaying, Runnable onFinished) {
        super(event, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.stream = stream;
        this.onFinished = onFinished;
        this.volumeSupplier = volumeSupplier;
        this.positionSupplier = positionSupplier;
        this.keepPlaying = keepPlaying;
        this.volume = volume;
        this.pitch = 1.0F;
        this.relative = relative;
        // OpenAL distance rolloff is unreliable for a stream whose position we drive ourselves
        // (the resolved sound event carries no attenuation distance for a mod registered this way),
        // so every instance stays un-attenuated and the caller applies the falloff in Java via the
        // volume supplier. Without that the music never got quieter no matter how far away you were.
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.looping = false;
    }

    public static MusicBoxSoundInstance local(SoundEvent event, PcmAudioStream stream, float volume, Runnable onFinished) {
        return new MusicBoxSoundInstance(event, stream, volume, true, null, null, () -> true, onFinished);
    }

    public static MusicBoxSoundInstance local(SoundEvent event, PcmAudioStream stream, Supplier<Float> volumeSupplier,
                                             Runnable onFinished) {
        return new MusicBoxSoundInstance(event, stream, 1.0F, true, null, volumeSupplier, () -> true, onFinished);
    }

    /**
     * Stops this instance from outside the sound system.
     */
    public void shutdown() {
        if (!isStopped()) {
            stop();
        }
    }

    public static MusicBoxSoundInstance remote(SoundEvent event, PcmAudioStream stream, Supplier<Float> volumeSupplier,
                                               Supplier<Vec3> positionSupplier, BooleanSupplier keepPlaying,
                                               Runnable onFinished) {
        return new MusicBoxSoundInstance(event, stream, 1.0F, false, positionSupplier, volumeSupplier, keepPlaying,
                onFinished);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !isStopped() && keepPlaying.getAsBoolean();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    @Override
    public void tick() {
        if (volumeSupplier != null) {
            volume = volumeSupplier.get();
        }
        if (positionSupplier != null) {
            Vec3 position = positionSupplier.get();
            if (position != null) {
                this.x = position.x;
                this.y = position.y;
                this.z = position.z;
            }
        }

        if (!finished && stream.isFinished()) {
            finished = true;
            stop();
            onFinished.run();
        }
    }

    public PcmAudioStream pcmStream() {
        return stream;
    }

    public int positionMillis() {
        return stream.positionMillis();
    }
}