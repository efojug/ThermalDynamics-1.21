package cofh.thermal.dynamics.common.network.data.client;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

/**
 * Traveling-item sync payload for one duct: full removals, full snapshots, and lightweight
 * positional heartbeats for ordinary hops (the client already knows the route; a hop only needs
 * the authoritative route index).
 */
public record TravelingItemsPayload(BlockPos pos, List<Removal> removed, List<Update> updated, List<Move> moved) implements CustomPacketPayload {

    public static final int MAX_ITEMS_PER_PACKET = 4096;

    public record Removal(UUID id, long revision) { }

    public record Update(long revision, CompoundTag itemTag) { }

    public record Move(UUID id, long revision, int routeIndex) { }

    public static final Type<TravelingItemsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "traveling_items_packet"));
    public static final StreamCodec<FriendlyByteBuf, TravelingItemsPayload> STREAM_CODEC = StreamCodec.of((buf, payload) -> payload.write(buf), TravelingItemsPayload::new);

    public TravelingItemsPayload(FriendlyByteBuf buf) {

        this(buf.readBlockPos(), readRemovals(buf), readUpdates(buf), readMoves(buf));
    }

    private static int count(FriendlyByteBuf buf) {

        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ITEMS_PER_PACKET) {
            throw new DecoderException("Invalid traveling item count: " + n);
        }
        return n;
    }

    private static List<Removal> readRemovals(FriendlyByteBuf buf) {

        int n = count(buf);
        List<Removal> result = new ArrayList<>(n);
        for (int i = 0; i < n; ++i) {
            result.add(new Removal(buf.readUUID(), buf.readLong()));
        }
        return List.copyOf(result);
    }

    private static List<Update> readUpdates(FriendlyByteBuf buf) {

        int n = count(buf);
        List<Update> result = new ArrayList<>(n);
        for (int i = 0; i < n; ++i) {
            long revision = buf.readLong();
            CompoundTag tag = buf.readNbt();
            if (tag == null) {
                throw new DecoderException("Missing traveling item NBT");
            }
            result.add(new Update(revision, tag));
        }
        return List.copyOf(result);
    }

    private static List<Move> readMoves(FriendlyByteBuf buf) {

        int n = count(buf);
        List<Move> result = new ArrayList<>(n);
        for (int i = 0; i < n; ++i) {
            result.add(new Move(buf.readUUID(), buf.readLong(), buf.readVarInt()));
        }
        return List.copyOf(result);
    }

    private void write(FriendlyByteBuf buf) {

        if (removed.size() > MAX_ITEMS_PER_PACKET || updated.size() > MAX_ITEMS_PER_PACKET || moved.size() > MAX_ITEMS_PER_PACKET) {
            throw new IllegalArgumentException("TravelingItemsPayload exceeds per-packet item limit");
        }
        buf.writeBlockPos(pos);
        buf.writeVarInt(removed.size());
        for (Removal removal : removed) {
            buf.writeUUID(removal.id());
            buf.writeLong(removal.revision());
        }
        buf.writeVarInt(updated.size());
        for (Update update : updated) {
            buf.writeLong(update.revision());
            buf.writeNbt(update.itemTag());
        }
        buf.writeVarInt(moved.size());
        for (Move move : moved) {
            buf.writeUUID(move.id());
            buf.writeLong(move.revision());
            buf.writeVarInt(move.routeIndex());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {

        return TYPE;
    }

}
