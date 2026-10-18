package dev.jblcube.block;

import com.mojang.serialization.MapCodec;
import dev.jblcube.client.ClientMusicBoxManager;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The speaker itself. There is one block per wool colour and they all behave the same: the colour
 * only decides the tint of the cabinet, while the two boolean properties drive the status panel.
 */
public class JblCubeBlock extends BaseEntityBlock {
    /**
     * Only drives the model variant (dark while off, warm while playing). The block entity stays
     * the single source of truth for the playlist, this just lets the server push a block update
     * so every client redraws the cube with the lit texture.
     */
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");

    /**
     * True while the client is still opening the audio. The server cannot know that on its own, so
     * the client reports it back through {@link dev.jblcube.net.ModPayloads#CubeLoadStatePayload}
     * and the server flips the property, which lights the panel yellow.
     */
    public static final BooleanProperty LOADING = BooleanProperty.create("loading");

    /**
     * A flat cabinet with a carry handle, so the clickable outline matches the silhouette: the body
     * fills the lower 9 pixels and the handle arches over the two above it. Without the handle in
     * the shape you could click the empty gap beside it and open a cube that is not really there.
     */
    private static final VoxelShape SHAPE = Shapes.join(
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D),
            Shapes.join(
                    Block.box(2.0D, 9.0D, 6.0D, 4.0D, 16.0D, 10.0D),
                    Shapes.join(Block.box(12.0D, 9.0D, 6.0D, 14.0D, 16.0D, 10.0D),
                            Block.box(2.0D, 15.0D, 6.0D, 14.0D, 16.0D, 10.0D),
                            BooleanOp.OR),
                    BooleanOp.OR),
            BooleanOp.OR);

    private final DyeColor color;
    private final MapCodec<JblCubeBlock> codec;

    public JblCubeBlock(Properties properties, DyeColor color) {
        super(properties);
        this.color = color;
        // One codec per instance: the colour is baked into the block, so it cannot come from the
        // serialised properties.
        this.codec = simpleCodec(props -> new JblCubeBlock(props, color));
        registerDefaultState(stateDefinition.any().setValue(PLAYING, false).setValue(LOADING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PLAYING, LOADING);
    }

    /** Red stopped, yellow loading, green playing: the three panel variants. */
    public enum Status {
        STOPPED, LOADING, PLAYING;

        public static Status of(boolean playing, boolean loading) {
            if (loading) {
                return LOADING;
            }
            return playing ? PLAYING : STOPPED;
        }
    }

    public DyeColor color() {
        return color;
    }

    @Override
    public MapCodec<? extends BaseEntityBlock> codec() {
        return codec;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                  net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                           BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPE;
    }

    /**
     * The cube is a decorative barrell sitting on the block below, not a full occluder: without this,
     * the grass (or whatever surface) under it loses its top and side faces, which reads as the block
     * below "turning invisible". An empty occlusion shape keeps the neighbour's faces intact.
     */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                           BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new JblCubeBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide && level.getBlockEntity(pos) instanceof JblCubeBlockEntity cube) {
            ClientMusicBoxManager.get().openEditor(cube, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        // Carry the playlist over to the placed cube so it keeps playing where it was left.
        // Server side only: the client copy arrives through the block entity update packet.
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof JblCubeBlockEntity cube) {
            MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
            // Always store it stopped: placing a box must never start music on its own. The
            // selection and the position come along, so pressing play continues where it stopped.
            cube.setData((data == null ? MusicBoxData.EMPTY : data).withPlaying(false));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // The playlist lives in the block entity, so it cannot be expressed in a loot table.
            // Dropping the item here is the only way to keep the music when the cube is broken.
            // No setBlock call: onRemove runs from setBlockState, so it would recurse.
            // asItem() hands back the BlockItem of this colour, so breaking a red cube gives a red
            // cube back.
            Item item = state.getBlock().asItem();
            if (!level.isClientSide && item != Items.AIR && level.getBlockEntity(pos) instanceof JblCubeBlockEntity cube) {
                ItemStack drop = new ItemStack(item);
                drop.set(ModDataComponents.JBL_CUBE.get(), cube.data());
                popResource(level, pos, drop);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(PLAYING) ? 15 : 0;
    }
}