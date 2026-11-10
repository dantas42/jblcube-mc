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
        /**
         * The server only needs yt-dlp to expand a YouTube link when the DJ's own machine cannot.
         * Audio itself is delivered from the DJ's upload, so server-side ffmpeg is never required.
         */
        public boolean youtubeReady() {
            return ytDlp;
        }
    }

    /** Probes yt-dlp only. Blocking: call it off the server thread. */
    public static Status probe() {
        // ffmpeg is no longer needed server-side: the DJ's client transcodes to mp3 before the
        // upload. Probing it here would also auto-download a binary that is never used.
        return new Status(ExternalTools.isYtDlpAvailable(), false);
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
        if (status.ytDlp()) {
            LOGGER.info("yt-dlp ready: this server can resolve YouTube links for players who don't "
                    + "have it. Audio playback is served from the DJ's upload, so no ffmpeg is needed here.");
            return;
        }

        String folder = ToolDownloader.directory().toString();
        LOGGER.warn("""
                yt-dlp is NOT available. Players who don't have yt-dlp on their own machine won't \
                be able to add YouTube links. Playback itself still works for everyone: the DJ's \
                client uploads the mp3 and the server relays it. Direct .mp3 links always work.
                Let this server reach github.com, or drop 'yt-dlp' into {} \
                (the JBLCUBE_YTDLP variable overrides the path).""", folder);
    }
}
