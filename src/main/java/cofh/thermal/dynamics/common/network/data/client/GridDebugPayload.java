package cofh.thermal.dynamics.common.network.data.client;

import cofh.lib.util.helpers.NetworkHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

public record GridDebugPayload(FriendlyByteBuf buf) implements CustomPacketPayload {

    public static final Type<GridDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "grid_debug_packet"));

    public static final StreamCodec<FriendlyByteBuf, GridDebugPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf), GridDebugPayload::decode);

    private static GridDebugPayload decode(FriendlyByteBuf buf) {

        return new GridDebugPayload(NetworkHelper.copyRemaining(buf));
    }

    public void write(FriendlyByteBuf buf) {

        buf.writeBytes(this.buf, this.buf.readerIndex(), this.buf.readableBytes());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {

        return TYPE;
    }

}
