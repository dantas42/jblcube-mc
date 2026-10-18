package dev.jblcube;

import dev.jblcube.net.ModPayloads;
import dev.jblcube.net.MusicBoxRelay;
import dev.jblcube.registry.ModBlockEntities;
import dev.jblcube.registry.ModBlocks;
import dev.jblcube.registry.ModDataComponents;
import dev.jblcube.registry.ModEffects;
import dev.jblcube.registry.ModItems;
import dev.jblcube.registry.ModRecipeSerializers;
import dev.jblcube.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(JblCube.MOD_ID)
public final class JblCube {
    public static final String MOD_ID = "jblcube";
    public static final Logger LOGGER = LoggerFactory.getLogger("JblCube");

    public JblCube(IEventBus modBus) {
        ModSounds.register(modBus);
        ModDataComponents.register(modBus);
        ModEffects.register(modBus);
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModItems.register(modBus);
        ModRecipeSerializers.register(modBus);
        modBus.addListener(this::registerPayloads);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new ServerEvents());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        ModPayloads.register(event);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static final class ServerEvents {
        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            dev.jblcube.command.JblCubeCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public void onServerStarted(ServerStartedEvent event) {
            dev.jblcube.server.ServerToolCheck.checkAsync();
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MusicBoxRelay.forget(player.getUUID());
                dev.jblcube.server.ServerAudio.forget(player);
            }
        }

        @SubscribeEvent
        public void onPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MusicBoxRelay.relayState(player, dev.jblcube.net.ServerPayloadHandler
                        .currentState(player).orElse(dev.jblcube.data.MusicBoxData.EMPTY));
            }
        }

        @SubscribeEvent
        public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
            dev.jblcube.server.ServerAudio.tick();
        }
    }
}