package cofh.thermal.dynamics.compat.mekanism.inventory;

import cofh.core.util.filter.IFilterOptions;
import cofh.core.common.network.packet.client.ContainerGuiPacket;
import cofh.lib.common.inventory.SlotFalseCopy;
import cofh.lib.util.helpers.MathHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.inventory.attachment.AttachmentMenu;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import cofh.thermal.dynamics.compat.mekanism.attachment.ChemicalFilter;
import cofh.thermal.dynamics.compat.mekanism.attachment.ChemicalServoAttachment;
import cofh.thermal.dynamics.compat.mekanism.network.packet.server.ChemicalFilterPacket;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_SERVO_ATTACHMENT_CONTAINER;

public class ChemicalServoAttachmentMenu extends AttachmentMenu implements IFilterOptions {

    public final ChemicalServoAttachment attachment;
    protected final ChemicalFilter filter;
    protected final ChemicalFilterInventory filterInventory;

    public ChemicalServoAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        this(CHEMICAL_SERVO_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);
    }

    protected ChemicalServoAttachmentMenu(MenuType<?> type, int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(type, id, world, pos, side, inventory, player);
        attachment = hostTile instanceof IDuct<?, ?> duct && duct.getAttachment(side) instanceof ChemicalServoAttachment chemicalServo ? chemicalServo : null;
        filter = attachment == null ? null : attachment.getChemicalFilter();
        allowSwap = false;
        if (filter != null) {
            filterInventory = new ChemicalFilterInventory(this, filter.getChemicals(), filter.size()) {
                @Override
                public void setChanged() {

                    filter.setChemicals(filterInventory.getChemicals());
                }
            };
            int rows = MathHelper.clamp(filter.size() / 3, 1, 3);
            int rowSize = filter.size() / rows;
            int xOffset = getCenteredServoFilterX(rowSize);
            int yOffset = 44 - 9 * rows;
            for (int i = 0; i < filter.size(); ++i) {
                addSlot(new SlotFalseCopy(filterInventory, i, xOffset + i % rowSize * 18, yOffset + i / rowSize * 18));
            }
        } else {
            filterInventory = null;
        }
        bindPlayerInventory(inventory);
    }

    public int getFilterSize() {

        return filter == null ? 0 : filter.size();
    }

    public List<ChemicalStack> getFilterStacks() {

        return filterInventory == null ? List.of() : filterInventory.getChemicals();
    }

    public void setFilterChemical(int slot, ChemicalStack chemical) {

        if (attachment == null || filterInventory == null) {
            return;
        }
        filterInventory.setChemical(slot, chemical);
        filter.setChemicals(filterInventory.getChemicals());
        ChemicalFilterPacket.sendToServer(attachment, slot, chemical);
    }

    /**
     * Mirrors a JEI ghost ingredient update into the server menu inventory so
     * subsequent menu synchronization and closing the menu retain the change.
     */
    public void applyFilterChemical(int slot, ChemicalStack chemical) {

        if (attachment == null || filterInventory == null) {
            return;
        }
        filterInventory.setChemical(slot, chemical);
        filter.setChemicals(filterInventory.getChemicals());
    }

    @Override
    protected int getMergeableSlotCount() {

        return filterInventory == null ? 0 : filterInventory.getContainerSize();
    }

    @Override
    public void broadcastChanges() {

        super.broadcastChanges();
        ContainerGuiPacket.sendToClient(this, player);
    }

    @Override
    public void removed(Player player) {

        if (filterInventory != null) {
            filter.setChemicals(filterInventory.getChemicals());
        }
        super.removed(player);
    }

    @Override
    public FriendlyByteBuf getGuiPacket(FriendlyByteBuf buffer) {

        buffer.writeByte(getFilterSize());
        for (ChemicalStack chemical : filter.getChemicals()) {
            ChemicalStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buffer, chemical);
        }
        return buffer;
    }

    @Override
    public void handleGuiPacket(FriendlyByteBuf buffer) {

        int size = Byte.toUnsignedInt(buffer.readByte());
        List<ChemicalStack> chemicals = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            chemicals.add(ChemicalStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buffer));
        }
        if (filterInventory != null) {
            filterInventory.readFromSource(chemicals);
        }
    }

    @Override public boolean getAllowList() { return filter != null && filter.getAllowList(); }
    @Override public boolean getCheckNBT() { return filter != null && filter.getCheckNBT(); }

    @Override
    public boolean setAllowList(boolean allowList) {

        boolean result = filter != null && filter.setAllowList(allowList);
        if (attachment != null) {
            AttachmentConfigPacket.sendToServer(attachment);
        }
        return result;
    }

    @Override
    public boolean setCheckNBT(boolean checkNBT) {

        boolean result = filter != null && filter.setCheckNBT(checkNBT);
        if (attachment != null) {
            AttachmentConfigPacket.sendToServer(attachment);
        }
        return result;
    }

}
