package dev.jblcube.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * The "embriaguez" that a beer leaves behind. It carries no attribute changes on purpose: the
 * whole point is the wobbling screen, which is drawn client side over the vanilla nausea texture
 * while this effect is active.
 */
public class DrunkennessMobEffect extends MobEffect {
    public DrunkennessMobEffect() {
        super(MobEffectCategory.HARMFUL, 0xE0A93C);
        setBlendDuration(20);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // No gameplay effect, but it still has to report that it ticked so the timer runs down on
        // the server and the client keeps the instance for the overlay.
        return true;
    }
}