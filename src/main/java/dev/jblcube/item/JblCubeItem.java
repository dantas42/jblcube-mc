package dev.jblcube.item;

import dev.jblcube.client.ClientMusicBoxManager;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The cube as an item. It doubles as a {@link BlockItem}, so placing it on the ground stores the
 * current playlist in the placed block entity and keeps playing after the item leaves the hand.
 */
public class JblCubeItem extends BlockItem {
    public JblCubeItem(Block block, Properties properties) {
        super(block, properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(ModDataComponents.JBL_CUBE.get())) {
            stack.set(ModDataComponents.JBL_CUBE.get(), MusicBoxData.EMPTY);
        }

        if (level.isClientSide) {
            ClientMusicBoxManager.get().openEditor(stack, hand);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.jblcube.jbl_cube.empty").withStyle(ChatFormatting.GRAY));

        MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
        if (data == null) {
            return;
        }

        MusicBoxTrack current = data.currentTrack();
        if (current != null) {
            tooltip.add(Component.translatable(
                            data.playing() ? "item.jblcube.jbl_cube.playing" : "item.jblcube.jbl_cube.paused")
                    .withStyle(data.playing() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
            tooltip.add(Component.literal(current.title()).withStyle(ChatFormatting.WHITE));
            tooltip.add(Component.literal("#" + (data.currentIndex() + 1) + "/" + data.trackCount())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}