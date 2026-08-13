package cofh.thermal.dynamics.common.network.data.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

public record AttachmentRedstoneControlPayload(BlockPos pos, Direction side, int threshold, byte mode) implements CustomPacketPayload {

    public static final Type<AttachmentRedstoneControlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "attachment_redstone_packet"));

    public static final StreamCodec<FriendlyByteBuf, AttachmentRedstoneControlPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf), AttachmentRedstoneControlPayload::new);

    public AttachmentRedstoneControlPayload(final FriendlyByteBuf buf) {

        this(buf.readBlockPos(), buf.readEnum(Direction.class), buf.readInt(), buf.readByte());
    }

    public void write(FriendlyByteBuf buf) {

        buf.writeBlockPos(pos);
        buf.writeEnum(side);
        buf.writeInt(threshold);
        buf.writeByte(mode);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {

        return TYPE;
    }

}
