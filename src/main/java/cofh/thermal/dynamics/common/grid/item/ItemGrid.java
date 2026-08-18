package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.attachment.ItemServoAttachment;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.Grid;
import com.google.common.graph.EndpointPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
        for (ItemDuctBlockEntity duct : List.copyOf(activeDucts)) {
            if (duct.isRemoved() || duct.getGrid() != this || !duct.hasTravelingItems()) {
                activeDucts.remove(duct);
                continue;
            }
            duct.serverTick();
        }
        for (ItemDuctBlockEntity duct : dirtyDucts) {
            duct.syncTravelingItems();
        }
        dirtyDucts.clear();
    }

    @Override
    public void onModified() {

        attachmentNodesDirty = true;
        super.onModified();
    }

    @Override
    public void onAttachmentsChanged() {

        attachmentNodesDirty = true;
    }

    @Override
    public void onMerge(ItemGrid from) {

        scanTrackedDucts();
    }

    @Override
    public void onSplit(List<ItemGrid> others) {

        for (ItemGrid grid : others) {
            grid.scanTrackedDucts();
        }
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        if (GridHelper.getGridHost(tile) != null || dir == null || tile.getLevel() == null) {
            return false;
        }
        IItemHandler handler = tile.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(),
                tile.getBlockState(), tile, dir);
        return handler != null && handler.getSlots() > 0;
    }

    @Override
    public void refreshCapabilities() {

        for (var entry : getNodes().entrySet()) {
            if (!entry.getValue().isLoaded()) {
                continue;
            }
            if (getLevel().getBlockEntity(entry.getKey()) instanceof DuctBlockEntity<?, ?> duct) {
                duct.invalidateAttachments();
            }
            getLevel().invalidateCapabilities(entry.getKey());
        }
    }

    public void track(ItemDuctBlockEntity duct) {

        if (duct.getGrid() == this && duct.hasTravelingItems()) {
            activeDucts.add(duct);
        }
    }

    public void markDirty(ItemDuctBlockEntity duct) {

        track(duct);
        dirtyDucts.add(duct);
    }

    public ItemRoute findRoute(BlockPos start, @Nullable Direction excludedStartSide, net.minecraft.world.item.ItemStack stack,
            @Nullable BlockPos preferredDestination, @Nullable Direction preferredSide) {

        Search search = search(start);
        if (preferredDestination != null && preferredSide != null && search.parents.containsKey(preferredDestination) &&
                canInsert(preferredDestination, preferredSide, stack)) {
            return buildRoute(start, preferredDestination, preferredSide, search.parents);
        }
        for (BlockPos position : search.order) {
            ItemDuctBlockEntity duct = getItemDuct(position);
            if (duct == null) {
                continue;
            }
            for (Direction side : DIRECTIONS) {
                if (position.equals(start) && side == excludedStartSide) {
                    continue;
                }
                if (canInsert(position, side, stack)) {
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
        return new Search(parents, order);
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

    private boolean canInsert(BlockPos position, Direction side, net.minecraft.world.item.ItemStack stack) {

        ItemDuctBlockEntity duct = getItemDuct(position);
        if (duct == null || duct.getConnectionType(side) == IDuct.ConnectionType.DISABLED ||
                duct.getAttachment(side) instanceof ItemServoAttachment) {
            return false;
        }
        BlockPos target = position.relative(side);
        if (GridHelper.getGridHost(getLevel(), target) != null) {
            return false;
        }
        BlockEntity targetTile = getLevel().getBlockEntity(target);
        if (targetTile == null) {
            return false;
        }
        IItemHandler handler = getLevel().getCapability(Capabilities.ItemHandler.BLOCK, target,
                targetTile.getBlockState(), targetTile, side.getOpposite());
        return handler != null && handler.getSlots() > 0 &&
                ItemHandlerHelper.insertItemStacked(handler, stack, true).getCount() < stack.getCount();
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

}
