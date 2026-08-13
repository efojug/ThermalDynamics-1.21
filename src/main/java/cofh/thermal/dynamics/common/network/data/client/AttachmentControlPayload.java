package cofh.thermal.dynamics.common.network.data.client;

import cofh.lib.util.helpers.NetworkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

public record AttachmentControlPayload(BlockPos pos, Direction side, FriendlyByteBuf buf) implements CustomPacketPayload {

    public static final Type<AttachmentControlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "attachment_control_packet"));

    public static final StreamCodec<FriendlyByteBuf, AttachmentControlPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf), AttachmentControlPayload::new);

    public AttachmentControlPayload(final FriendlyByteBuf buf) {

        this(buf.readBlockPos(), buf.readEnum(Direction.class), NetworkHelper.copyRemaining(buf));
    }

    public void write(FriendlyByteBuf buf) {

        buf.writeBlockPos(pos);
        buf.writeEnum(side);
        buf.writeBytes(this.buf, this.buf.readerIndex(), this.buf.readableBytes());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {

        return TYPE;
    }

}
