package dev.jblcube.client;

import dev.jblcube.JblCube;
import dev.jblcube.block.JblCubeBlockEntity;
import dev.jblcube.client.audio.AudioDecoder;
import dev.jblcube.client.audio.MusicBoxSoundInstance;
import dev.jblcube.source.SourceResolver;
import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.net.ModPayloads;
import dev.jblcube.registry.ModDataComponents;
import dev.jblcube.registry.ModItems;
import dev.jblcube.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client side brain: decides what the local music box should be playing, keeps the
 * OpenAL channels fed, and mirrors remote boxes relayed by the server.
 */
public final class ClientMusicBoxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(JblCube.MOD_ID);
    private static final ClientMusicBoxManager INSTANCE = new ClientMusicBoxManager();

    private static final long RELAY_TIMEOUT_MILLIS = 8000L;
    private static final int POSITION_SYNC_TICKS = 40;
    private static final int START_ATTEMPT_COOLDOWN_MILLIS = 2500;
    /** How often the nearby placed cubes are looked for, in ticks. */
    private static final int CUBE_SCAN_TICKS = 10;
    /** 3 chunks == 48 blocks, comfortably past the audible radius of the sound. */
    private static final int CUBE_SCAN_CHUNK_RADIUS = 3;
    /** Blocks from the listener at which a cube goes silent. Matches the sounds.json entry. */
    public static final float CUBE_AUDIBLE_RADIUS = 32.0F;
    /** How often a playing placed cube reports its playback position, in ticks. */
    private static final int CUBE_POSITION_SYNC_TICKS = 100;
    /**
     * Cubes that are paused (or too far to hear) keep their decoder parked instead of being torn
     * down, so walking back into range resumes the song where it stopped instead of resolving,
     * downloading and seeking all over again. Each one still holds a thread, so only a handful are
     * kept around.
     */
    private static final int MAX_SUSPENDED_CUBES = 4;
    /** Same idea for the box in the player's hand. */
    private static final int MAX_PREFETCHED_URLS = 32;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Music Box Worker");
        thread.setDaemon(true);
        return thread;
    });

    /** Separate from {@link #workers} so a background download cannot delay a start. */
    private final ExecutorService prefetchWorkers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Music Box Prefetch");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<UUID, RemoteBox> remoteBoxes = new HashMap<>();
    private final Map<BlockPos, PlacedCube> placedCubes = new HashMap<>();
    /** Track already announced above the hotbar, per cube, so it is only shown on change. */
    private final Map<BlockPos, String> announcedCubes = new HashMap<>();
    private final Set<String> prefetchedUrls = new LinkedHashSet<>();

    private ItemStack editorStack;
    private InteractionHand editorHand = InteractionHand.MAIN_HAND;
    private JblCubeBlockEntity editorCube;
    private BlockPos editorCubePos;
    /** Held box playback. Kept alive (paused) between pause and play. */
    private LocalPlayback local;
    private boolean advancePending;
    private long nextStartAttemptMillis;
    private int ticksSinceSync;
    private int cubeScanCooldown;
    private int cubePositionSyncTicks;
    /** Bumped on every start and stop so a superseded decode cannot spawn an orphan sound. */
    private int playbackGeneration;
    private String status = "";

    private ClientMusicBoxManager() {
    }

    public static ClientMusicBoxManager get() {
        return INSTANCE;
    }

    public void openEditor(ItemStack stack, InteractionHand hand) {
        this.editorStack = stack;
        this.editorHand = hand;
        this.editorCube = null;
        this.editorCubePos = null;
        Minecraft.getInstance().setScreen(new MusicBoxScreen(this, stack, null));
    }

    /**
     * Opens the editor on a cube that is standing on the ground. The block entity is the
     * authoritative copy, so every edit is sent as a whole via
     * {@link ModPayloads.CubeStatePayload} instead of the incremental item payloads.
     */
    public void openEditor(JblCubeBlockEntity cube, BlockPos pos) {
        this.editorStack = null;
        this.editorCube = cube;
        this.editorCubePos = pos.immutable();
        Minecraft.getInstance().setScreen(new MusicBoxScreen(this, null, pos.immutable()));
    }

    public ItemStack editorStack() {
        return editorStack;
    }

    public JblCubeBlockEntity editorCube() {
        return editorCube;
    }

    public BlockPos editorCubePos() {
        return editorCubePos;
    }

    public InteractionHand editorHand() {
        return editorHand;
    }

    public String status() {
        return status;
    }

    public int localPositionMillis() {
        return local == null ? 0 : local.positionMillis();
    }

    public int localDurationMillis() {
        return local == null ? -1 : local.durationMillis();
    }

    /** Duration of the placed cube's own decode, which knows lengths the stored track may not. */
    int placedDurationMillis(BlockPos pos) {
        PlacedCube cube = placedCubes.get(pos);
        return cube == null ? -1 : cube.durationMillis();
    }

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            stopAll();
            return;
        }

        ItemStack stack = heldBox(player);
        if (stack == null) {
            stopLocal();
        } else {
            // Read the component raw on purpose: a held box with no data component yet is not the
            // same as a box that is explicitly idle, so it must not cut off current playback.
            MusicBoxData raw = stack.get(ModDataComponents.JBL_CUBE.get());
            if (raw != null) {
                syncLocalPlayback(stack, raw);
            }
        }

        tickRemoteBoxes(minecraft, player);
        tickPlacedCubes(minecraft);
        handlePendingAdvance();
    }

    /**
     * A placed cube is heard by every client that has its chunk loaded, so playback is driven
     * purely from the block entity that vanilla already syncs to us. No extra relay needed.
     */
    /**
     * Distance falloff applied in Java. OpenAL is left un-attenuated (see
     * {@link dev.jblcube.client.audio.MusicBoxSoundInstance}), so this is the only thing making the
     * music quieter as you walk away. Squared, like the vanilla linear model.
     */
    private static float distanceVolume(Minecraft minecraft, Vec3 source, float baseVolume) {
        if (baseVolume <= 0.0F || minecraft.player == null || minecraft.level == null) {
            return 0.0F;
        }
        float distance = (float) minecraft.player.getEyePosition().distanceTo(source);
        if (distance >= CUBE_AUDIBLE_RADIUS) {
            return 0.0F;
        }
        float falloff = 1.0F - distance / CUBE_AUDIBLE_RADIUS;
        return baseVolume * falloff * falloff;
    }

    private static boolean withinAudibleRange(Minecraft minecraft, Vec3 source) {
        if (minecraft.player == null) {
            return false;
        }
        return minecraft.player.getEyePosition().distanceToSqr(source) < CUBE_AUDIBLE_RADIUS * CUBE_AUDIBLE_RADIUS;
    }

    private void tickPlacedCubes(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            stopAllPlacedCubes();
            return;
        }

        if (--cubeScanCooldown <= 0) {
            cubeScanCooldown = CUBE_SCAN_TICKS;
            scanPlacedCubes(minecraft, level);
        }

        if (--cubePositionSyncTicks <= 0) {
            cubePositionSyncTicks = CUBE_POSITION_SYNC_TICKS;
            syncPlacedPositions(level);
        }
    }

    private void scanPlacedCubes(Minecraft minecraft, ClientLevel level) {
        BlockPos playerPos = minecraft.player.blockPosition();
        int centerX = playerPos.getX() >> 4;
        int centerZ = playerPos.getZ() >> 4;

        Map<BlockPos, MusicBoxData> visible = new HashMap<>();
        for (int cx = centerX - CUBE_SCAN_CHUNK_RADIUS; cx <= centerX + CUBE_SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = centerZ - CUBE_SCAN_CHUNK_RADIUS; cz <= centerZ + CUBE_SCAN_CHUNK_RADIUS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof JblCubeBlockEntity cube) {
                        visible.put(cube.getBlockPos().immutable(), cube.data());
                    }
                }
            }
        }

        Set<BlockPos> inRangeNow = new HashSet<>();
        Iterator<Map.Entry<BlockPos, PlacedCube>> iterator = placedCubes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlacedCube> entry = iterator.next();
            PlacedCube cube = entry.getValue();
            MusicBoxData data = visible.get(entry.getKey());
            MusicBoxTrack track = data == null ? null : data.currentTrack();

            // Block gone, unloaded, or holding another song: this decoder is worthless now.
            if (data == null || track == null || !track.url().equals(cube.url)) {
                cube.stop();
                iterator.remove();
                sendCubeLoading(cube.pos, false);
                continue;
            }

            if (data.playing() && cube.hasStoppedSound()) {
                // The engine tore the channel down; the scan rebuilds it on the next pass.
                cube.stop();
                iterator.remove();
                sendCubeLoading(cube.pos, false);
                continue;
            }

            if (!withinAudibleRange(minecraft, cube.position)) {
                // Out of earshot. Park the decoder instead of dropping it, so coming back into
                // range continues the song instead of resolving and seeking all over again.
                suspendCube(cube);
                continue;
            }

            if (data.playing()) {
                cube.resume();
                // The editor slider writes the block entity, so the running sound has to follow it.
                cube.setVolume(data.volume());
                inRangeNow.add(cube.pos);
            } else {
                suspendCube(cube);
            }
        }

        for (Map.Entry<BlockPos, MusicBoxData> entry : visible.entrySet()) {
            MusicBoxData data = entry.getValue();
            MusicBoxTrack track = data.currentTrack();
            if (!data.playing() || track == null || placedCubes.containsKey(entry.getKey())) {
                continue;
            }
            if (!withinAudibleRange(minecraft, Vec3.atCenterOf(entry.getKey()))) {
                continue;
            }
            inRangeNow.add(entry.getKey());
            startPlacedPlayback(minecraft, entry.getKey(), data);
        }

        collectAudibleMusic(visible, inRangeNow);
    }

    /**
     * Keeps at most {@link #MAX_SUSPENDED_CUBES} parked decoders; the oldest one is dropped when
     * the limit is hit so a world full of paused cubes cannot pile up threads.
     */
    private void suspendCube(PlacedCube cube) {
        if (!cube.isPaused()) {
            cube.pause();
            // Nothing is decoding while parked, so the panel must not stay yellow.
            sendCubeLoading(cube.pos, false);
        }

        int suspended = 0;
        PlacedCube oldest = null;
        for (PlacedCube other : placedCubes.values()) {
            if (other.isPaused()) {
                suspended++;
                if (oldest == null || other.pausedAt < oldest.pausedAt) {
                    oldest = other;
                }
            }
        }

        if (suspended <= MAX_SUSPENDED_CUBES || oldest == null || oldest == cube) {
            return;
        }
        placedCubes.remove(oldest.pos);
        oldest.stop();
    }

    /**
     * Keeps the title of every cube within earshot, so the HUD layer can show what is playing for
     * as long as it is audible instead of a message that fades away after a few seconds.
     */
    private void collectAudibleMusic(Map<BlockPos, MusicBoxData> visible, Set<BlockPos> inRangeNow) {
        Iterator<BlockPos> known = announcedCubes.keySet().iterator();
        while (known.hasNext()) {
            if (!inRangeNow.contains(known.next())) {
                known.remove();
            }
        }

        for (BlockPos pos : inRangeNow) {
            String title = titleOf(visible.get(pos));
            if (title != null) {
                announcedCubes.put(pos, title);
            }
        }
    }

    /** The songs currently within earshot, for the HUD line above the hotbar. */
    public List<Component> audibleMusic() {
        if (announcedCubes.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(announcedCubes.size());
        for (String title : announcedCubes.values()) {
            lines.add(Component.translatable("status.jblcube.nearby", title));
        }
        return lines;
    }

    private static String titleOf(MusicBoxData data) {
        MusicBoxTrack track = data == null ? null : data.currentTrack();
        if (track == null) {
            return null;
        }
        return track.title().isBlank() ? track.url() : track.title();
    }

    private void startPlacedPlayback(Minecraft minecraft, BlockPos pos, MusicBoxData data) {
        MusicBoxTrack track = data.currentTrack();
        PlacedCube cube = new PlacedCube(pos.immutable(), track, data);
        // Registered before the decode finishes so the next scan does not start a second copy.
        placedCubes.put(cube.pos, cube);
        // Yellow panel while the file is opening; green as soon as there is audio to play.
        sendCubeLoading(cube.pos, true);

        int startMillis = Math.max(0, data.positionMillis() - 1500);
        CompletableFuture
                .supplyAsync(() -> openDecoder(track, startMillis), workers)
                .thenAccept(decoder -> minecraft.execute(() -> {
                    if (decoder == null || placedCubes.get(pos) != cube) {
                        if (decoder != null) {
                            decoder.close();
                        }
                        sendCubeLoading(cube.pos, false);
                        return;
                    }
                    cube.attach(decoder, minecraft);
                    sendCubeLoading(cube.pos, false);
                    prefetchNext(data);
                }));
    }

    /**
     * Reports where a playing placed cube currently is, so someone who walks up later joins the
     * song instead of restarting it. Every nearby client reports, which is harmless because the
     * value is only consulted when playback (re)starts.
     */
    private void syncPlacedPositions(ClientLevel level) {
        for (PlacedCube cube : placedCubes.values()) {
            int position = cube.positionMillis();
            if (position <= 0 || !(level.getBlockEntity(cube.pos) instanceof JblCubeBlockEntity blockEntity)) {
                continue;
            }
            MusicBoxData current = blockEntity.data();
            if (Math.abs(current.positionMillis() - position) < 2000) {
                continue;
            }
            MusicBoxData updated = current.withPosition(position);
            blockEntity.setData(updated);
            sendCubeState(cube.pos, updated);
        }
    }

    private void advancePlaced(PlacedCube cube) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        // Drop it from the map so the next scan picks the new track up, even when repeat makes the
        // next url identical to the current one.
        PlacedCube stopped = placedCubes.remove(cube.pos);
        if (stopped != null) {
            stopped.stop();
        }

        if (!(level.getBlockEntity(cube.pos) instanceof JblCubeBlockEntity blockEntity)) {
            return;
        }

        MusicBoxData data = blockEntity.data();
        if (!data.playing()) {
            return;
        }

        MusicBoxData advanced = data.advance(false);
        blockEntity.setData(advanced);
        sendCubeState(cube.pos, advanced);
        prefetchNext(advanced);
    }

    public void sendCubeState(BlockPos pos, MusicBoxData data) {
        send(new ModPayloads.CubeStatePayload(pos, data));
        // Rescan on the next tick: waiting for the periodic scan added up to half a second of
        // silence between pressing play and hearing the cube.
        cubeScanCooldown = 0;
    }

    /** Applies a new volume to a placed cube that is already playing, without waiting for a scan. */
    void applyPlacedVolume(BlockPos pos, float volume) {
        PlacedCube cube = placedCubes.get(pos);
        if (cube != null) {
            cube.setVolume(volume);
        }
    }

    /** Turns the cube panel yellow while this client is opening the audio of a placed cube. */
    private void sendCubeLoading(BlockPos pos, boolean loading) {
        send(new ModPayloads.CubeLoadStatePayload(pos, loading));
    }

    int placedPositionMillis(BlockPos pos) {
        PlacedCube cube = placedCubes.get(pos);
        return cube == null ? 0 : cube.positionMillis();
    }

    /**
     * A cube that is playing but has no sound yet is still downloading or decoding, which is the
     * window the editor shows the loading hint for.
     */
    boolean placedCubeLoading(BlockPos pos, MusicBoxData data) {
        if (!data.playing() || data.currentTrack() == null) {
            return false;
        }
        PlacedCube cube = placedCubes.get(pos);
        // A parked decoder is not loading: pressing play resumes it instantly.
        return cube == null || !cube.url.equals(data.currentTrack().url()) || cube.isPaused();
    }

    /** The held cube is loading while it should be playing but has no sound instance yet. */
    boolean heldCubeLoading(MusicBoxData data) {
        MusicBoxTrack track = data.currentTrack();
        if (!data.playing() || track == null) {
            return false;
        }
        return local == null || !local.url().equals(track.url()) || local.isPaused();
    }

    private void stopAllPlacedCubes() {
        announcedCubes.clear();
        if (placedCubes.isEmpty()) {
            return;
        }
        for (PlacedCube cube : placedCubes.values()) {
            cube.stop();
        }
        placedCubes.clear();
    }

    private static MusicBoxData boxData(ItemStack stack) {
        MusicBoxData data = stack.get(ModDataComponents.JBL_CUBE.get());
        return data == null ? MusicBoxData.EMPTY : data;
    }

    private void syncLocalPlayback(ItemStack stack, MusicBoxData data) {
        MusicBoxTrack track = data.currentTrack();
        boolean wantsPlaying = data.playing() && track != null;

        if (local != null && (track == null || !local.url().equals(track.url()))) {
            // Another song (or none) is loaded, so the suspended decoder has nothing to resume.
            local.stop();
            local = null;
        }

        if (!wantsPlaying) {
            if (local == null) {
                maybeSyncState(stack, data, false);
                return;
            }

            if (local.isPaused()) {
                maybeSyncState(stack, data, false);
                return;
            }

            // Keep the stream alive, just stop handing audio over. The frozen position is written
            // back so resuming (or reopening the editor) continues from the same millisecond.
            local.pause();
            MusicBoxData paused = data.withPosition(local.positionMillis());
            stack.set(ModDataComponents.JBL_CUBE.get(), paused);
            send(new ModPayloads.BoxStatePayload(Math.max(0, paused.currentIndex()), paused.positionMillis(),
                    paused.volume(), paused.repeat(), paused.playing()));
            ticksSinceSync = 0;
            return;
        }

        if (local != null) {
            if (local.isStopped()) {
                // The engine closed the channel (track ended); the pending advance picks the next one.
                local.stop();
                local = null;
            } else {
                if (local.isPaused()) {
                    local.resume();
                    ticksSinceSync = 0;
                }
                syncLocalPosition(stack, data);
                return;
            }
        }

        startLocalPlayback(track, data.positionMillis(), data.volume());
        prefetchNext(data);
    }

    /** Only write the position back on the sync interval: every tick marked the stack dirty 20x/s. */
    private void syncLocalPosition(ItemStack stack, MusicBoxData data) {
        if (local == null) {
            return;
        }
        // The slider owns the volume, so it is pushed into the running sound first and the value in
        // the stack is kept. Writing the volume the track started with back here is what used to
        // undo every drag of the slider within a second.
        local.setVolume(data.volume());
        if (++ticksSinceSync < POSITION_SYNC_TICKS) {
            return;
        }
        ticksSinceSync = 0;
        MusicBoxData updated = data.withPosition(local.positionMillis());
        stack.set(ModDataComponents.JBL_CUBE.get(), updated);
        send(new ModPayloads.BoxStatePayload(Math.max(0, updated.currentIndex()), updated.positionMillis(),
                updated.volume(), updated.repeat(), updated.playing()));
    }

    public void prefetchTrack(MusicBoxTrack track) {
        if (track == null || track.url().isBlank() || SourceResolver.isCached(track)
                || !prefetchedUrls.add(track.url())) {
            return;
        }
        while (prefetchedUrls.size() > MAX_PREFETCHED_URLS) {
            Iterator<String> oldest = prefetchedUrls.iterator();
            oldest.next();
            oldest.remove();
        }
        prefetchWorkers.execute(() -> {
            try {
                // Also streams from the server when this machine cannot fetch the audio itself.
                NetworkAudio.INSTANCE.fetchFile(track);
            } catch (RuntimeException e) {
                LOGGER.debug("Could not prefetch {}", track.url(), e);
            }
        });
    }

    /**
     * Fetches the audio of the track after the current one in the background, so the transition to
     * the next song does not have to wait for a download. Runs on its own thread: a prefetch must
     * never delay the track the player actually asked for.
     */
    private void prefetchNext(MusicBoxData data) {
        MusicBoxTrack track = data.currentTrack();
        if (track == null || data.trackCount() <= 1) {
            return;
        }

        int next = data.currentIndex() + 1;
        if (next >= data.trackCount()) {
            if (!data.repeat()) {
                return;
            }
            next = 0;
        }

        prefetchTrack(data.tracks().get(next));
    }

    private void startLocalPlayback(MusicBoxTrack track, int startMillis, float boxVolume) {
        long now = System.currentTimeMillis();
        if (now < nextStartAttemptMillis) {
            return;
        }
        nextStartAttemptMillis = now + START_ATTEMPT_COOLDOWN_MILLIS;

        status = "status.jblcube.loading";
        int generation = ++playbackGeneration;

        CompletableFuture
                .supplyAsync(() -> openDecoder(track, startMillis), workers)
                .thenAccept(decoder -> Minecraft.getInstance().execute(() -> {
                    // A newer start (or a stop) happened while the decoder was opening. Starting now
                    // would leave the old sound instance running with nothing holding a reference to
                    // it, so it could never be stopped and the track played twice.
                    if (decoder == null || generation != playbackGeneration || !isCurrentTrack(track)) {
                        if (decoder != null) {
                            decoder.close();
                        }
                        if (generation == playbackGeneration) {
                            stopLocal();
                        }
                        return;
                    }

                    LocalPlayback previous = local;
                    LocalPlayback playback = new LocalPlayback(track, decoder, boxVolume);
                    local = playback;
                    playback.start();
                    if (previous != null) {
                        previous.stop();
                    }
                    status = "";
                }))
                .exceptionally(error -> {
                    // A decode that failed after the user already moved on must not kill the
                    // track that is playing now, nor invalidate its still in-flight start.
                    if (generation != playbackGeneration) {
                        return null;
                    }
                    LOGGER.warn("Could not start track {}", track.url(), error);
                    status = "status.jblcube.failed";
                    stopLocal();
                    return null;
                });
    }

    private AudioDecoder openDecoder(MusicBoxTrack track, int startMillis) {
        try {
            Optional<Path> file = NetworkAudio.INSTANCE.fetchFile(track);
            if (file.isEmpty()) {
                LOGGER.warn("No audio source available for {}", track.url());
                return null;
            }
            AudioDecoder decoder = AudioDecoder.open(file.get(), Math.max(0, startMillis));
            decoder.awaitPrimed(6000);
            return decoder;
        } catch (Exception e) {
            LOGGER.warn("Failed to open audio for {}", track.url(), e);
            return null;
        }
    }

    private boolean isCurrentTrack(MusicBoxTrack track) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        ItemStack stack = heldBox(player);
        MusicBoxData data = stack == null ? MusicBoxData.EMPTY : boxData(stack);
        return data.playing() && data.currentTrack() != null
                && data.currentTrack().url().equals(track.url());
    }

    private void onLocalTrackFinished() {
        advancePending = true;
    }

    private void maybeSyncState(ItemStack stack, MusicBoxData data, boolean force) {
        if (!force && ++ticksSinceSync < POSITION_SYNC_TICKS) {
            return;
        }
        ticksSinceSync = 0;
        send(new ModPayloads.BoxStatePayload(Math.max(0, data.currentIndex()), data.positionMillis(),
                data.volume(), data.repeat(), data.playing()));
    }

    private void stopLocal() {
        playbackGeneration++;
        if (local != null) {
            local.stop();
            local = null;
        }
    }

    public void stopAll() {
        stopLocal();
        stopAllPlacedCubes();
        for (RemoteBox box : remoteBoxes.values()) {
            box.stop();
        }
        remoteBoxes.clear();
        editorStack = null;
        status = "";
        advancePending = false;
        ticksSinceSync = 0;
    }

    private ItemStack heldBox(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            // The data component is optional, so a bare box must not read as "no box held": that
            // made the client drop the track whenever a server sync delivered a stack without it.
            if (!stack.isEmpty() && ModItems.isCube(stack)) {
                return stack;
            }
        }
        return null;
    }

    private void tickRemoteBoxes(Minecraft minecraft, LocalPlayer player) {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, RemoteBox>> iterator = remoteBoxes.entrySet().iterator();
        while (iterator.hasNext()) {
            RemoteBox box = iterator.next().getValue();
            if (now - box.lastUpdateMillis > RELAY_TIMEOUT_MILLIS) {
                box.stop();
                iterator.remove();
            }
        }
    }

    public void handleRelay(ModPayloads.RelayPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || payload.holder().equals(player.getUUID())) {
            return;
        }

        RemoteBox box = remoteBoxes.get(payload.holder());
        if (box != null && box.matchesUrl(payload)) {
            if (!payload.playing()) {
                box.stop();
                remoteBoxes.remove(payload.holder());
                return;
            }
            box.update(payload);
            return;
        }

        if (box != null) {
            box.stop();
        }
        if (!payload.playing() || payload.url().isBlank()) {
            remoteBoxes.remove(payload.holder());
            return;
        }

        RemoteBox remoteBox = new RemoteBox(payload);
        remoteBoxes.put(payload.holder(), remoteBox);
        startRemotePlayback(minecraft, remoteBox, payload);
    }

    private void startRemotePlayback(Minecraft minecraft, RemoteBox box, ModPayloads.RelayPayload payload) {
        MusicBoxTrack track = new MusicBoxTrack(payload.url(), payload.title(), 0);
        CompletableFuture
                .supplyAsync(() -> openDecoder(track, Math.max(0, payload.positionMillis() - 1500)), workers)
                .thenAccept(decoder -> minecraft.execute(() -> {
                    if (decoder == null || remoteBoxes.get(payload.holder()) != box) {
                        if (decoder != null) {
                            decoder.close();
                        }
                        return;
                    }
                    box.attach(decoder, minecraft);
                }));
    }

    public static void onClientTick(Minecraft minecraft) {
        INSTANCE.tick(minecraft);
    }

    public void runAsync(Runnable task) {
        workers.execute(task);
    }

    public void handlePendingAdvance() {
        if (!advancePending) {
            return;
        }
        advancePending = false;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        ItemStack stack = heldBox(player);
        if (stack == null) {
            return;
        }

        MusicBoxData data = boxData(stack);
        if (!data.playing()) {
            return;
        }

        MusicBoxData advanced = data.advance(false);
        stack.set(ModDataComponents.JBL_CUBE.get(), advanced);
        send(new ModPayloads.BoxSelectPayload(advanced.currentIndex(), advanced.playing()));
        send(new ModPayloads.BoxStatePayload(Math.max(0, advanced.currentIndex()), 0, advanced.volume(),
                advanced.repeat(), advanced.playing()));
    }

    public void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public void sendSettings(float volume, boolean repeat) {
        send(new ModPayloads.BoxSettingsPayload(volume, repeat));
    }

    public void sendSelect(int index, boolean playing) {
        send(new ModPayloads.BoxSelectPayload(index, playing));
    }

    public void sendAddTracks(java.util.List<MusicBoxTrack> tracks, boolean playFirst) {
        send(new ModPayloads.BoxAddTracksPayload(tracks, playFirst));
    }

    public void sendRemoveTrack(int index) {
        send(new ModPayloads.BoxRemoveTrackPayload(index));
    }

    public void sendClear() {
        send(new ModPayloads.BoxClearPayload(false));
    }

    /**
     * A cube standing on the ground. Audio is anchored to the block centre and panned by the sound
     * engine; the distance falloff is applied in Java by {@link #distanceVolume}.
     */
    private static final class PlacedCube {
        private final BlockPos pos;
        private final String url;
        /** Not final: the slider applies to a playing cube, so this must follow the block entity. */
        private float volume;
        private final Vec3 position;
        private final AtomicBoolean playing = new AtomicBoolean();
        private AudioDecoder decoder;
        private MusicBoxSoundInstance sound;
        private long pausedAt;

        private PlacedCube(BlockPos pos, MusicBoxTrack track, MusicBoxData data) {
            this.pos = pos;
            this.url = track.url();
            this.volume = Math.max(0.05F, data.volume());
            this.position = Vec3.atCenterOf(pos);
        }

        private void setVolume(float value) {
            this.volume = Math.max(0.05F, Math.min(1.0F, value));
        }

        private void attach(AudioDecoder audioDecoder, Minecraft minecraft) {
            this.decoder = audioDecoder;
            MusicBoxSoundInstance minecraft$1 = MusicBoxSoundInstance.remote(ModSounds.JBL_CUBE, audioDecoder.stream(),
                    () -> distanceVolume(minecraft, position, volume), () -> position,
                    () -> playing.get() && sound != null && !sound.isStopped(),
                    () -> INSTANCE.advancePlaced(this));
            this.sound = minecraft$1;
            playing.set(true);
            minecraft.getSoundManager().play(minecraft$1);
        }

        private boolean hasStoppedSound() {
            return sound != null && sound.isStopped();
        }

        private void pause() {
            if (decoder != null && !decoder.stream().isPaused()) {
                decoder.stream().setPaused(true);
                pausedAt = System.currentTimeMillis();
            }
        }

        private void resume() {
            if (decoder != null && decoder.stream().isPaused()) {
                decoder.stream().setPaused(false);
            }
        }

        private boolean isPaused() {
            return decoder == null || decoder.stream().isPaused();
        }

        private int positionMillis() {
            return sound == null ? 0 : sound.positionMillis();
        }

        private int durationMillis() {
            return decoder == null ? -1 : decoder.durationMillis();
        }

        private void stop() {
            playing.set(false);
            if (sound != null) {
                sound.shutdown();
                Minecraft.getInstance().getSoundManager().stop(sound);
                sound = null;
            }
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }
    }

    /**
     * The box in the player's hand. Pausing only suspends the stream: the decoder, the file handle
     * and the position all survive, so pressing play again continues instantly. A different song (or
     * no song) tears it down.
     */
    private final class LocalPlayback {
        private final MusicBoxTrack track;
        private final AudioDecoder decoder;
        /**
         * Not final: the slider changes the volume of a track that is already playing, so the sound
         * instance asks for the current value on every buffer instead of capturing the one the
         * track started with.
         */
        private float volume;
        private MusicBoxSoundInstance sound;

        private LocalPlayback(MusicBoxTrack track, AudioDecoder decoder, float volume) {
            this.track = track;
            this.decoder = decoder;
            this.volume = volume;
        }

        private void start() {
            sound = MusicBoxSoundInstance.local(ModSounds.JBL_CUBE, decoder.stream(), () -> volume,
                    ClientMusicBoxManager.this::onLocalTrackFinished);
            Minecraft.getInstance().getSoundManager().play(sound);
        }

        private String url() {
            return track.url();
        }

        private void pause() {
            decoder.stream().setPaused(true);
        }

        private void resume() {
            decoder.stream().setPaused(false);
        }

        private boolean isPaused() {
            return decoder.stream().isPaused();
        }

        private boolean isStopped() {
            return sound == null || sound.isStopped();
        }

        private int positionMillis() {
            return sound == null ? 0 : sound.positionMillis();
        }

        private int durationMillis() {
            return decoder.durationMillis();
        }

        private float volume() {
            return volume;
        }

        /** Applies a new volume to a track that is already running. */
        private void setVolume(float value) {
            this.volume = Math.max(0.0F, Math.min(1.0F, value));
        }

        private void stop() {
            if (sound != null) {
                sound.shutdown();
                Minecraft.getInstance().getSoundManager().stop(sound);
            }
            decoder.close();
        }
    }

    private static final class RemoteBox {
        private final UUID holder;
        private final AtomicBoolean playing = new AtomicBoolean();
        private String url = "";
        private String title = "";
        private float volume = 1.0F;
        private Vec3 position = Vec3.ZERO;
        private long lastUpdateMillis;
        private AudioDecoder decoder;
        private MusicBoxSoundInstance sound;

        private RemoteBox(ModPayloads.RelayPayload payload) {
            this.holder = payload.holder();
            update(payload);
        }

        private void update(ModPayloads.RelayPayload payload) {
            this.url = payload.url();
            this.title = payload.title();
            this.volume = Math.max(0.05F, payload.volume());
            this.position = new Vec3(payload.x(), payload.y(), payload.z());
            this.lastUpdateMillis = System.currentTimeMillis();
        }

        private boolean matchesUrl(ModPayloads.RelayPayload payload) {
            return url.equals(payload.url());
        }

        private void attach(AudioDecoder audioDecoder, Minecraft minecraft) {
            this.decoder = audioDecoder;
            MusicBoxSoundInstance minecraft$1 = MusicBoxSoundInstance.remote(ModSounds.JBL_CUBE, audioDecoder.stream(),
                    () -> distanceVolume(minecraft, position, volume), () -> position,
                    () -> playing.get() && sound != null && !sound.isStopped(), this::onFinished);
            this.sound = minecraft$1;
            playing.set(true);
            minecraft.getSoundManager().play(minecraft$1);
        }

        private void onFinished() {
            playing.set(false);
        }

        private void stop() {
            playing.set(false);
            if (sound != null) {
                sound.shutdown();
                Minecraft.getInstance().getSoundManager().stop(sound);
                sound = null;
            }
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }
    }

    public SoundSource relaySource() {
        return SoundSource.RECORDS;
    }
}