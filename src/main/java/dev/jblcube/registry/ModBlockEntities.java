package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.block.JblCubeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, JblCube.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<JblCubeBlockEntity>> JBL_CUBE =
            BLOCK_ENTITIES.register("jbl_cube",
                    () -> BlockEntityType.Builder.of(JblCubeBlockEntity::new, ModBlocks.cubeBlocks())
                            .build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}