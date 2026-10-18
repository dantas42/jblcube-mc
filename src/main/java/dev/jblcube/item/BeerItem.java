package dev.jblcube.item;

import dev.jblcube.registry.ModEffects;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A Brazilian beer. Right click drinks it over about two and a half seconds, the foam shows up as
 * bubbles while it goes down, and finishing it leaves you drunk for thirty seconds. The empty beer
 * comes back as a bucket, which is also how the crafting recipe works: the bucket stays in the grid.
 */
public class BeerItem extends Item {
    private static final int USE_TICKS = 48;
    /** Thirty seconds, exactly like the user asked for. */
    private static final int DRUNKENNESS_TICKS = 20 * 30;
    private static final ParticleOptions FOAM = ParticleTypes.BUBBLE;
    private static final ParticleOptions SPLASH = ParticleTypes.SPLASH;

    public BeerItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public SoundEvent getDrinkingSound() {
        return SoundEvents.GENERIC_DRINK;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isUsingItem()) {
            return InteractionResultHolder.pass(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // A little foam while the glass goes down, on the off ticks so it does not look like rain.
        if ((USE_TICKS - remainingUseDuration) % 4 != 0) {
            return;
        }
        Vec3 mouth = entity.getEyePosition();
        serverLevel.sendParticles(FOAM, mouth.x, mouth.y - 0.35D, mouth.z, 2, 0.12D, 0.08D, 0.12D, 0.01D);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level instanceof ServerLevel serverLevel && entity instanceof Player player) {
            player.addEffect(new MobEffectInstance(ModEffects.DRUNKENNESS, DRUNKENNESS_TICKS, 0, false, true, true));
            serverLevel.sendParticles(FOAM, player.getX(), player.getY() + 1.0D, player.getZ(), 16, 0.35D, 0.4D, 0.35D,
                    0.02D);
            serverLevel.sendParticles(SPLASH, player.getX(), player.getY() + 1.2D, player.getZ(), 8, 0.3D, 0.3D, 0.3D,
                    0.01D);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_BURP,
                    SoundSource.PLAYERS, 0.9F, 0.9F + player.getRandom().nextFloat() * 0.2F);
        }
        // The bucket is never really gone: it comes back empty.
        return new ItemStack(Items.BUCKET);
    }

    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return false;
    }
}