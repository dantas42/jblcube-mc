package dev.jblcube.registry;

import dev.jblcube.JblCube;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, JblCube.MOD_ID);

    public static final ResourceLocation JBL_CUBE_LOCATION = JblCube.id("jbl_cube");
    public static final SoundEvent JBL_CUBE = SoundEvent.createVariableRangeEvent(JBL_CUBE_LOCATION);

    private ModSounds() {
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register("jbl_cube", () -> JBL_CUBE);
        SOUND_EVENTS.register(modBus);
    }
}