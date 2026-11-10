package dev.jblcube.client;

import dev.jblcube.block.JblCubeBlockEntity;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The editor can point either at a cube held in the hand or at a cube standing on the ground.
 * Both expose the same operations so {@link MusicBoxScreen} does not care which one it has, but
 * they talk to the server differently: the held cube sends incremental item payloads, while a
 * placed cube sends its whole playlist in one message because the copy that counts lives in the
 * block entity.
 */
public interface MusicBoxTarget {
    MusicBoxData data();

    /** Writes the value on this client only, so the UI reacts immediately. */
    void applyLocal(MusicBoxData data);

    void pushSelect(MusicBoxData updated);

    void pushAddTracks(MusicBoxData updated, List<MusicBoxTrack> added, boolean playFirst);

    void pushRemoveTrack(MusicBoxData updated, int index);

    void pushSettings(MusicBoxData updated);

    /** Removes every track at once, keeping the volume and repeat preferences. */
    void pushClear(MusicBoxData updated);

    int positionMillis();

    int durationMillis();

    /** True while the track should be audible but the decoder is still being opened. */
    boolean loading();

    static MusicBoxData boxData(ItemStack stack) {
        MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
        return data == null ? MusicBoxData.EMPTY : data;
    }
}

final class HeldBoxTarget implements MusicBoxTarget {
    private final ClientMusicBoxManager manager;
    private final ItemStack stack;

    HeldBoxTarget(ClientMusicBoxManager manager, ItemStack stack) {
        this.manager = manager;
        this.stack = stack;
    }

    @Override
    public MusicBoxData data() {
        return MusicBoxTarget.boxData(stack);
    }

    @Override
    public void applyLocal(MusicBoxData data) {
        stack.set(ModDataComponents.JBL_CUBE.get(), data);
    }

    @Override
    public void pushSelect(MusicBoxData updated) {
        manager.sendSelect(updated.currentIndex(), updated.playing());
    }

    @Override
    public void pushAddTracks(MusicBoxData updated, List<MusicBoxTrack> added, boolean playFirst) {
        manager.sendAddTracks(added, playFirst);
    }

    @Override
    public void pushRemoveTrack(MusicBoxData updated, int index) {
        manager.sendRemoveTrack(index);
    }

    @Override
    public void pushSettings(MusicBoxData updated) {
        manager.sendSettings(updated.volume(), updated.repeat());
    }

    @Override
    public void pushClear(MusicBoxData updated) {
        manager.sendClear();
    }

    @Override
    public int positionMillis() {
        return manager.localPositionMillis();
    }

    @Override
    public int durationMillis() {
        return manager.localDurationMillis();
    }

    @Override
    public boolean loading() {
        return manager.heldCubeLoading(data());
    }
}

final class PlacedCubeTarget implements MusicBoxTarget {
    private final ClientMusicBoxManager manager;
    private final BlockPos pos;
    private JblCubeBlockEntity cube;

    PlacedCubeTarget(ClientMusicBoxManager manager, BlockPos pos) {
        this.manager = manager;
        this.pos = pos;
    }

    private JblCubeBlockEntity resolve() {
        if (cube == null) {
            cube = manager.editorCube();
        }
        return cube;
    }

    @Override
    public MusicBoxData data() {
        JblCubeBlockEntity blockEntity = resolve();
        return blockEntity == null ? MusicBoxData.EMPTY : blockEntity.data();
    }

    @Override
    public void applyLocal(MusicBoxData data) {
        JblCubeBlockEntity blockEntity = resolve();
        if (blockEntity != null) {
            blockEntity.setData(data);
        }
    }

    @Override
    public void pushSelect(MusicBoxData updated) {
        manager.sendCubeState(pos, updated);
        // The editor user is the de-facto owner of this cube: seed the server with the selected
        // track so nearby listeners (and this client, on the server stream) can hear it without
        // having to fetch it themselves.
        manager.prefetchTrack(updated.currentTrack());
    }

    @Override
    public void pushAddTracks(MusicBoxData updated, List<MusicBoxTrack> added, boolean playFirst) {
        manager.sendCubeState(pos, updated);
    }

    @Override
    public void pushRemoveTrack(MusicBoxData updated, int index) {
        manager.sendCubeState(pos, updated);
    }

    @Override
    public void pushSettings(MusicBoxData updated) {
        manager.sendCubeState(pos, updated);
        // Instant for the editor: the periodic scan would apply the volume only up to a second later.
        manager.applyPlacedVolume(pos, updated.volume());
    }

    @Override
    public void pushClear(MusicBoxData updated) {
        manager.sendCubeState(pos, updated);
    }

    @Override
    public int positionMillis() {
        return manager.placedPositionMillis(pos);
    }

    @Override
    public int durationMillis() {
        // The decoder knows the real length of the file, while a track pasted as a raw link often
        // carries no duration at all. Reading it here is what makes the remaining time show up.
        int decoded = manager.placedDurationMillis(pos);
        if (decoded > 0) {
            return decoded;
        }
        MusicBoxTrack track = data().currentTrack();
        return track == null ? -1 : track.durationMillis();
    }

    @Override
    public boolean loading() {
        return manager.placedCubeLoading(pos, data());
    }
}