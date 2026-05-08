package com.mineastr;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class MineAstrPayloads {
    public static final int MAX_REASON_LENGTH = 512;
    public static final int MAX_ERROR_LENGTH = 512;
    public static final int MAX_MIME_LENGTH = 64;
    public static final int MAX_CHUNK_BYTES = 24 * 1024;

    private MineAstrPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineAstr.MODID, path));
    }

    public record ClientHello(String modVersion, boolean screenshotSupported) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClientHello> TYPE = MineAstrPayloads.type("client_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientHello> CODEC =
                StreamCodec.ofMember(ClientHello::write, ClientHello::read);

        private static ClientHello read(RegistryFriendlyByteBuf buffer) {
            return new ClientHello(buffer.readUtf(64), buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(modVersion, 64);
            buffer.writeBoolean(screenshotSupported);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotRequest(
            String requestId,
            String reason,
            int maxWidth,
            int maxHeight,
            int maxBytes,
            String format) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotRequest> TYPE = MineAstrPayloads.type("screenshot_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotRequest> CODEC =
                StreamCodec.ofMember(ScreenshotRequest::write, ScreenshotRequest::read);

        private static ScreenshotRequest read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotRequest(
                    buffer.readUtf(64),
                    buffer.readUtf(MAX_REASON_LENGTH),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(16));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(reason, MAX_REASON_LENGTH);
            buffer.writeVarInt(maxWidth);
            buffer.writeVarInt(maxHeight);
            buffer.writeVarInt(maxBytes);
            buffer.writeUtf(format, 16);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotChunk(
            String requestId,
            int index,
            int totalChunks,
            int width,
            int height,
            int totalBytes,
            long capturedAtMs,
            String mimeType,
            byte[] bytes) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotChunk> TYPE = MineAstrPayloads.type("screenshot_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotChunk> CODEC =
                StreamCodec.ofMember(ScreenshotChunk::write, ScreenshotChunk::read);

        private static ScreenshotChunk read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotChunk(
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readUtf(MAX_MIME_LENGTH),
                    buffer.readByteArray(MAX_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeVarInt(index);
            buffer.writeVarInt(totalChunks);
            buffer.writeVarInt(width);
            buffer.writeVarInt(height);
            buffer.writeVarInt(totalBytes);
            buffer.writeLong(capturedAtMs);
            buffer.writeUtf(mimeType, MAX_MIME_LENGTH);
            buffer.writeByteArray(bytes);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotError(String requestId, String code, String message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotError> TYPE = MineAstrPayloads.type("screenshot_error");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotError> CODEC =
                StreamCodec.ofMember(ScreenshotError::write, ScreenshotError::read);

        private static ScreenshotError read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotError(buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(MAX_ERROR_LENGTH));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(code, 64);
            buffer.writeUtf(message, MAX_ERROR_LENGTH);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
