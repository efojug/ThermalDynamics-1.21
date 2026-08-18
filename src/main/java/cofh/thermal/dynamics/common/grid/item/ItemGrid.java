package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.attachment.ItemFilterAttachment;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.Grid;
import com.google.common.graph.EndpointPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;

public class ItemGrid extends Grid<ItemGrid, ItemGridNode> {

    private ItemGridNode[] attachmentNodes = new ItemGridNode[0];
    private boolean attachmentNodesDirty = true;
    private final Set<ItemDuctBlockEntity> activeDucts = new LinkedHashSet<>();
    private final Set<ItemDuctBlockEntity> dirtyDucts = new LinkedHashSet<>();
    private final Map<BlockPos, Search> searchCache = new HashMap<>();
    /** In-flight capacity reservations indexed by external target position (loaded ducts only). */
    private final Map<BlockPos, List<TravelingItem>> reservations = new HashMap<>();
    private List<ItemDuctBlockEntity> activeSnapshot = List.of();
    private boolean activeDirty = true;
    private int waitingItems;

    public ItemGrid(UUID id, Level world) {

        super(ITEM_GRID.get(), id, world);
    }

    @Override
    public ItemGridNode newNode() {

        return new ItemGridNode(this);
    }

    @Override
    public void tick() {

        if (attachmentNodesDirty) {
            rebuildAttachmentNodes();
        }
        for (ItemGridNode node : attachmentNodes) {
            if (node.isLoaded()) {
                node.attachmentTick();
            }
        }
        if (activeDirty) {
            activeSnapshot = List.copyOf(activeDucts);
            activeDirty = false;
        }
        for (ItemDuctBlockEntity duct : activeSnapshot) {
            if (duct.isRemoved() || duct.getGrid() != this || !duct.hasTravelingItems()) {
                if (activeDucts.remove(duct)) {
                    activeDirty = true;
                    // Unloaded/foreign ducts keep their items but stop reserving capacity here;
                    // re-tracking on load rebuilds their reservations.
                    for (TravelingItem item : duct.getTravelingItems()) {
                        removeReservation(item);
                    }
                }
                continue;
            }
            duct.serverTick();
        }
        for (ItemDuctBlockEntity duct : dirtyDucts) {
            if (!duct.isRemoved() && duct.getGrid() == this) {
                duct.syncTravelingItems();
            }
        }
        dirtyDucts.clear();
    }

    @Override
    public void onModified() {

        attachmentNodesDirty = true;
        searchCache.clear();
        super.onModified();
    }

    @Override
    public void onAttachmentsChanged() {

        attachmentNodesDirty = true;
        searchCache.clear();
    }

    @Override
    public void onMerge(ItemGrid from) {

        scanTrackedDucts();
        recountWaitingItems();
    }

    @Override
    public void onSplit(List<ItemGrid> others) {

        for (ItemGrid grid : others) {
            grid.scanTrackedDucts();
            grid.recountWaitingItems();
        }
    }

    public boolean hasWaitingItems() {

        return waitingItems > 0;
    }

    public void setItemWaiting(boolean waiting, boolean wasWaiting) {

        waitingItems = Math.max(0, waitingItems + (waiting ? 1 : 0) - (wasWaiting ? 1 : 0));
    }

    public void recountWaitingItems() {

        int count = 0;
        for (ItemDuctBlockEntity duct : activeDucts) {
            if (duct.isRemoved() || duct.getGrid() != this) continue;
            for (TravelingItem item : duct.getTravelingItems()) {
                if (item.waiting()) ++count;
            }
        }
        waitingItems = count;
        rebuildReservations();
    }

    // region RESERVATION INDEX
    public void addReservation(TravelingItem item) {

        BlockPos target = item.updateReservedTarget();
        if (target != null) {
            reservations.computeIfAbsent(target, k -> new ArrayList<>(4)).add(item);
        }
    }

    public void removeReservation(TravelingItem item) {

        BlockPos target = item.reservedTarget();
        if (target == null) {
            return;
        }
        List<TravelingItem> list = reservations.get(target);
        if (list != null && list.remove(item) && list.isEmpty()) {
            reservations.remove(target);
        }
        item.clearReservedTarget();
    }

    public void updateReservation(TravelingItem item) {

        removeReservation(item);
        addReservation(item);
    }

    private void rebuildReservations() {

        for (List<TravelingItem> list : reservations.values()) {
            for (TravelingItem item : list) {
                item.clearReservedTarget();
            }
        }
        reservations.clear();
        for (ItemDuctBlockEntity duct : activeDucts) {
            if (duct.isRemoved() || duct.getGrid() != this) continue;
            for (TravelingItem item : duct.getTravelingItems()) {
                addReservation(item);
            }
        }
    }
    // endregion

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        if (GridHelper.getGridHost(tile) != null || dir == null || tile.getLevel() == null) {
            return false;
        }
        IItemHandler handler = tile.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(),
                tile.getBlockState(), tile, dir);
        return handler != null && handler.getSlots() > 0;
    }

    public void track(ItemDuctBlockEntity duct) {

        if (duct.getGrid() == this && duct.hasTravelingItems()) {
            if (activeDucts.add(duct)) {
                activeDirty = true;
            }
        }
    }

    public void markDirty(ItemDuctBlockEntity duct) {

        track(duct);
        dirtyDucts.add(duct);
    }

    public ItemRoute findRoute(BlockPos start, @Nullable Set<BlockPos> excludedTargets, net.minecraft.world.item.ItemStack stack,
            @Nullable BlockPos preferredDestination, @Nullable Direction preferredSide) {

        Search search = search(start);
        if (preferredDestination != null && preferredSide != null && search.parents.containsKey(preferredDestination) &&
                !containsTarget(excludedTargets, preferredDestination.relative(preferredSide)) &&
                canInsert(preferredDestination, preferredSide, stack, excludedTargets)) {
            return buildRoute(start, preferredDestination, preferredSide, search.parents);
        }
        for (BlockPos position : search.order) {
            ItemDuctBlockEntity duct = getItemDuct(position);
            if (duct == null) {
                continue;
            }
            for (Direction side : DIRECTIONS) {
                if (canInsert(position, side, stack, excludedTargets)) {
                    return buildRoute(start, position, side, search.parents);
                }
            }
        }
        return null;
    }

    public ItemRoute findRouteToDuct(BlockPos start, BlockPos destination, Direction destinationSide) {

        Search search = search(start);
        if (!search.parents.containsKey(destination)) {
            return null;
        }
        return buildRoute(start, destination, destinationSide, search.parents);
    }

    private Search search(BlockPos start) {

        Search cached = searchCache.get(start);
        if (cached != null) {
            return cached;
        }
        Map<BlockPos, Step> parents = new HashMap<>();
        List<BlockPos> order = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        parents.put(start, null);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst();
            order.add(position);
            ItemDuctBlockEntity duct = getItemDuct(position);
            if (duct == null) {
                continue;
            }
            for (Direction direction : DIRECTIONS) {
                BlockPos adjacent = position.relative(direction);
                if (parents.containsKey(adjacent) || duct.getConnectedDuct(direction) == null) {
                    continue;
                }
                parents.put(adjacent, new Step(position, direction));
                queue.addLast(adjacent);
            }
        }
        Search result = new Search(Collections.unmodifiableMap(new HashMap<>(parents)), List.copyOf(order));
        searchCache.put(start.immutable(), result);
        return result;
    }

    private ItemRoute buildRoute(BlockPos start, BlockPos destination, Direction side, Map<BlockPos, Step> parents) {

        ArrayList<Direction> steps = new ArrayList<>();
        BlockPos cursor = destination;
        while (!cursor.equals(start)) {
            Step step = parents.get(cursor);
            if (step == null) {
                return null;
            }
            steps.add(step.direction);
            cursor = step.parent;
        }
        java.util.Collections.reverse(steps);
        return new ItemRoute(steps, destination, side);
    }

    /**
     * Returns the amount of {@code stack} that the route's endpoint can accept right now.
     * The simulation is intentionally performed against the live handler: endpoint capacity can change
     * independently of the topology/BFS cache.
     */
    public int getInsertCapacity(@Nullable ItemRoute route, ItemStack stack) {

        return getInsertCapacity(route, stack, 0);
    }

    /**
     * Returns the endpoint capacity after also accounting for reservations planned by the current
     * simulation call but not yet represented by a real {@link TravelingItem}.
     */
    public int getInsertCapacity(@Nullable ItemRoute route, ItemStack stack, int additionalReserved) {

        if (route == null || stack.isEmpty()) {
            return 0;
        }
        ItemDuctBlockEntity duct = getItemDuct(route.destination());
        return duct == null ? 0 : getInsertCapacity(duct, route.destinationSide(), stack, Math.max(0, additionalReserved));
    }

    private int getInsertCapacity(ItemDuctBlockEntity duct, Direction side, ItemStack stack, int additionalReserved) {

        if (duct.getConnectionType(side) == IDuct.ConnectionType.DISABLED ||
                duct.getAttachment(side) instanceof ItemFilterAttachment) {
            return 0;
        }
        BlockPos target = duct.getBlockPos().relative(side);
        if (GridHelper.getGridHost(getLevel(), target) != null) {
            return 0;
        }
        IItemHandler handler = duct.getCachedExternalItemHandler(side);
        if (handler == null || handler.getSlots() <= 0) {
            return 0;
        }
        VirtualItemHandler virtual = new VirtualItemHandler(handler);
        List<TravelingItem> reserved = reservations.get(target);
        if (reserved != null) {
            for (TravelingItem item : reserved) {
                if (!item.reservesTarget(target)) {
                    continue; // stale index entry; harmless, cleaned up on the next rebuild
                }
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(virtual, item.stack(), false);
                if (!remainder.isEmpty()) {
                    // An already travelling item could no longer fit after the target changed.
                    // Do not compound the problem by accepting another item for this endpoint.
                    return 0;
                }
            }
        }
        if (additionalReserved > 0) {
            ItemStack reservedStack = stack.copyWithCount(additionalReserved);
            if (!ItemHandlerHelper.insertItemStacked(virtual, reservedStack, false).isEmpty()) {
                return 0;
            }
        }
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(virtual, stack, true);
        return stack.getCount() - remainder.getCount();
    }

    private boolean canInsert(BlockPos position, Direction side, ItemStack stack, @Nullable Set<BlockPos> excludedTargets) {

        ItemDuctBlockEntity duct = getItemDuct(position);
        if (duct == null || duct.getConnectionType(side) == IDuct.ConnectionType.DISABLED ||
                duct.getAttachment(side) instanceof ItemFilterAttachment) {
            return false;
        }
        BlockPos target = position.relative(side);
        if (containsTarget(excludedTargets, target)) {
            return false;
        }
        if (GridHelper.getGridHost(getLevel(), target) != null) {
            return false;
        }
        return getInsertCapacity(duct, side, stack, 0) > 0;
    }

    private static boolean containsTarget(@Nullable Set<BlockPos> targets, BlockPos pos) {

        return targets != null && targets.contains(pos);
    }

    private ItemDuctBlockEntity getItemDuct(BlockPos position) {

        return getLevel().isLoaded(position) && getLevel().getBlockEntity(position) instanceof ItemDuctBlockEntity duct && duct.getGrid() == this ? duct : null;
    }

    private void rebuildAttachmentNodes() {

        List<ItemGridNode> nodes = new ArrayList<>();
        for (ItemGridNode node : getNodes().values()) {
            if (node.needsAttachmentTick()) {
                nodes.add(node);
            }
        }
        attachmentNodes = nodes.toArray(new ItemGridNode[0]);
        attachmentNodesDirty = false;
    }

    private void scanTrackedDucts() {

        for (BlockPos position : getDuctPositions()) {
            ItemDuctBlockEntity duct = getItemDuct(position);
            if (duct != null && duct.hasTravelingItems()) {
                activeDucts.add(duct);
            }
        }
    }

    private Set<BlockPos> getDuctPositions() {

        Set<BlockPos> positions = new HashSet<>(getNodes().keySet());
        for (EndpointPair<ItemGridNode> edge : nodeGraph.edges()) {
            BlockPos from = edge.nodeU().getPos();
            BlockPos to = edge.nodeV().getPos();
            int dx = Integer.compare(to.getX(), from.getX());
            int dy = Integer.compare(to.getY(), from.getY());
            int dz = Integer.compare(to.getZ(), from.getZ());
            int steps = Math.abs(to.getX() - from.getX()) + Math.abs(to.getY() - from.getY()) + Math.abs(to.getZ() - from.getZ());
            for (int i = 0; i <= steps; ++i) {
                positions.add(from.offset(dx * i, dy * i, dz * i));
            }
        }
        return positions;
    }

    private record Step(BlockPos parent, Direction direction) {

    }

    private record Search(Map<BlockPos, Step> parents, List<BlockPos> order) {

    }

    /**
     * A slot-level simulation of an external item handler. The real handler is never mutated; this
     * lets reservations for different item types occupy distinct empty slots during routing checks.
     * Slots are copied lazily on first write access; reads pass through to the delegate.
     */
    private static final class VirtualItemHandler implements IItemHandler {

        private final IItemHandler delegate;
        private final ItemStack[] stacks;

        private VirtualItemHandler(IItemHandler delegate) {

            this.delegate = delegate;
            this.stacks = new ItemStack[delegate.getSlots()];
        }

        private ItemStack localSlot(int slot) {

            ItemStack local = stacks[slot];
            if (local == null) {
                local = delegate.getStackInSlot(slot).copy();
                stacks[slot] = local;
            }
            return local;
        }

        @Override
        public int getSlots() {

            return stacks.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {

            if (slot < 0 || slot >= stacks.length) {
                return ItemStack.EMPTY;
            }
            ItemStack local = stacks[slot];
            // Per the IItemHandler contract callers must not mutate the returned stack, so untouched
            // slots pass the delegate's view through without copying.
            return local != null ? local : delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {

            if (stack.isEmpty() || slot < 0 || slot >= stacks.length || !delegate.isItemValid(slot, stack)) {
                return stack;
            }
            ItemStack existing = localSlot(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) {
                return stack;
            }
            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            int available = Math.max(0, limit - existing.getCount());
            int accepted = Math.min(available, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }
            if (!simulate) {
                if (existing.isEmpty()) {
                    stacks[slot] = stack.copyWithCount(accepted);
                } else {
                    existing.grow(accepted);
                }
            }
            return stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {

            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {

            return slot >= 0 && slot < stacks.length ? delegate.getSlotLimit(slot) : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {

            return slot >= 0 && slot < stacks.length && delegate.isItemValid(slot, stack);
        }
    }

}
