package cofh.thermal.dynamics.common.inventory.attachment;

import cofh.core.common.network.packet.client.ContainerGuiPacket;
import cofh.core.util.filter.BaseFluidFilter;
import cofh.core.util.filter.IFilterOptions;
import cofh.lib.common.inventory.SlotFalseCopy;
import cofh.lib.common.inventory.wrapper.InvWrapperFluids;
import cofh.lib.util.helpers.MathHelper;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.FluidTurboServoAttachment;
import cofh.thermal.dynamics.common.network.packet.server.AttachmentConfigPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

import static cofh.thermal.dynamics.init.registries.TDynContainers.FLUID_TURBO_SERVO_ATTACHMENT_CONTAINER;

public class FluidTurboServoAttachmentMenu extends AttachmentMenu implements IFilterOptions {

    public final FluidTurboServoAttachment attachment;

    protected BaseFluidFilter filter;
    protected InvWrapperFluids filterInventory;

    public FluidTurboServoAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(FLUID_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);

        if (hostTile instanceof IDuct<?, ?> duct && duct.getAttachment(side) instanceof FluidTurboServoAttachment expectedAttachment) {
            this.attachment = expectedAttachment;
            this.filter = (BaseFluidFilter) attachment.getFilter();
        } else {
            this.attachment = null;
        }
        allowSwap = false;
        if (filter != null) {
            int slots = filter.size();
            filterInventory = new InvWrapperFluids(this, filter.getFluids(), slots) {
                @Override
                public void setChanged() {

                    filter.setFluids(filterInventory.getStacks());
                }
            };

            int rows = MathHelper.clamp(slots / 3, 1, 3);
            int rowSize = slots / rows;

            int xOffset = getCenteredServoFilterX(rowSize);
            int yOffset = 44 - 9 * rows;

            for (int i = 0; i < filter.size(); ++i) {
                addSlot(new SlotFalseCopy(filterInventory, i, xOffset + i % rowSize * 18, yOffset + i / rowSize * 18));
            }
        }
        bindPlayerInventory(inventory);
    }

    public int getFilterSize() {

        return filter == null ? 0 : filter.size();
    }

    public List<FluidStack> getFilterStacks() {

        return filterInventory == null ? List.of() : filterInventory.getStacks();
    }

    @Override
    protected int getMergeableSlotCount() {

        return filterInventory == null ? 0 : filterInventory.getContainerSize();
    }

    @Override
    public void broadcastChanges() {

        // This seems strange when the Attachment already has a Gui Packet, but the attachment doesn't know about the filter inventory.
        super.broadcastChanges();
        ContainerGuiPacket.sendToClient(this, player);
    }

    @Override
    public void removed(Player playerIn) {

        if (filter != null && filterInventory != null) {
            filter.setFluids(filterInventory.getStacks());
        }
        super.removed(playerIn);
    }

    // region NETWORK
    @Override
    public FriendlyByteBuf getGuiPacket(FriendlyByteBuf buffer) {

        byte size = (byte) getFilterSize();
        buffer.writeByte(size);
        for (int i = 0; i < size; ++i) {
            FluidStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buffer, getFilterStacks().get(i));
        }

        return buffer;
    }

    @Override
    public void handleGuiPacket(FriendlyByteBuf buffer) {

        if (!buffer.isReadable()) return;
        int size = Byte.toUnsignedInt(buffer.readByte());
        if (filterInventory == null || size > getFilterSize()) return;
        List<FluidStack> fluidStacks = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; ++i) {
                if (!buffer.isReadable()) return;
                fluidStacks.add(FluidStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buffer));
            }
        } catch (RuntimeException ignored) {
            return;
        }
        filterInventory.readFromSource(fluidStacks);
    }
    // endregion

    // region IFilterOptions
    @Override
    public boolean getAllowList() {

        return filter != null && filter.getAllowList();
    }

    @Override
    public boolean setAllowList(boolean allowList) {

        if (filter == null || attachment == null) return false;
        boolean ret = filter.setAllowList(allowList);
        AttachmentConfigPacket.sendToServer(attachment);
        return ret;
    }

    @Override
    public boolean getCheckNBT() {

        return filter != null && filter.getCheckNBT();
    }

    @Override
    public boolean setCheckNBT(boolean checkNBT) {

        if (filter == null || attachment == null) return false;
        boolean ret = filter.setCheckNBT(checkNBT);
        AttachmentConfigPacket.sendToServer(attachment);
        return ret;
    }
    // endregion
}
