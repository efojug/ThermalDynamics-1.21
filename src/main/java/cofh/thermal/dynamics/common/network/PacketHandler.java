package cofh.thermal.dynamics.common.network;

import cofh.thermal.dynamics.common.network.data.client.AttachmentControlPayload;
import cofh.thermal.dynamics.common.network.data.client.GridDebugPayload;
import cofh.thermal.dynamics.common.network.data.client.TravelingItemsPayload;
import cofh.thermal.dynamics.common.network.data.server.AttachmentConfigPayload;
import cofh.thermal.dynamics.common.network.data.server.AttachmentRedstoneControlPayload;
import cofh.thermal.dynamics.common.network.packet.client.AttachmentControlPacket;
import cofh.thermal.dynamics.common.network.packet.client.GridDebugPacket;
import cofh.thermal.dynamics.common.network.packet.client.TravelingItemsPacket;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentRedstoneControlPacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.fml.ModList;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

public class PacketHandler {

    public static void registerNetworking(final RegisterPayloadHandlersEvent event) {

        final PayloadRegistrar registrar = event.registrar(ID_THERMAL_DYNAMICS);

        // SERVER
        registrar.playToServer(AttachmentConfigPayload.TYPE, AttachmentConfigPayload.STREAM_CODEC, AttachmentConfigPacket.get()::handle);
        registrar.playToServer(AttachmentRedstoneControlPayload.TYPE, AttachmentRedstoneControlPayload.STREAM_CODEC, AttachmentRedstoneControlPacket.get()::handle);
        if (ModList.get().isLoaded("mekanism")) {
            cofh.thermal.dynamics.compat.mekanism.MekanismCompat.registerNetworking(registrar);
        }

        // CLIENT
        registrar.playToClient(AttachmentControlPayload.TYPE, AttachmentControlPayload.STREAM_CODEC, AttachmentControlPacket.get()::handle);
        registrar.playToClient(GridDebugPayload.TYPE, GridDebugPayload.STREAM_CODEC, GridDebugPacket.get()::handle);
        registrar.playToClient(TravelingItemsPayload.TYPE, TravelingItemsPayload.STREAM_CODEC, TravelingItemsPacket.get()::handle);
    }
}
