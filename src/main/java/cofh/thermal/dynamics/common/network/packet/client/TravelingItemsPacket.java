package cofh.thermal.dynamics.common.network.packet.client;

import cofh.core.util.ProxyUtils;
import cofh.thermal.dynamics.client.ClientTravelingItemIndex;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import cofh.thermal.dynamics.common.network.data.client.TravelingItemsPayload;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class TravelingItemsPacket {

    private static final TravelingItemsPacket INSTANCE = new TravelingItemsPacket();

    public static TravelingItemsPacket get() {

        return INSTANCE;
    }

    public void handle(TravelingItemsPayload payload, IPayloadContext context) {

        context.enqueueWork(() -> {
            Level world = ProxyUtils.getClientWorld();
            if (world == null) {
                return;
            }
            for (TravelingItemsPayload.Removal removal : payload.removed()) {
                ClientTravelingItemIndex.applyRemoval(world, removal.id(), removal.revision());
            }
            if (!(world.getBlockEntity(payload.pos()) instanceof ItemDuctBlockEntity duct)) {
                return;
            }
            for (TravelingItemsPayload.Update update : payload.updated()) {
                try {
                    TravelingItem item = TravelingItem.loadValidated(update.itemTag(), world.registryAccess()).item();
                    if (item.syncRevision() == update.revision()) {
                        ClientTravelingItemIndex.applyUpdate(world, duct, item, update.revision());
                    }
                } catch (RuntimeException ignored) {
                }
            }
            for (TravelingItemsPayload.Move move : payload.moved()) {
                ClientTravelingItemIndex.applyMove(world, duct, move.id(), move.revision(), move.routeIndex());
            }
        });
    }

}
