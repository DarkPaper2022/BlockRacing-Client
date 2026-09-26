package top.lqsnow.blockracing.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BoardPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<BoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockracing", "board_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoardPayload> CODEC = new StreamCodec<>() {
        public BoardPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readableBytes();
            if (size > BoardState.MAX_PACKET) throw new IllegalArgumentException("Oversize task board payload");
            byte[] data = new byte[size];
            buf.readBytes(data);
            return new BoardPayload(data);
        }
        public void encode(RegistryFriendlyByteBuf buf, BoardPayload payload) { buf.writeBytes(payload.data()); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Request(boolean open) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockracing", "board_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf buf) { return new Request(buf.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf buf, Request request) { buf.writeBoolean(request.open()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record FavoriteAction(String target) implements CustomPacketPayload {
        public static final Type<FavoriteAction> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockracing", "board_favorite"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FavoriteAction> CODEC = new StreamCodec<>() {
            public FavoriteAction decode(RegistryFriendlyByteBuf buf) {
                int len = buf.readableBytes();
                if (len > 160) throw new IllegalArgumentException("Target ID too long");
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                return new FavoriteAction(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            public void encode(RegistryFriendlyByteBuf buf, FavoriteAction action) {
                buf.writeBytes(action.target().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
