package dev.jblcube.client;

import dev.jblcube.registry.ModEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

/**
 * Draws what is currently playing above the hotbar, and the drunk overlay while the beers effect is
 * running.
 *
 * <p>This is a plain HUD line instead of the vanilla action bar on purpose: the action bar fades out
 * after a couple of seconds, and the point here is to keep the song name on screen for as long as
 * the cube stays within earshot. Walking out of range (or the song stopping) removes it.
 */
@EventBusSubscriber(modid = "jblcube", value = Dist.CLIENT)
public final class JblCubeHudLayer {
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int LINE_SPACING = 2;
    /** The same overlay the game uses for nausea, so the wobble looks familiar. */
    private static final ResourceLocation NAUSEA_OVERLAY =
            ResourceLocation.withDefaultNamespace("textures/misc/nausea.png");
    private static final int OVERLAY_SCALE = 4;

    private JblCubeHudLayer() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();

        renderNearbyMusic(minecraft, graphics);
        renderDrunkenness(minecraft, graphics);
    }

    private static void renderNearbyMusic(Minecraft minecraft, GuiGraphics graphics) {
        // The debug overlay occupies the whole middle of the screen, so leave it alone.
        if (minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }

        List<Component> lines = ClientMusicBoxManager.get().audibleMusic();
        if (lines.isEmpty()) {
            return;
        }

        int screenHeight = graphics.guiHeight();
        int lineHeight = minecraft.font.lineHeight + LINE_SPACING;
        int fontHeight = minecraft.font.lineHeight;

        // Sticks to the bottom of the screen, just above the hotbar, like an action bar line that
        // never fades; the song name used to float next to the crosshair in the middle of the view.
        int lastLineTop = screenHeight - 32 - fontHeight;
        int top = lastLineTop - (lines.size() - 1) * lineHeight;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            int width = minecraft.font.width(line);
            int x = (graphics.guiWidth() - width) / 2;
            int y = top + i * lineHeight;

            graphics.fill(x - 2, y - 1, x + width + 2, y + fontHeight + 1, 0x66000000);
            graphics.drawString(minecraft.font, line, x, y, COLOR_TEXT, true);
        }
    }

    /**
     * Thirty seconds of a slightly off kilter screen after a beer, using the vanilla nausea overlay
     * as the source texture. It pulses faster as the effect runs out.
     */
    private static void renderDrunkenness(Minecraft minecraft, GuiGraphics graphics) {
        Player player = minecraft.player;
        if (player == null || !player.hasEffect(ModEffects.DRUNKENNESS)) {
            return;
        }

        MobEffectInstance effect = player.getEffect(ModEffects.DRUNKENNESS);
        int remaining = effect == null ? 0 : effect.getDuration();
        float strength = effect == null ? 1.0F : Math.min(1.0F, remaining / 20.0F);
        // Swells and fades instead of blinking, which is what the nausea overlay does.
        float pulse = (float) (0.5D + 0.5D * Math.sin(minecraft.player.tickCount * 0.2D));
        float alpha = strength * (0.35F + 0.35F * pulse);

        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int tile = 16 * OVERLAY_SCALE;
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        for (int x = 0; x <= screenWidth; x += tile) {
            for (int y = 0; y <= screenHeight; y += tile) {
                graphics.blit(NAUSEA_OVERLAY, x, y, 0.0F, 0.0F, tile, tile, 16, 16);
            }
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}