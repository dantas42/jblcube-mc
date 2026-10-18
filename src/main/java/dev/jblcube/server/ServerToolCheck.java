package dev.jblcube.server;

import dev.jblcube.source.ExternalTools;
import dev.jblcube.source.ToolDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports whether the helper tools used to fetch YouTube audio are usable.
 *
 * <p>The same probe feeds the boot-time log line and the in-game {@code /jblcube tools} command, so
 * an operator finds out at start instead of when the first player tries to play a song. Probing can
 * spawn processes and, on first use, download the binaries, so live checks run on a daemon thread and
 * never touch the server thread.
 */
public final class ServerToolCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger("jblcube/tools");

    private ServerToolCheck() {
    }

    /** Whether the two helpers that make YouTube links playable are usable. */
    public record Status(boolean ytDlp, boolean ffmpeg) {
        /** YouTube needs yt-dlp to resolve the song and ffmpeg to convert it to mp3. */
        public boolean youtubeReady() {
            return ytDlp && ffmpeg;
        }
    }

    /** Probes both tools. Blocking: call it off the server thread. */
    public static Status probe() {
        return new Status(ExternalTools.isYtDlpAvailable(), ExternalTools.isFfmpegAvailable());
    }

    /** Probes in the background at server start and logs the result. Returns immediately. */
    public static void checkAsync() {
        Thread thread = new Thread(() -> {
            try {
                log(probe());
            } catch (RuntimeException e) {
                LOGGER.warn("Could not check the audio helper tools", e);
            }
        }, "JBL Cube Tool Check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Writes the boot-time diagnostics for the given probe result. */
    public static void log(Status status) {
        if (status.youtubeReady()) {
            LOGGER.info("Audio helpers ready: yt-dlp and ffmpeg are usable, YouTube links will play.");
            return;
        }

        String folder = ToolDownloader.directory().toString();
        if (!status.ytDlp() && !status.ffmpeg()) {
            LOGGER.warn("""
                    yt-dlp and ffmpeg are NOT available. YouTube links will not play for players \
                    whose own machine cannot fetch the audio either. Direct .mp3 links still work.
                    Let this server reach github.com, or drop 'yt-dlp' and 'ffmpeg' into {} \
                    (the JBLCUBE_YTDLP / JBLCUBE_FFMPEG variables override the path).""", folder);
            return;
        }

        if (!status.ffmpeg()) {
            LOGGER.warn("""
                    ffmpeg is NOT available. yt-dlp can resolve a YouTube song but cannot convert \
                    it to mp3, so YouTube links will not play. Direct .mp3 links still work.
                    Let this server reach github.com, or drop 'ffmpeg' into {}.""", folder);
            return;
        }

        LOGGER.warn("""
                yt-dlp is NOT available. YouTube links fall back to public Invidious instances \
                (converted with ffmpeg), which is less reliable. Fix: let this server reach \
                github.com, or drop 'yt-dlp' into {}.""", folder);
    }
}
