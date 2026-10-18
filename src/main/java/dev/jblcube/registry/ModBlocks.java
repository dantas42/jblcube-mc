package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.block.JblCubeBlock;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ModBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(JblCube.MOD_ID);

    /** One cube per wool colour, in the same order the dyes are defined. */
    private static final Map<DyeColor, DeferredBlock<JblCubeBlock>> CUBES = new LinkedHashMap<>();

    static {
        for (DyeColor color : DyeColor.values()) {
            DeferredBlock<JblCubeBlock> block = BLOCKS.registerBlock("jbl_cube_" + color.getSerializedName(),
                    (Function<Properties, JblCubeBlock>) properties -> new JblCubeBlock(properties, color),
                    Properties.of()
                            .mapColor(color)
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.STONE));
            CUBES.put(color, block);
        }
    }

    private ModBlocks() {
    }

    public static @Nullable DeferredBlock<JblCubeBlock> cube(DyeColor color) {
        return CUBES.get(color);
    }

    /** Only safe to call once the blocks are registered, which is the case for every event handler. */
    public static List<JblCubeBlock> cubes() {
        return CUBES.values().stream().map(DeferredBlock::get).toList();
    }

    public static Block[] cubeBlocks() {
        return cubes().stream().map(block -> (Block) block).toArray(Block[]::new);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}