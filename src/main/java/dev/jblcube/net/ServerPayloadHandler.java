package dev.jblcube.net;

import dev.jblcube.block.JblCubeBlockEntity;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.registry.ModDataComponents;
import dev.jblcube.registry.ModItems;
import dev.jblcube.server.ServerAudio;
import dev.jblcube.source.UrlValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class ServerPayloadHandler {
    private static final int MAX_POSITION_MILLIS = 6 * 60 * 60 * 1000;
    private static final int MAX_DURATION_MILLIS = 6 * 60 * 60 * 1000;
    /** A player may only edit a placed cube they are standing next to. */
    private static final double MAX_CUBE_REACH = 8.0D;

    private ServerPayloadHandler() {
    }

    public static void handleAddTracks(ModPayloads.BoxAddTracksPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, data -> {
            MusicBoxData updated = data.addTracks(sanitizeTracks(payload.tracks()));
            if (payload.playFirst()) {
                int firstNew = data.trackCount();
                if (firstNew < updated.trackCount()) {
                    updated = updated.selectTrack(firstNew, true);
                }
            }
            return updated;
        }));
    }

    public static void handleRemoveTrack(ModPayloads.BoxRemoveTrackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, data -> data.removeTrack(payload.index())));
    }

    public static void handleClear(ModPayloads.BoxClearPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, MusicBoxData::clearTracks));
    }

    public static void handleSelect(ModPayloads.BoxSelectPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, data -> data.selectTrack(payload.index(), payload.playing())));
    }

    public static void handleSettings(ModPayloads.BoxSettingsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, data -> data
                .withVolume(MusicBoxData.clampVolume(payload.volume()))
                .withRepeat(payload.repeat())));
    }

    public static void handleState(ModPayloads.BoxStatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> withBoxData(player, data -> {
            MusicBoxData result = data.hasCurrentTrack() && data.currentIndex() == payload.index()
                    ? data
                    : data.selectTrack(payload.index(), payload.playing());
            return result
                    .withPosition(clampPosition(payload.positionMillis()))
                    .withVolume(MusicBoxData.clampVolume(payload.volume()))
                    .withRepeat(payload.repeat())
                    .withPlaying(payload.playing() && result.hasCurrentTrack());
        }));
    }

    /**
     * A nearby client is opening the audio of a placed cube. Only the colour hint travels, and only
     * while the cube really is playing, so a stale "loading" can never leave the panel stuck yellow.
     */
    public static void handleCubeLoading(ModPayloads.CubeLoadStatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            BlockPos pos = payload.pos();
            if (pos == null || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_CUBE_REACH * MAX_CUBE_REACH) {
                return;
            }
            if (!(player.level().getBlockEntity(pos) instanceof JblCubeBlockEntity cube)) {
                return;
            }
            if (payload.loading() && !cube.data().playing()) {
                return;
            }
            cube.setLoading(payload.loading());
        });
    }

    public static void handleExpandUrl(ModPayloads.ExpandUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerAudio.expand(payload.id(), payload.input(), player);
    }

    public static void handleRequestAudio(ModPayloads.RequestAudioPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerAudio.requestAudio(payload.id(), payload.url(), player);
    }

    public static void handleCubeState(ModPayloads.CubeStatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            BlockPos pos = payload.pos();
            if (pos == null || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_CUBE_REACH * MAX_CUBE_REACH) {
                return;
            }
            if (!(player.level().getBlockEntity(pos) instanceof JblCubeBlockEntity cube)) {
                return;
            }

            MusicBoxData updated = sanitizeData(payload.data());
            if (!updated.equals(cube.data())) {
                cube.setData(updated);
                player.level().sendBlockUpdated(pos, player.level().getBlockState(pos),
                        player.level().getBlockState(pos), Block.UPDATE_ALL);
            }
        });
    }

    /**
     * Rebuilds a playlist received from a client so a hand crafted payload cannot smuggle in
     * non http(s) urls, oversized titles or absurd durations.
     */
    private static MusicBoxData sanitizeData(MusicBoxData data) {
        if (data == null) {
            return MusicBoxData.EMPTY;
        }

        List<MusicBoxTrack> tracks = sanitizeTracks(data.tracks());
        int index = data.currentIndex();
        boolean playing = data.playing();

        if (index < 0 || index >= tracks.size()) {
            return new MusicBoxData(tracks, -1, 0, clampVolume(data.volume()), data.repeat(), false);
        }
        if (!playing) {
            return new MusicBoxData(tracks, index, clampPosition(data.positionMillis()), clampVolume(data.volume()),
                    data.repeat(), false);
        }
        return new MusicBoxData(tracks, index, clampPosition(data.positionMillis()), clampVolume(data.volume()),
                data.repeat(), true);
    }

    private static float clampVolume(float volume) {
        return MusicBoxData.clampVolume(volume);
    }

    public static Optional<MusicBoxData> currentState(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEmpty() && ModItems.isCube(stack)) {
                MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
                if (data != null) {
                    return Optional.of(data);
                }
            }
        }
        return Optional.empty();
    }

    private static MusicBoxData withBoxData(ServerPlayer player, UnaryOperator<MusicBoxData> operator) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !ModItems.isCube(stack)) {
                continue;
            }

            MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
            if (data == null) {
                continue;
            }

            MusicBoxData updated = operator.apply(data);
            if (updated != null && !updated.equals(data)) {
                stack.set(ModDataComponents.JBL_CUBE.get(), updated);
                MusicBoxRelay.relayState(player, updated);
            }
            return updated;
        }
        return null;
    }

    private static List<MusicBoxTrack> sanitizeTracks(List<MusicBoxTrack> tracks) {
        List<MusicBoxTrack> sanitized = new ArrayList<>();
        if (tracks == null) {
            return sanitized;
        }
        for (MusicBoxTrack track : tracks) {
            if (track == null) {
                continue;
            }
            Optional<URI> uri = UrlValidator.parse(track.url());
            if (uri.isEmpty()) {
                continue;
            }
            sanitized.add(new MusicBoxTrack(uri.get().toASCIIString(),
                    UrlValidator.sanitizeTitle(track.title()),
                    clampDuration(track.durationMillis())));
            if (sanitized.size() >= UrlValidator.MAX_TRACKS) {
                break;
            }
        }
        return sanitized;
    }

    private static int clampPosition(int positionMillis) {
        return Math.clamp(positionMillis, 0, MAX_POSITION_MILLIS);
    }

    private static int clampDuration(int durationMillis) {
        return Math.clamp(durationMillis, 0, MAX_DURATION_MILLIS);
    }
}