package dev.jblcube.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.jblcube.source.UrlValidator;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the Minecraft-dependent codec definitions for the music box data records.
 *
 * <p>Keeping them here lets {@link MusicBoxTrack} and {@link MusicBoxData} stay plain Java records that
 * can be unit tested without the game on the classpath.
 */
public final class MusicBoxCodecs {
    public static final Codec<MusicBoxTrack> TRACK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("url").forGetter(MusicBoxTrack::url),
            Codec.STRING.fieldOf("title").forGetter(MusicBoxTrack::title),
            Codec.INT.optionalFieldOf("duration", 0).forGetter(MusicBoxTrack::durationMillis)
    ).apply(instance, MusicBoxTrack::new));

    public static final StreamCodec<ByteBuf, MusicBoxTrack> TRACK_STREAM_CODEC = ByteBufCodecs.fromCodec(TRACK_CODEC);

    public static final StreamCodec<ByteBuf, List<MusicBoxTrack>> TRACK_LIST_STREAM_CODEC =
            ByteBufCodecs.collection(ArrayList::new, TRACK_STREAM_CODEC, UrlValidator.MAX_TRACKS);

    private static final Codec<Float> VOLUME_CODEC = Codec.FLOAT.xmap(
            MusicBoxData::clampVolume,
            MusicBoxData::clampVolume);

    public static final Codec<MusicBoxData> DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TRACK_CODEC.listOf().fieldOf("tracks").forGetter(MusicBoxData::tracks),
            Codec.INT.optionalFieldOf("index", -1).forGetter(MusicBoxData::currentIndex),
            Codec.INT.optionalFieldOf("position", 0).forGetter(MusicBoxData::positionMillis),
            VOLUME_CODEC.optionalFieldOf("volume", 0.8F).forGetter(MusicBoxData::volume),
            Codec.BOOL.optionalFieldOf("repeat", false).forGetter(MusicBoxData::repeat),
            Codec.BOOL.optionalFieldOf("playing", false).forGetter(MusicBoxData::playing)
    ).apply(instance, MusicBoxData::new));

    public static final StreamCodec<ByteBuf, MusicBoxData> DATA_STREAM_CODEC = ByteBufCodecs.fromCodec(DATA_CODEC);

    private MusicBoxCodecs() {
    }
}