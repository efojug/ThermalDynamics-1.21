package cofh.thermal.dynamics.common.inventory.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilterOptions;
import cofh.lib.common.inventory.SlotFalseCopy;
import cofh.lib.common.inventory.wrapper.InvWrapperGeneric;
import cofh.lib.util.helpers.MathHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.ItemFilterAttachment;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;

import static cofh.thermal.dynamics.init.registries.TDynContainers.ITEM_SERVO_ATTACHMENT_CONTAINER;

public class ItemServoAttachmentMenu extends AttachmentMenu implements IFilterOptions {

    public final ItemFilterAttachment attachment;
    protected final BaseItemFilter filter;
    protected final InvWrapperGeneric filterInventory;

    public ItemServoAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        this(ITEM_SERVO_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);
    }

    protected ItemServoAttachmentMenu(MenuType<?> type, int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(type, id, world, pos, side, inventory, player);
        attachment = hostTile instanceof IDuct<?, ?> duct && duct.getAttachment(side) instanceof ItemFilterAttachment itemFilter ? itemFilter : null;
        filter = attachment == null ? BaseItemFilter.ZERO : (BaseItemFilter) attachment.getFilter();
        allowSwap = false;
        filterInventory = new InvWrapperGeneric(this, filter.getItems(), filter.size()) {
            @Override
            public void setChanged() {

                filter.setItems(filterInventory.getStacks());
            }
        };
        int rows = MathHelper.clamp(filter.size() / 3, 1, 3);
        int rowSize = filter.size() / rows;
        int xOffset = getCenteredServoFilterX(rowSize);
        int yOffset = 44 - 9 * rows;
        for (int i = 0; i < filter.size(); ++i) {
            addSlot(new SlotFalseCopy(filterInventory, i, xOffset + i % rowSize * 18, yOffset + i / rowSize * 18));
        }
        bindPlayerInventory(inventory);
    }

    public int getFilterSize() {

        return filter.size();
    }

    @Override
    protected int getMergeableSlotCount() {

        return filterInventory.getContainerSize();
    }

    @Override
    public void removed(Player player) {

        filter.setItems(filterInventory.getStacks());
        super.removed(player);
    }

    @Override
    public boolean getAllowList() {

        return filter.getAllowList();
    }

    @Override
    public boolean setAllowList(boolean allowList) {

        boolean result = filter.setAllowList(allowList);
        if (attachment != null) {
            AttachmentConfigPacket.sendToServer(attachment);
        }
        return result;
    }

    @Override
    public boolean getCheckNBT() {

        return filter.getCheckNBT();
    }

    @Override
    public boolean setCheckNBT(boolean checkNBT) {

        boolean result = filter.setCheckNBT(checkNBT);
        if (attachment != null) {
            AttachmentConfigPacket.sendToServer(attachment);
        }
        return result;
    }

}
