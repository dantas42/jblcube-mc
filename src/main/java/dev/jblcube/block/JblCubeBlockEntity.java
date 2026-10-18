package dev.jblcube.block;

import dev.jblcube.data.MusicBoxCodecs;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds the playlist of a placed cube. The data lives here rather than in the block state so it can
 * grow, and it is synced with the vanilla block entity update packet, which is what makes a placed
 * cube play for every client that has the chunk loaded instead of only for the player who placed it.
 */
public class JblCubeBlockEntity extends BlockEntity {
    private static final String KEY_DATA = "MusicBox";

    private MusicBoxData data = MusicBoxData.EMPTY;
    /**
     * Reported by the client that is actually decoding the audio. A placed cube is heard by every
     * client that has its chunk loaded, and the one that is opening the file is the only side that
     * knows whether it is still loading, so it tells the server to paint the panel yellow.
     */
    private boolean loading;

    public JblCubeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.JBL_CUBE.get(), pos, state);
    }

    public MusicBoxData data() {
        return data;
    }

    public void setData(MusicBoxData data) {
        MusicBoxData updated = data == null ? MusicBoxData.EMPTY : data;
        JblCubeBlock.Status before = status();
        this.data = updated;
        if (!updated.playing()) {
            // Nothing is being decoded for a paused or empty cube, whatever the last client said.
            this.loading = false;
        }
        setChanged();

        // Only a status transition needs a block update; the playlist itself rides along on the
        // block entity packet. Swapping the state here is what recolours the cube on screen.
        if (before != status()) {
            updateStatusState();
        }
    }

    /** Server side only: marks the cube as still opening its audio, so the panel turns yellow. */
    public void setLoading(boolean loading) {
        if (this.loading == loading) {
            return;
        }
        JblCubeBlock.Status before = status();
        this.loading = loading;
        if (before != status()) {
            updateStatusState();
        }
    }

    public boolean isLoading() {
        return loading;
    }

    private JblCubeBlock.Status status() {
        return JblCubeBlock.Status.of(data.playing(), loading);
    }

    private void updateStatusState() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(getBlockPos());
        if (!state.hasProperty(JblCubeBlock.PLAYING) || !state.hasProperty(JblCubeBlock.LOADING)) {
            return;
        }
        BlockState updated = state
                .setValue(JblCubeBlock.PLAYING, data.playing())
                .setValue(JblCubeBlock.LOADING, loading);
        if (!updated.equals(state)) {
            level.setBlock(getBlockPos(), updated, Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        MusicBoxCodecs.DATA_CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), data)
                .result()
                .ifPresent(encoded -> tag.put(KEY_DATA, encoded));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(KEY_DATA, Tag.TAG_COMPOUND)) {
            data = MusicBoxCodecs.DATA_CODEC
                    .parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.getCompound(KEY_DATA))
                    .result()
                    .orElse(MusicBoxData.EMPTY);
        } else {
            data = MusicBoxData.EMPTY;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}