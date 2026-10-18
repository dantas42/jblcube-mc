package dev.jblcube.client;

import dev.jblcube.source.ExternalTools;
import dev.jblcube.registry.ModBlocks;
import dev.jblcube.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;

@EventBusSubscriber(modid = "jblcube", value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientMusicBoxManager.onClientTick(minecraft);
    }

    @SubscribeEvent
    public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
        ExternalTools.resetAvailabilityCache();
        ClientMusicBoxManager.get().stopAll();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMusicBoxManager.get().stopAll();
    }

    /**
     * Tints the cabinet with the wool colour it was crafted with. The panel texture is drawn on top
     * of it without a tint index, so red/yellow/green stays readable on any colour.
     */
    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        for (DyeColor color : DyeColor.values()) {
            event.register((state, view, pos, tintIndex) -> tintIndex == 0 ? color.getTextureDiffuseColor() : 0xFFFFFFFF,
                    ModBlocks.cube(color).get());
        }
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        for (DyeColor color : DyeColor.values()) {
            event.register((stack, tintIndex) -> color.getTextureDiffuseColor(), ModItems.cube(color).get());
        }
        for (ModItems.Beer beer : ModItems.beers()) {
            event.register((stack, tintIndex) -> beer.tint(), ModItems.beer(beer.id()).get());
        }
    }
}