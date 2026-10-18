package dev.jblcube.net;

import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells nearby players what a music box is currently playing so their clients can
 * decode the same track and play it at the holder's position.
 */
public final class MusicBoxRelay {
    public static final double RELAY_RANGE = 64.0D;
    private static final long HEARTBEAT_MILLIS = 2000L;
    private static final Map<UUID, LastBroadcast> LAST_BROADCAST = new ConcurrentHashMap<>();

    private MusicBoxRelay() {
    }

    public static void relayState(ServerPlayer holder, MusicBoxData data) {
        MusicBoxTrack track = data.currentTrack();
        long now = System.currentTimeMillis();
        if (track == null) {
            broadcastStop(holder, now);
            return;
        }

        LastBroadcast last = LAST_BROADCAST.get(holder.getUUID());
        boolean changed = last == null
                || !last.url().equals(track.url())
                || last.playing() != data.playing()
                || Float.compare(last.volume(), data.volume()) != 0;
        boolean heartbeat = last == null || now - last.sentMillis() >= HEARTBEAT_MILLIS;
        if (!changed && !heartbeat) {
            return;
        }

        LAST_BROADCAST.put(holder.getUUID(), new LastBroadcast(track.url(), data.playing(), data.volume(), now));
        broadcast(holder, new ModPayloads.RelayPayload(
                holder.getUUID(), data.playing(), track.url(), track.title(), data.positionMillis(),
                data.volume(), holder.position().x, holder.position().y, holder.position().z));
    }

    private static void broadcastStop(ServerPlayer holder, long now) {
        if (LAST_BROADCAST.remove(holder.getUUID()) == null) {
            return;
        }
        Vec3 position = holder.position();
        broadcast(holder, new ModPayloads.RelayPayload(
                holder.getUUID(), false, "", "", 0, 0.0F, position.x, position.y, position.z));
    }

    private static void broadcast(ServerPlayer holder, ModPayloads.RelayPayload payload) {
        Vec3 position = holder.position();
        for (ServerPlayer listener : holder.server.getPlayerList().getPlayers()) {
            if (listener == holder || listener.isSpectator()) {
                continue;
            }
            if (listener.distanceToSqr(position) <= RELAY_RANGE * RELAY_RANGE) {
                listener.connection.send(payload);
            }
        }
    }

    public static void forget(UUID holder) {
        LAST_BROADCAST.remove(holder);
    }

    private record LastBroadcast(String url, boolean playing, float volume, long sentMillis) {
    }
}