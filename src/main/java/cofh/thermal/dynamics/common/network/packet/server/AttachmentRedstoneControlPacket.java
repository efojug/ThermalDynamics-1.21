package cofh.thermal.dynamics.common.network.packet.server;

import cofh.lib.api.control.IRedstoneControllable;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.IRedstoneControllableAttachment;
import cofh.thermal.dynamics.common.network.data.server.AttachmentRedstoneControlPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public class AttachmentRedstoneControlPacket {

    public static final AttachmentRedstoneControlPacket INSTANCE = new AttachmentRedstoneControlPacket();

    public static AttachmentRedstoneControlPacket get() {

        return INSTANCE;
    }

    public void handle(final AttachmentRedstoneControlPayload payload, final IPayloadContext context) {

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
            if (tile instanceof IDuct<?, ?> duct && duct.getAttachment(payload.side()) instanceof IRedstoneControllableAttachment attachment) {
                int mode = Byte.toUnsignedInt(payload.mode());
                if (mode < IRedstoneControllable.ControlMode.VALUES.length) {
                    attachment.setControl(payload.threshold(), IRedstoneControllable.ControlMode.VALUES[mode]);
                }
            }
        });
    }

    public static void sendToServer(IRedstoneControllableAttachment attachment) {

        if (attachment == null) {
            return;
        }
        PacketDistributor.sendToServer(new AttachmentRedstoneControlPayload(attachment.pos(), attachment.side(), attachment.redstoneControl().getThreshold(), (byte) attachment.redstoneControl().getMode().ordinal()));
    }

}
