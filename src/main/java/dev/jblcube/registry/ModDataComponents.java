package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.data.MusicBoxCodecs;
import dev.jblcube.data.MusicBoxData;
import net.minecraft.core.component.DataComponentType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(JblCube.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<MusicBoxData>> JBL_CUBE =
            DATA_COMPONENTS.registerComponentType("jbl_cube", builder -> builder
                    .persistent(MusicBoxCodecs.DATA_CODEC)
                    .networkSynchronized(MusicBoxCodecs.DATA_STREAM_CODEC));

    private ModDataComponents() {
    }

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }
}