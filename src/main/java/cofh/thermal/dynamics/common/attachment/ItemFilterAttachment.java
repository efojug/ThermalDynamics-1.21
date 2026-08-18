package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.core.util.helpers.ItemHelper;
import cofh.lib.api.IConveyableData;
import cofh.lib.util.Utils;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.config.TDynConfig;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemRoute;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
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
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.FILTER;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_FILTER_ATTACHMENT;
import static net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK;

/**
 * The item duct's only extraction attachment. Pulls filtered items from the adjacent inventory each
 * tick and routes them into the grid. (Item ducts have no plain servo; fluid/chemical ducts have no
 * filter — this class carries the whole item-side extraction pipeline.)
 */
public class ItemFilterAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.filter");
    public static final int TRANSFER = 64;

    protected final IDuct<?, ?> duct;
    protected final Direction side;
    protected final BaseItemFilter filter = new BaseItemFilter(15);
    protected final RedstoneControlLogic rsControl = new RedstoneControlLogic(this);
    protected final List<ItemStack> legacyStuffedItems = new ArrayList<>();

    public ItemFilterAttachment(IDuct<?, ?> duct, Direction side) {

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

        return read(nbt, world() == null ? Utils.BUILTIN_ACCESS : world().registryAccess());
    }

    @Override
    public IAttachment read(CompoundTag nbt, HolderLookup.Provider provider) {

        if (nbt.isEmpty()) {
            return this;
        }
        filter.read(provider, nbt);
        rsControl.read(nbt);
        legacyStuffedItems.clear();
        for (Tag tag : nbt.getList("StuffedItems", Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(provider, (CompoundTag) tag);
            if (!stack.isEmpty()) {
                legacyStuffedItems.add(stack);
            }
        }
        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        CompoundTag result = write(nbt, world() == null ? Utils.BUILTIN_ACCESS : world().registryAccess());
        result.remove("StuffedItems");
        return result;
    }

    @Override
    public CompoundTag write(CompoundTag nbt, HolderLookup.Provider provider) {

        nbt.putString(TAG_TYPE, FILTER);
        filter.write(provider, nbt);
        rsControl.write(nbt);
        if (!legacyStuffedItems.isEmpty()) {
            ListTag items = new ListTag();
            for (ItemStack stack : legacyStuffedItems) {
                items.add(stack.save(provider, new CompoundTag()));
            }
            nbt.put("StuffedItems", items);
        }
        return nbt;
    }

    @Override
    public void tick() {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct)) {
            return;
        }
        if (!legacyStuffedItems.isEmpty()) {
            itemDuct.drainLegacyStuffedItems();
        }
        if (!rsControl.getState()) {
            return;
        }
        if (itemDuct.getGrid() == null || itemDuct.getGrid().hasWaitingItems()) {
            return;
        }
        int maxStacks = TDynConfig.itemFilterStacksPerTick;
        for (int sent = 0; sent < maxStacks; ++sent) {
            if (!pullAndRoute()) {
                break;
            }
        }
    }

    @Override
    public boolean needsTick() {

        return true;
    }

    protected boolean pullAndRoute() {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || world() == null) {
            return false;
        }
        ItemGrid itemGrid = itemDuct.getGrid();
        if (itemGrid == null || itemGrid.hasWaitingItems()) {
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
        ItemRoute route = itemGrid.findRoute(pos(), java.util.Set.of(pos().relative(side)), candidate, null, null);
        if (route == null) {
            return false;
        }
        int capacity = itemGrid.getInsertCapacity(route, candidate);
        if (capacity <= 0) {
            return false;
        }
        ItemStack sending = candidate.copyWithCount(Math.min(candidate.getCount(), capacity));
        ItemStack extracted = extractMatching(handler, sending);
        if (extracted.isEmpty()) {
            return false;
        }
        itemDuct.addTravelingItem(new TravelingItem(extracted, pos(), side, route));
        return true;
    }

    public List<ItemStack> legacyStuffedItems() {

        return legacyStuffedItems;
    }

    /**
     * Return routing may put an item back through the same input attachment that extracted it.
     * This does not make the attachment a general output target; the source duct performs the
     * endpoint identity check before calling this predicate.
     */
    public boolean acceptsSourceReturn(ItemStack stack) {

        return !stack.isEmpty() && filter.valid(stack);
    }

    public ItemStack insertFromExternal(ItemStack stack, boolean simulate) {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || stack.isEmpty() || !filter.valid(stack)
                || itemDuct.getGrid() == null || itemDuct.getGrid().hasWaitingItems()) {
            return stack;
        }
        ItemStack sending = stack.copyWithCount(Math.min(stack.getCount(), TRANSFER));
        ItemRoute route = itemDuct.getGrid().findRoute(pos(), java.util.Set.of(pos().relative(side)), sending, null, null);
        if (route == null) {
            return stack;
        }
        int capacity = itemDuct.getGrid().getInsertCapacity(route, sending);
        if (capacity <= 0) {
            return stack;
        }
        sending = sending.copyWithCount(Math.min(sending.getCount(), capacity));
        if (!simulate) {
            itemDuct.addTravelingItem(new TravelingItem(sending, pos(), side, route));
        }
        return stack.copyWithCount(stack.getCount() - sending.getCount());
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

        return new ItemStack(ITEMS.get(ID_FILTER_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? FILTER_ATTACHMENT_ACTIVE_LOC : FILTER_ATTACHMENT_LOC;
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
    public int configPacketSize() {

        return 2;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {

        boolean allowList = buffer.readBoolean();
        boolean checkNbt = buffer.readBoolean();
        filter.setAllowList(allowList);
        filter.setCheckNBT(checkNbt);
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
