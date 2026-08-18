package cofh.thermal.dynamics.compat.mekanism.network.packet.server;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.compat.mekanism.attachment.ChemicalServoAttachment;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import cofh.thermal.dynamics.compat.mekanism.network.data.server.ChemicalFilterPayload;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ChemicalFilterPacket {

    private static final ChemicalFilterPacket INSTANCE = new ChemicalFilterPacket();

    private ChemicalFilterPacket() {

    }

    public static ChemicalFilterPacket get() {

        return INSTANCE;
    }

    public void handle(ChemicalFilterPayload payload, IPayloadContext context) {

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
            if (tile instanceof IDuct<?, ?> duct && duct.getAttachment(payload.side()) instanceof ChemicalServoAttachment attachment &&
                    payload.slot() >= 0 && payload.slot() < attachment.getChemicalFilter().size()) {
                ChemicalStack chemical = ChemicalStack.parseOptional(world.registryAccess(), payload.chemical());
                attachment.setFilterChemical(payload.slot(), chemical);
                if (player.containerMenu instanceof ChemicalServoAttachmentMenu menu && menu.attachment == attachment) {
                    menu.applyFilterChemical(payload.slot(), chemical);
                }
            }
        });
    }

    public static void sendToServer(ChemicalServoAttachment attachment, int slot, ChemicalStack chemical) {

        if (attachment == null || attachment.world() == null) {
            return;
        }
        CompoundTag chemicalTag = (CompoundTag) chemical.saveOptional(attachment.world().registryAccess());
        PacketDistributor.sendToServer(new ChemicalFilterPayload(attachment.pos(), attachment.side(), slot, chemicalTag));
    }

}
