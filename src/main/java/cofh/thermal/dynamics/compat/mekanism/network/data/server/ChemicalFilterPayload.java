package cofh.thermal.dynamics.compat.mekanism.network.data.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

public record ChemicalFilterPayload(BlockPos pos, Direction side, int slot, CompoundTag chemical) implements CustomPacketPayload {

    public static final Type<ChemicalFilterPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "chemical_filter_packet"));
    public static final StreamCodec<FriendlyByteBuf, ChemicalFilterPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> payload.write(buffer), ChemicalFilterPayload::new);

    public ChemicalFilterPayload(FriendlyByteBuf buffer) {

        this(buffer.readBlockPos(), buffer.readEnum(Direction.class), buffer.readVarInt(), buffer.readNbt());
    }

    public void write(FriendlyByteBuf buffer) {

        buffer.writeBlockPos(pos);
        buffer.writeEnum(side);
        buffer.writeVarInt(slot);
        buffer.writeNbt(chemical);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {

        return TYPE;
    }

}
