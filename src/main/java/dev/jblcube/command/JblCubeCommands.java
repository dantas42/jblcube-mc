package dev.jblcube.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.jblcube.server.ServerToolCheck;
import dev.jblcube.source.ExternalTools;
import dev.jblcube.source.ToolDownloader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /jblcube tools} — checks, from inside the game, whether this server can play YouTube links.
 * Shares the probe with the boot-time log so both answers always agree.
 */
public final class JblCubeCommands {
    private JblCubeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("jblcube")
                .then(Commands.literal("tools")
                        .requires(source -> source.hasPermission(2))
                        .executes(JblCubeCommands::tools)));
    }

    private static int tools(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("command.jblcube.tools.checking"), false);

        Thread thread = new Thread(() -> {
            ServerToolCheck.Status status;
            try {
                // Fresh probe, so an operator can re-run the command right after fixing the host
                // instead of being stuck with the answer the boot check cached.
                ExternalTools.resetAvailabilityCache();
                status = ServerToolCheck.probe();
            } catch (RuntimeException e) {
                reply(source, Component.translatable("command.jblcube.tools.error").withStyle(ChatFormatting.RED));
                return;
            }

            reply(source, Component.translatable("command.jblcube.tools.ytdlp", state(status.ytDlp())));
            reply(source, Component.translatable("command.jblcube.tools.folder", ToolDownloader.directory().toString())
                    .withStyle(ChatFormatting.GRAY));
            reply(source, summary(status));
        }, "JBL Cube Tool Check");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static Component state(boolean available) {
        return Component.translatable(available ? "command.jblcube.tools.ok" : "command.jblcube.tools.missing")
                .withStyle(available ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component summary(ServerToolCheck.Status status) {
        if (status.youtubeReady()) {
            return Component.translatable("command.jblcube.tools.summary.ok").withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("command.jblcube.tools.summary.no_ytdlp").withStyle(ChatFormatting.GOLD);
    }

    private static void reply(CommandSourceStack source, Component message) {
        // The probe runs off-thread, so hop back before touching server or connection state.
        source.getServer().execute(() -> source.sendSuccess(() -> message, false));
    }
}
