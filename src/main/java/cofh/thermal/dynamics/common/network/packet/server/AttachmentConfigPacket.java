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
import cofh.thermal.dynamics.common.inventory.attachment.AttachmentMenu;


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
            if (player.distanceToSqr(payload.pos().getX() + 0.5D, payload.pos().getY() + 0.5D, payload.pos().getZ() + 0.5D) > AttachmentMenu.INTERACTION_RANGE_SQR
                    || !(tile instanceof IDuct<?, ?> duct)
                    || !(duct.getAttachment(payload.side()) instanceof IPacketHandlerAttachment attachment)
                    || !AttachmentMenu.ownsOpenAttachment(player, payload.pos(), payload.side(), attachment)) {
                return;
            }
            int expected = attachment.configPacketSize();
            FriendlyByteBuf config = payload.buf();
            if (expected < 0 || config == null || config.readableBytes() != expected) {
                return;
            }
            try {
                attachment.handleConfigPacket(config);
            } catch (RuntimeException ignored) {
                return;
            }
            if (config.isReadable()) {
                return;
            }
            attachment.onControlUpdate();
        });
    }

    public static void sendToServer(IPacketHandlerAttachment attachment) {

        if (attachment == null || attachment.world() == null) {
            return;
        }
        PacketDistributor.sendToServer(new AttachmentConfigPayload(attachment.pos(), attachment.side(), attachment.getConfigPacket(NetworkHelper.createBuffer(attachment.world().registryAccess()))));
    }

}
