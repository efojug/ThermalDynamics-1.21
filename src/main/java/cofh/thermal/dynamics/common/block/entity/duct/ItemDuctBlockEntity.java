package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.attachment.ItemServoAttachment;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemGridNode;
import cofh.thermal.dynamics.common.grid.item.ItemRoute;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemDuctBlockEntity extends DuctBlockEntity<ItemGrid, ItemGridNode> {

    private static final String TAG_TRAVELING_ITEMS = "TravelingItems";

    private final List<TravelingItem> travelingItems = new ArrayList<>();
    private final IItemHandler[] itemHandlers = new IItemHandler[6];

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {

        super(ITEM_DUCT_BLOCK_ENTITY.get(), pos, state);
    }

    public ItemDuctBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {

        super(type, pos, state);
    }

    @Override
    protected boolean canConnectToBlock(Direction dir) {

        if (!connections[dir.ordinal()].allowBlockConnection()) {
            return false;
        }
        BlockEntity tile = level.getBlockEntity(getBlockPos().relative(dir));
        if (tile == null || GridHelper.getGridHost(tile) != null) {
            return false;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(),
                tile.getBlockState(), tile, dir.getOpposite());
        return handler != null && handler.getSlots() > 0;
    }

    @Override
    public IGridType<ItemGrid> getGridType() {

        return ITEM_GRID.get();
    }

    public boolean hasTravelingItems() {

        return !travelingItems.isEmpty();
    }

    public List<TravelingItem> getTravelingItems() {

        return travelingItems;
    }

    public void addTravelingItem(TravelingItem item) {

        travelingItems.add(item);
        if (level != null && !level.isClientSide()) {
            getGrid().markDirty(this);
        }
    }

    public void removeTravelingItem(TravelingItem item) {

        if (travelingItems.remove(item) && level != null && !level.isClientSide()) {
            getGrid().markDirty(this);
        }
    }

    public void serverTick() {

        for (TravelingItem item : List.copyOf(travelingItems)) {
            item.tick(this);
        }
    }

    public void clientTick() {

        for (TravelingItem item : List.copyOf(travelingItems)) {
            item.clientTick(this);
        }
    }

    @Nullable
    public ItemDuctBlockEntity getConnectedDuct(Direction side) {

        if (connections[side.ordinal()].allowDuctConnection() && level.getBlockEntity(getBlockPos().relative(side)) instanceof ItemDuctBlockEntity duct &&
                duct.getConnectionType(side.getOpposite()).allowDuctConnection() && duct.getGrid() == getGrid()) {
            return duct;
        }
        return null;
    }

    @Nullable
    public ItemDuctBlockEntity getAdjacentItemDuct(Direction side) {

        return level.getBlockEntity(getBlockPos().relative(side)) instanceof ItemDuctBlockEntity duct ? duct : null;
    }

    public ItemStack insertIntoEndpoint(Direction side, ItemStack stack) {

        if (connections[side.ordinal()] == IDuct.ConnectionType.DISABLED || getAttachment(side) instanceof ItemServoAttachment) {
            return stack;
        }
        BlockPos target = getBlockPos().relative(side);
        if (GridHelper.getGridHost(level, target) != null) {
            return stack;
        }
        BlockEntity targetTile = level.getBlockEntity(target);
        if (targetTile == null) {
            return stack;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target,
                targetTile.getBlockState(), targetTile, side.getOpposite());
        return handler == null || handler.getSlots() <= 0 ? stack : ItemHandlerHelper.insertItemStacked(handler, stack, false);
    }

    public ItemStack insertFromExternal(Direction side, ItemStack stack, boolean simulate) {

        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack;
        while (!remaining.isEmpty()) {
            int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            ItemStack sending = remaining.copyWithCount(amount);
            ItemRoute route = getGrid().findRoute(getBlockPos(), side, sending, null, null);
            if (route == null) {
                return remaining;
            }
            if (!simulate) {
                addTravelingItem(new TravelingItem(sending, getBlockPos(), side, route));
            }
            remaining = remaining.copyWithCount(remaining.getCount() - amount);
        }
        return ItemStack.EMPTY;
    }

    public void syncTravelingItems() {

        if (level == null || level.isClientSide()) {
            return;
        }
        setChanged();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public void dropItemContents() {

        if (level == null || level.isClientSide()) {
            return;
        }
        for (TravelingItem item : travelingItems) {
            ItemStack stack = item.stack();
            if (!stack.isEmpty()) {
                cofh.lib.util.Utils.dropDismantleStackIntoWorld(stack, level, getBlockPos());
            }
        }
        travelingItems.clear();
        for (Direction side : Direction.values()) {
            if (getAttachment(side) instanceof ItemServoAttachment servo) {
                servo.dropStuffedItems();
            }
        }
    }

    @Override
    public void onLoad() {

        super.onLoad();
        if (level != null && !level.isClientSide() && hasTravelingItems()) {
            getGrid().track(this);
        }
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.loadAdditional(tag, provider);
        travelingItems.clear();
        for (Tag entry : tag.getList(TAG_TRAVELING_ITEMS, Tag.TAG_COMPOUND)) {
            TravelingItem item = TravelingItem.load((CompoundTag) entry, provider);
            if (!item.stack().isEmpty()) {
                travelingItems.add(item);
            }
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.saveAdditional(tag, provider);
        ListTag items = new ListTag();
        for (TravelingItem item : travelingItems) {
            items.add(item.save(new CompoundTag(), provider));
        }
        tag.put(TAG_TRAVELING_ITEMS, items);
    }

    @Override
    @SuppressWarnings ("unchecked")
    public <T, C> T getCapability(BlockCapability<T, C> capability, Direction side) {

        if (capability == Capabilities.ItemHandler.BLOCK && side != null && level != null && !level.isClientSide() &&
                connections[side.ordinal()] != IDuct.ConnectionType.DISABLED) {
            IItemHandler handler = itemHandlers[side.ordinal()];
            if (getAttachment(side) instanceof ItemServoAttachment servo) {
                if (!(handler instanceof ServoItemHandler)) {
                    handler = new ServoItemHandler(servo);
                    itemHandlers[side.ordinal()] = handler;
                }
            } else if (!(handler instanceof DuctItemHandler)) {
                handler = new DuctItemHandler(this, side);
                itemHandlers[side.ordinal()] = handler;
            }
            return (T) handler;
        }
        return super.getCapability(capability, side);
    }

    private static class DuctItemHandler implements IItemHandler {

        private final ItemDuctBlockEntity duct;
        private final Direction side;

        private DuctItemHandler(ItemDuctBlockEntity duct, Direction side) {

            this.duct = duct;
            this.side = side;
        }

        @Override
        public int getSlots() {

            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {

            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {

            return slot == 0 ? duct.insertFromExternal(side, stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return slot == 0 ? 64 : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {

            return slot == 0;
        }

    }

    private static class ServoItemHandler implements IItemHandler {

        private final ItemServoAttachment servo;

        private ServoItemHandler(ItemServoAttachment servo) {

            this.servo = servo;
        }

        @Override
        public int getSlots() {

            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {

            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {

            return slot == 0 ? servo.insertFromExternal(stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return slot == 0 ? ItemServoAttachment.TRANSFER : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {

            return slot == 0;
        }

    }

}
