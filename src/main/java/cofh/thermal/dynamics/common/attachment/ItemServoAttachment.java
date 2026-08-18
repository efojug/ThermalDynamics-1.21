package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.core.util.helpers.ItemHelper;
import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.ItemRoute;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;
import static net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK;

public class ItemServoAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.servo");
    public static final int TRANSFER = 64;
    public static final int TICK_DELAY = 10;

    protected final IDuct<?, ?> duct;
    protected final Direction side;
    protected final BaseItemFilter filter = new BaseItemFilter(15);
    protected final RedstoneControlLogic rsControl = new RedstoneControlLogic(this);
    protected final List<ItemStack> stuffedItems = new ArrayList<>();
    protected int sendCooldown;

    public ItemServoAttachment(IDuct<?, ?> duct, Direction side) {

        this.duct = duct;
        this.side = side;
    }

    @Override
    public IDuct<?, ?> duct() {

        return duct;
    }

    @Override
    public Direction side() {

        return side;
    }

    @Override
    public void invalidate() {

    }

    @Override
    public IAttachment read(CompoundTag nbt) {

        if (nbt.isEmpty()) {
            return this;
        }
        filter.read(nbt);
        rsControl.read(nbt);
        sendCooldown = Math.max(0, nbt.getInt("SendCooldown"));
        stuffedItems.clear();
        for (Tag tag : nbt.getList("StuffedItems", Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(world().registryAccess(), (CompoundTag) tag);
            if (!stack.isEmpty()) {
                stuffedItems.add(stack);
            }
        }
        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        nbt.putString(TAG_TYPE, SERVO);
        filter.write(nbt);
        rsControl.write(nbt);
        nbt.putInt("SendCooldown", sendCooldown);
        if (!stuffedItems.isEmpty()) {
            ListTag items = new ListTag();
            for (ItemStack stack : stuffedItems) {
                items.add(stack.save(world().registryAccess(), new CompoundTag()));
            }
            nbt.put("StuffedItems", items);
        }
        return nbt;
    }

    @Override
    public void tick() {

        if (!rsControl.getState()) {
            return;
        }
        if (sendCooldown > 0) {
            --sendCooldown;
            return;
        }
        if (sendStuffedItems(1) == 0) {
            pullAndRoute();
        }
        sendCooldown = TICK_DELAY - 1;
    }

    @Override
    public boolean needsTick() {

        return true;
    }

    protected boolean pullAndRoute() {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || world() == null) {
            return false;
        }
        BlockPos target = pos().relative(side);
        BlockEntity targetTile = world().getBlockEntity(target);
        IItemHandler handler = targetTile == null ? null : world().getCapability(BLOCK, target,
                targetTile.getBlockState(), targetTile, side.getOpposite());
        if (handler == null || handler.getSlots() <= 0) {
            return false;
        }
        ItemStack candidate = findCandidate(handler);
        if (candidate.isEmpty()) {
            return false;
        }
        ItemRoute route = itemDuct.getGrid().findRoute(pos(), side, candidate, null, null);
        if (route == null) {
            return false;
        }
        ItemStack extracted = extractMatching(handler, candidate);
        if (extracted.isEmpty()) {
            return false;
        }
        itemDuct.addTravelingItem(new TravelingItem(extracted, pos(), side, route));
        return true;
    }

    protected int sendStuffedItems(int maxStacks) {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct)) {
            return 0;
        }
        int sent = 0;
        for (Iterator<ItemStack> iterator = stuffedItems.iterator(); iterator.hasNext() && sent < maxStacks; ) {
            ItemStack stack = iterator.next();
            ItemStack sending = stack.copyWithCount(Math.min(stack.getCount(), TRANSFER));
            ItemRoute route = itemDuct.getGrid().findRoute(pos(), side, sending, null, null);
            if (route == null) {
                continue;
            }
            itemDuct.addTravelingItem(new TravelingItem(sending, pos(), side, route));
            stack.shrink(sending.getCount());
            if (stack.isEmpty()) {
                iterator.remove();
            }
            ++sent;
        }
        return sent;
    }

    public void stuff(ItemStack stack) {

        if (stack.isEmpty()) {
            return;
        }
        for (ItemStack stuffed : stuffedItems) {
            if (ItemHelper.itemsEqualWithTags(stuffed, stack)) {
                stuffed.grow(stack.getCount());
                duct.onAttachmentUpdate();
                return;
            }
        }
        stuffedItems.add(stack.copy());
        duct.onAttachmentUpdate();
    }

    public ItemStack insertFromExternal(ItemStack stack, boolean simulate) {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || stack.isEmpty() || !filter.valid(stack)) {
            return stack;
        }
        ItemStack sending = stack.copyWithCount(Math.min(stack.getCount(), TRANSFER));
        ItemRoute route = itemDuct.getGrid().findRoute(pos(), side, sending, null, null);
        if (route == null) {
            return stack;
        }
        if (!simulate) {
            itemDuct.addTravelingItem(new TravelingItem(sending, pos(), side, route));
        }
        return stack.copyWithCount(stack.getCount() - sending.getCount());
    }

    public void dropStuffedItems() {

        if (world() == null) {
            return;
        }
        for (ItemStack stack : stuffedItems) {
            cofh.lib.util.Utils.dropDismantleStackIntoWorld(stack, world(), pos());
        }
        stuffedItems.clear();
    }

    private ItemStack findCandidate(IItemHandler handler) {

        for (int slot = 0; slot < handler.getSlots(); ++slot) {
            ItemStack initial = handler.extractItem(slot, TRANSFER, true);
            if (initial.isEmpty() || !filter.valid(initial)) {
                continue;
            }
            ItemStack combined = initial.copy();
            for (int next = slot + 1; next < handler.getSlots() && combined.getCount() < TRANSFER; ++next) {
                ItemStack extra = handler.extractItem(next, TRANSFER - combined.getCount(), true);
                if (!extra.isEmpty() && ItemHelper.itemsEqualWithTags(initial, extra)) {
                    combined.grow(extra.getCount());
                }
            }
            return combined;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack extractMatching(IItemHandler handler, ItemStack candidate) {

        ItemStack combined = ItemStack.EMPTY;
        int remaining = candidate.getCount();
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; ++slot) {
            ItemStack sample = handler.extractItem(slot, remaining, true);
            if (sample.isEmpty() || !ItemHelper.itemsEqualWithTags(candidate, sample)) {
                continue;
            }
            ItemStack extracted = handler.extractItem(slot, remaining, false);
            if (combined.isEmpty()) {
                combined = extracted;
            } else {
                combined.grow(extracted.getCount());
            }
            remaining -= extracted.getCount();
        }
        return combined;
    }

    @Override
    public ItemStack getItem() {

        return new ItemStack(ITEMS.get(ID_SERVO_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? SERVO_ATTACHMENT_ACTIVE_LOC : SERVO_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {

        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {

        return new ItemServoAttachmentMenu(id, player.level(), pos(), side, inventory, player);
    }

    @Override
    public IFilter getFilter() {

        return filter;
    }

    @Override
    public FriendlyByteBuf getConfigPacket(FriendlyByteBuf buffer) {

        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());
        return buffer;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {

        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
    }

    @Override
    public FriendlyByteBuf getControlPacket(FriendlyByteBuf buffer) {

        rsControl.writeToBuffer(buffer);
        getConfigPacket(buffer);
        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {

        rsControl.readFromBuffer(buffer);
        handleConfigPacket(buffer);
    }

    @Override
    public RedstoneControlLogic redstoneControl() {

        return rsControl;
    }

    @Override
    public void readConveyableData(Player player, CompoundTag tag) {

        rsControl.readSettings(tag);
        filter.read(tag);
        onControlUpdate();
    }

    @Override
    public void writeConveyableData(Player player, CompoundTag tag) {

        rsControl.writeSettings(tag);
        filter.write(tag);
    }

}
