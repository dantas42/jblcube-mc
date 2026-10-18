package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.effect.DrunkennessMobEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    private static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, JblCube.MOD_ID);

    public static final DeferredHolder<MobEffect, DrunkennessMobEffect> DRUNKENNESS =
            EFFECTS.register("drunkenness", DrunkennessMobEffect::new);

    private ModEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}