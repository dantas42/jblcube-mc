package dev.jblcube.net;

import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void handleRelay(ModPayloads.RelayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.jblcube.client.ClientMusicBoxManager.get().handleRelay(payload));
    }

    public static void handleExpandResult(ModPayloads.ExpandResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.jblcube.client.NetworkAudio.INSTANCE.receiveExpandResult(payload.id(), payload.tracks()));
    }

    public static void handleAudioChunk(ModPayloads.AudioChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.jblcube.client.NetworkAudio.INSTANCE
                .receiveAudioChunk(payload.id(), payload.data(), payload.last()));
    }

    public static void handleAudioFailed(ModPayloads.AudioFailedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.jblcube.client.NetworkAudio.INSTANCE.receiveAudioFailed(payload.id()));
    }
}