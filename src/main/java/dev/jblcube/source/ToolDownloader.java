package dev.jblcube.source;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads the helper tools when neither the environment, an uploaded file nor the PATH provides
 * them, so a rented panel works out of the box. The binaries are raw single files per platform, so
 * there is no archive to unpack: the same GitHub release assets yt-dlp and ffmpeg-static publish.
 */
public final class ToolDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/auto-tools");

    private static final Path TOOLS = FMLPaths.GAMEDIR.get().resolve("jblcube").resolve("tools");
    private static final long MIN_VALID_BYTES = 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(300);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ToolDownloader() {
    }

    /** Folder the mod looks in first: drop {@code yt-dlp} / {@code ffmpeg} here to avoid a download. */
    public static Path directory() {
        return TOOLS;
    }

    /** The tool uploaded by the player or downloaded earlier, if one is lying in the game dir. */
    static Path uploadedTool(String name) {
        Path folder = TOOLS;
        Path plain = folder.resolve(exeName(name));
        if (Files.isRegularFile(plain) && safeSize(plain)) {
            return plain;
        }
        return null;
    }

    /** Downloads the right binary for this platform into the game dir, or returns null. */
    static Path download(String name) {
        String url = downloadUrl(name);
        if (url == null) {
            LOGGER.warn("No known download for {} on platform {} {}", name,
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        try {
            Files.createDirectories(TOOLS);
            Path target = TOOLS.resolve(exeName(name));
            if (Files.isRegularFile(target) && safeSize(target)) {
                makeExecutable(target);
                return target;
            }

            LOGGER.info("Downloading {} for the first time...", name);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("User-Agent", "JblCube/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                LOGGER.warn("Could not download {}: HTTP {}", name, response.statusCode());
                return null;
            }

            Path partial = TOOLS.resolve(exeName(name) + ".part");
            long size;
            try (InputStream body = response.body()) {
                size = Files.copy(body, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (size < MIN_VALID_BYTES) {
                LOGGER.warn("Download of {} looks broken ({} bytes)", name, size);
                Files.deleteIfExists(partial);
                return null;
            }
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(target);
            LOGGER.info("Downloaded {} ({} bytes) to {}", name, size, target);
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while downloading {}", name);
            return null;
        } catch (IOException e) {
            LOGGER.warn("Could not auto-download {}", name, e);
            return null;
        }
    }

    private static String downloadUrl(String name) {
        boolean windows = isWindows();
        boolean linux = isLinux();
        boolean mac = isMac();
        String arch = osArch();

        if ("yt-dlp".equals(name) && linux && "arm64".equals(arch)) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64";
        }
        if ("yt-dlp".equals(name) && linux) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux";
        }
        if ("yt-dlp".equals(name) && windows) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
        }
        if ("yt-dlp".equals(name) && mac) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";
        }

        String flavour = "ffmpeg-" + (windows ? "win32" : linux ? "linux" : mac ? "darwin" : "") + "-" + arch;
        if ("ffmpeg".equals(name) && !flavour.equals("ffmpeg-")) {
            return "https://github.com/eugeneware/ffmpeg-static/releases/latest/download/" + flavour;
        }

        return null;
    }

    private static boolean safeSize(Path path) {
        try {
            return Files.size(path) >= MIN_VALID_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static void makeExecutable(Path path) {
        if (isWindows()) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.debug("Could not mark {} executable: {}", path, e.toString());
        }
    }

    private static String exeName(String name) {
        return isWindows() ? name + ".exe" : name;
    }

    private static boolean isWindows() {
        return os().contains("win");
    }

    private static boolean isLinux() {
        return os().contains("nux") || os().contains("aix");
    }

    private static boolean isMac() {
        return os().contains("mac") || os().contains("darwin");
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static String osArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x64";
    }
}