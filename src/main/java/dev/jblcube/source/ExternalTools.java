package dev.jblcube.source;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Locates and runs the external helper programs used to fetch audio.
 *
 * <p>Resolution order for each tool: the {@code JBLCUBE_YTDLP} / {@code JBLCUBE_FFMPEG} environment
 * variables, then a {@code jblcube/tools/} file next to the game directory, then the PATH, and when
 * none of those work the mod downloads the right binary for this platform on its own. That last step
 * is what keeps rented panels working with nothing to install: the first song downloads yt-dlp and
 * ffmpeg, and every later song runs them from the cache.
 */
public final class ExternalTools {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/tools");

    public static final String YTDLP_ENV = "JBLCUBE_YTDLP";
    public static final String FFMPEG_ENV = "JBLCUBE_FFMPEG";
    private static final String LEGACY_YTDLP_ENV = "HANDMUSICBOX_YTDLP";
    private static final String LEGACY_FFMPEG_ENV = "HANDMUSICBOX_FFMPEG";

    private static final ConcurrentMap<String, String> RESOLVED_COMMANDS = new ConcurrentHashMap<>();
    private static Boolean ytDlpAvailable;
    private static Boolean ffmpegAvailable;

    private ExternalTools() {
    }

    public static String ytDlpCommand() {
        return resolveTool("yt-dlp", YTDLP_ENV, LEGACY_YTDLP_ENV);
    }

    public static String ffmpegCommand() {
        return resolveTool("ffmpeg", FFMPEG_ENV, LEGACY_FFMPEG_ENV);
    }

    public static synchronized boolean isYtDlpAvailable() {
        if (ytDlpAvailable == null) {
            ytDlpAvailable = probeVersion(ytDlpCommand()).isPresent();
        }
        return ytDlpAvailable;
    }

    public static synchronized boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) {
            ffmpegAvailable = probeVersion(ffmpegCommand()).isPresent();
        }
        return ffmpegAvailable;
    }

    public static synchronized void resetAvailabilityCache() {
        ytDlpAvailable = null;
        ffmpegAvailable = null;
        // Also drop the resolved paths so a tool dropped into the game dir after startup is picked
        // up instead of the stale "not found" answer the first probe cached.
        RESOLVED_COMMANDS.clear();
    }

    /**
     * Returns the command to run for a tool, downloading the platform build into the game dir when
     * neither the environment, an uploaded file nor the PATH provides one.
     */
    private static String resolveTool(String name, String envKey, String legacyKey) {
        String cached = RESOLVED_COMMANDS.get(name);
        if (cached != null) {
            return cached;
        }
        String result;
        String env = envValue(envKey, legacyKey);
        if (env != null) {
            result = env;
        } else {
            Path local = ToolDownloader.uploadedTool(name);
            if (local != null) {
                result = local.toString();
            } else if (probeVersion(name).isPresent()) {
                result = name;
            } else {
                Path downloaded = ToolDownloader.download(name);
                result = downloaded != null ? downloaded.toString() : name;
                if (downloaded != null) {
                    LOGGER.info("{} ready at {}", name, downloaded);
                }
            }
        }
        RESOLVED_COMMANDS.put(name, result);
        return result;
    }

    /**
     * Runs a command, capturing stdout. Returns empty when the process fails or times out.
     */
    public static Optional<String> run(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outPump = pump(process.getInputStream(), stdout);
            Thread errPump = pump(process.getErrorStream(), stderr);

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("Command timed out after {}s: {}", timeoutSeconds, String.join(" ", command));
                return Optional.empty();
            }
            outPump.join(1000);
            errPump.join(1000);

            String output = stdout.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                LOGGER.debug("Command {} failed: {}", String.join(" ", command), stderr.toString(StandardCharsets.UTF_8).strip());
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            LOGGER.debug("Could not start {}", command.get(0), e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static Optional<String> probeVersion(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            String firstLine;
            try (InputStream stream = process.getInputStream()) {
                firstLine = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse("");
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 && !firstLine.isBlank() ? Optional.of(firstLine) : Optional.empty();
        } catch (IOException e) {
            LOGGER.debug("{} is not available", command, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public static boolean isFfmpegInputSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".opus") || lower.endsWith(".webm") || lower.endsWith(".mp4");
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    private static String envValue(String key, String legacyKey) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(legacyKey);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Thread pump(InputStream stream, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (InputStream input = stream) {
                input.transferTo(target);
            } catch (IOException ignored) {
            }
        }, "Music Box Process Pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}