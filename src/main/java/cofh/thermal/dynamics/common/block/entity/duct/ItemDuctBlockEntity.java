package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.attachment.ItemFilterAttachment;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemGridNode;
import cofh.thermal.dynamics.common.grid.item.ItemRoute;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import cofh.thermal.dynamics.common.network.data.client.TravelingItemsPayload;
import cofh.thermal.dynamics.client.ClientTravelingItemIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.ITEM_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemDuctBlockEntity extends DuctBlockEntity<ItemGrid, ItemGridNode> {

    private static final String TAG_TRAVELING_ITEMS = "TravelingItems";

    private final List<TravelingItem> travelingItems = new ArrayList<>();
    private final Map<UUID, TravelingItem> travelingById = new HashMap<>();
    private final Map<UUID, TravelingItem> pendingUpdated = new HashMap<>();
    private final Map<UUID, TravelingItem> pendingMoved = new LinkedHashMap<>();
    private final Map<UUID, Long> pendingRemoved = new LinkedHashMap<>();
    private boolean repairedOnLoad;
    private final IItemHandler[] itemHandlers = new IItemHandler[6];
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IItemHandler, Direction>[] capCaches = new BlockCapabilityCache[6];

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {

        super(ITEM_DUCT_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void invalidateAttachments() {

        super.invalidateAttachments();
        Arrays.fill(itemHandlers, null);
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

        TravelingItem previous = travelingById.putIfAbsent(item.id(), item);
        if (previous == item) return;
        if (previous != null) throw new IllegalStateException("Duplicate traveling-item UUID in duct: " + item.id());
        travelingItems.add(item);
        setChanged();
        if (level != null && !level.isClientSide()) {
            item.bumpSyncRevision();
            pendingRemoved.remove(item.id());
            pendingMoved.remove(item.id());
            pendingUpdated.put(item.id(), item);
            if (getGrid() != null) {
                getGrid().addReservation(item);
                getGrid().markDirty(this);
            }
        }
    }

    public void removeTravelingItem(TravelingItem item) {

        if (travelingItems.remove(item)) {
            travelingById.remove(item.id(), item);
            setChanged();
            if (level != null && !level.isClientSide()) {
                if (getGrid() != null) {
                    if (item.waiting()) getGrid().setItemWaiting(false, true);
                    getGrid().removeReservation(item);
                }
                pendingUpdated.remove(item.id());
                pendingMoved.remove(item.id());
                pendingRemoved.put(item.id(), item.bumpSyncRevision());
                if (getGrid() != null) getGrid().markDirty(this);
            }
        }
    }

    /**
     * Transfers a traveling item into the next duct as ordinary route progress. Unlike
     * {@link #removeTravelingItem}, no client removal is queued, and instead of a full snapshot the
     * destination duct queues a lightweight positional heartbeat — the client already knows the
     * path and only needs the authoritative route index. A full snapshot is still sent periodically
     * as a safety refresh for observers that missed earlier state.
     */
    public void moveTravelingItemTo(ItemDuctBlockEntity next, TravelingItem item) {

        if (travelingItems.remove(item)) {
            travelingById.remove(item.id(), item);
            pendingUpdated.remove(item.id());
            pendingMoved.remove(item.id());
            setChanged();
        }
        next.acceptMovedTravelingItem(item);
    }

    private static final int MOVE_FULL_SYNC_INTERVAL = 16;

    private void acceptMovedTravelingItem(TravelingItem item) {

        TravelingItem previous = travelingById.putIfAbsent(item.id(), item);
        if (previous == item) return;
        if (previous != null) throw new IllegalStateException("Duplicate traveling-item UUID in duct: " + item.id());
        travelingItems.add(item);
        setChanged();
        if (level != null && !level.isClientSide()) {
            item.bumpSyncRevision();
            pendingRemoved.remove(item.id());
            if (item.age() % MOVE_FULL_SYNC_INTERVAL == 0) {
                pendingMoved.remove(item.id());
                pendingUpdated.put(item.id(), item);
            } else {
                pendingMoved.put(item.id(), item);
            }
            if (getGrid() != null) getGrid().markDirty(this);
        }
    }

    public void markTravelingItemUpdated(TravelingItem item) {

        if (item == null || travelingById.get(item.id()) != item) return;
        item.bumpSyncRevision();
        pendingRemoved.remove(item.id());
        pendingMoved.remove(item.id());
        pendingUpdated.put(item.id(), item);
        setChanged();
        if (level != null && !level.isClientSide() && getGrid() != null) {
            getGrid().updateReservation(item);
            getGrid().markDirty(this);
        }
    }

    public void drainLegacyStuffedItems() {

        ItemGrid itemGrid = getGrid();
        if (level == null || level.isClientSide() || itemGrid == null) return;
        for (Direction side : Direction.values()) {
            if (!(getAttachment(side) instanceof ItemFilterAttachment filter)) continue;
            for (Iterator<ItemStack> iterator = filter.legacyStuffedItems().iterator(); iterator.hasNext(); ) {
                ItemStack legacy = iterator.next();
                if (legacy.isEmpty()) { iterator.remove(); continue; }
                ItemStack sending = legacy.copyWithCount(Math.min(legacy.getCount(), ItemFilterAttachment.TRANSFER));
                ItemRoute route = itemGrid.findRoute(getBlockPos(), java.util.Set.of(getBlockPos().relative(side)), sending, null, null);
                if (route == null) continue;
                int capacity = itemGrid.getInsertCapacity(route, sending);
                if (capacity <= 0) continue;
                sending = sending.copyWithCount(Math.min(sending.getCount(), capacity));
                addTravelingItem(new TravelingItem(sending, getBlockPos(), side, route));
                legacy.shrink(sending.getCount());
                if (legacy.isEmpty()) iterator.remove();
            }
        }
    }

    public void serverTick() {

        if (travelingItems.isEmpty()) {
            return;
        }
        for (TravelingItem item : List.copyOf(travelingItems)) {
            item.tick(this);
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

        if (connections[side.ordinal()] == IDuct.ConnectionType.DISABLED || getAttachment(side) instanceof ItemFilterAttachment) {
            return stack;
        }
        return insertIntoExternalEndpoint(side, stack);
    }

    /**
     * Inserts a returning item into the exact external endpoint it was extracted from. An input
     * filter is allowed only through this source-bound path; normal routing still cannot target any
     * input filter, including this one.
     */
    public ItemStack insertIntoSourceEndpoint(Direction side, ItemStack stack) {

        if (connections[side.ordinal()] == IDuct.ConnectionType.DISABLED) {
            return stack;
        }
        if (getAttachment(side) instanceof ItemFilterAttachment filter && !filter.acceptsSourceReturn(stack)) {
            return stack;
        }
        return insertIntoExternalEndpoint(side, stack);
    }

    private ItemStack insertIntoExternalEndpoint(Direction side, ItemStack stack) {

        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
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
        ItemGrid itemGrid = getGrid();
        if (itemGrid == null || itemGrid.hasWaitingItems()) {
            return stack;
        }
        ItemStack remaining = stack;
        Map<BlockPos, Integer> simulatedReservations = simulate ? new HashMap<>() : Map.of();
        Set<BlockPos> excludedTargets = new HashSet<>();
        excludedTargets.add(getBlockPos().relative(side));
        while (!remaining.isEmpty()) {
            int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            ItemStack sending = remaining.copyWithCount(amount);
            ItemRoute route = itemGrid.findRoute(getBlockPos(), excludedTargets, sending, null, null);
            if (route == null) {
                return remaining;
            }
            BlockPos target = route.destination().relative(route.destinationSide());
            int simulatedReserved = simulatedReservations.getOrDefault(target, 0);
            int capacity = itemGrid.getInsertCapacity(route, sending, simulatedReserved);
            if (capacity <= 0) {
                if (simulate && simulatedReserved > 0) {
                    excludedTargets.add(target);
                    continue;
                }
                return remaining;
            }
            int transfer = Math.min(amount, capacity);
            sending = sending.copyWithCount(transfer);
            if (!simulate) {
                addTravelingItem(new TravelingItem(sending, getBlockPos(), side, route));
            } else {
                simulatedReservations.merge(target, transfer,
                        (current, added) -> (int) Math.min(Integer.MAX_VALUE, (long) current + added));
            }
            remaining = remaining.copyWithCount(remaining.getCount() - transfer);
            // Do not queue another batch against the same unchanged endpoint in this call. The caller can
            // retry the remainder after the endpoint's real capacity changes.
            if (transfer < amount) {
                return remaining;
            }
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    public IItemHandler getCachedExternalItemHandler(Direction side) {

        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || connections[side.ordinal()] == IDuct.ConnectionType.DISABLED) {
            return null;
        }
        BlockCapabilityCache<IItemHandler, Direction> cache = capCaches[side.ordinal()];
        if (cache == null) {
            BlockPos target = getBlockPos().relative(side);
            cache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, serverLevel, target,
                    side.getOpposite(), () -> !isRemoved(), () -> { });
            capCaches[side.ordinal()] = cache;
        }
        return cache.getCapability();
    }

    public void syncTravelingItems() {

        if (!(level instanceof ServerLevel serverLevel) || pendingRemoved.isEmpty() && pendingUpdated.isEmpty() && pendingMoved.isEmpty()) {
            return;
        }
        List<TravelingItemsPayload.Removal> removed = new ArrayList<>();
        pendingRemoved.forEach((id, revision) -> removed.add(new TravelingItemsPayload.Removal(id, revision)));
        List<TravelingItemsPayload.Update> updated = new ArrayList<>();
        for (TravelingItem item : pendingUpdated.values()) {
            updated.add(new TravelingItemsPayload.Update(item.syncRevision(), item.save(new CompoundTag(), serverLevel.registryAccess())));
        }
        List<TravelingItemsPayload.Move> moved = new ArrayList<>();
        for (TravelingItem item : pendingMoved.values()) {
            moved.add(new TravelingItemsPayload.Move(item.id(), item.syncRevision(), item.routeIndex()));
        }
        int pageSize = TravelingItemsPayload.MAX_ITEMS_PER_PACKET;
        int pages = Math.max(Math.max((removed.size() + pageSize - 1) / pageSize, (updated.size() + pageSize - 1) / pageSize),
                (moved.size() + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; ++page) {
            int from = page * pageSize;
            List<TravelingItemsPayload.Removal> rp = from < removed.size() ? List.copyOf(removed.subList(from, Math.min(from + pageSize, removed.size()))) : List.of();
            List<TravelingItemsPayload.Update> up = from < updated.size() ? List.copyOf(updated.subList(from, Math.min(from + pageSize, updated.size()))) : List.of();
            List<TravelingItemsPayload.Move> mp = from < moved.size() ? List.copyOf(moved.subList(from, Math.min(from + pageSize, moved.size()))) : List.of();
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(getBlockPos()), new TravelingItemsPayload(getBlockPos(), rp, up, mp));
        }
        pendingRemoved.clear();
        pendingUpdated.clear();
        pendingMoved.clear();
    }

    public void dropItemContents() {

        if (level == null || level.isClientSide()) {
            return;
        }
        for (TravelingItem item : List.copyOf(travelingItems)) {
            ItemStack stack = item.stack();
            if (!stack.isEmpty()) {
                cofh.lib.util.Utils.dropDismantleStackIntoWorld(stack, level, getBlockPos());
            }
            removeTravelingItem(item);
        }
    }

    @Override
    public void onLoad() {

        super.onLoad();
        if (level == null) {
            return;
        }
        if (level.isClientSide()) {
            List<TravelingItem> snapshot = List.copyOf(travelingItems);
            travelingItems.clear(); travelingById.clear();
            ClientTravelingItemIndex.unregisterOwner(level, this);
            for (TravelingItem item : snapshot) ClientTravelingItemIndex.registerSnapshot(level, this, item);
            return;
        }
        if (repairedOnLoad) {
            repairedOnLoad = false;
            setChanged();
        }
        ItemGrid itemGrid = getGrid();
        if (itemGrid == null) {
            return;
        }
        drainLegacyStuffedItems();
        if (hasTravelingItems()) itemGrid.track(this);
        itemGrid.recountWaitingItems();
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.loadAdditional(tag, provider);
        repairedOnLoad = false;
        travelingItems.clear();
        travelingById.clear();
        pendingUpdated.clear(); pendingRemoved.clear();
        List<TravelingItem> parsed = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        for (Tag entry : tag.getList(TAG_TRAVELING_ITEMS, Tag.TAG_COMPOUND)) {
            TravelingItem item;
            try {
                TravelingItem.LoadResult result = TravelingItem.loadValidated((CompoundTag) entry, provider);
                item = result.item();
                repairedOnLoad |= result.repaired();
            } catch (RuntimeException ignored) {
                repairedOnLoad = true;
                continue;
            }
            if (item.stack().isEmpty()) {
                repairedOnLoad = true;
                continue;
            }
            if (!seenIds.add(item.id())) {
                repairedOnLoad = true;
                continue;
            }
            parsed.add(item);
        }
        if (level != null && level.isClientSide()) {
            ClientTravelingItemIndex.unregisterOwner(level, this);
            for (TravelingItem item : parsed) {
                ClientTravelingItemIndex.registerSnapshot(level, this, item);
            }
        } else {
            for (TravelingItem item : parsed) {
                travelingItems.add(item);
                travelingById.put(item.id(), item);
            }
        }
    }

    public void removeClientTravelingItemLocal(UUID id) {
        if (level == null || !level.isClientSide()) return;
        TravelingItem item = travelingById.remove(id);
        if (item != null) travelingItems.remove(item);
    }

    public void addClientTravelingItemLocal(TravelingItem item) {
        if (level == null || !level.isClientSide() || item == null) return;
        TravelingItem old = travelingById.put(item.id(), item);
        if (old != null && old != item) travelingItems.remove(old);
        if (!travelingItems.contains(item)) travelingItems.add(item);
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && level.isClientSide()) ClientTravelingItemIndex.unregisterOwner(level, this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) ClientTravelingItemIndex.unregisterOwner(level, this);
        super.setRemoved();
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
            if (getAttachment(side) instanceof ItemFilterAttachment filter) {
                if (!(handler instanceof FilterItemHandler filterHandler) || filterHandler.filter != filter) {
                    handler = new FilterItemHandler(filter);
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

    private static class FilterItemHandler implements IItemHandler {

        private final ItemFilterAttachment filter;

        private FilterItemHandler(ItemFilterAttachment filter) {

            this.filter = filter;
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

            return slot == 0 ? filter.insertFromExternal(stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return slot == 0 ? ItemFilterAttachment.TRANSFER : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {

            return slot == 0;
        }

    }

}
