package cofh.thermal.dynamics.common.network.packet.server;

import cofh.lib.util.helpers.NetworkHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.IPacketHandlerAttachment;
import cofh.thermal.dynamics.common.network.data.server.AttachmentConfigPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public class AttachmentConfigPacket {

    public static final AttachmentConfigPacket INSTANCE = new AttachmentConfigPacket();

    public static AttachmentConfigPacket get() {

        return INSTANCE;
    }

    public void handle(final AttachmentConfigPayload payload, final IPayloadContext context) {

        context.enqueueWork(() -> {

            Player player = context.player();
            if (player == null) {
                return;
            }

            Level world = player.level();
            if (!world.isLoaded(payload.pos())) {
                return;
            }
            BlockEntity tile = world.getBlockEntity(payload.pos());
            if (tile instanceof IDuct<?, ?> duct && duct.getAttachment(payload.side()) instanceof IPacketHandlerAttachment attachment) {
                attachment.handleConfigPacket(payload.buf());
            }
        });
    }

    public static void sendToServer(IPacketHandlerAttachment attachment) {

        if (attachment == null || attachment.world() == null) {
            return;
        }
        PacketDistributor.sendToServer(new AttachmentConfigPayload(attachment.pos(), attachment.side(), attachment.getConfigPacket(NetworkHelper.createBuffer(attachment.world().registryAccess()))));
    }

}
