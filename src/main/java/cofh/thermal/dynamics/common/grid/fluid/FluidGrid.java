package cofh.thermal.dynamics.common.grid.fluid;

import cofh.core.util.helpers.FluidHelper;
import cofh.lib.util.TimeTracker;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.grid.Grid;
import com.google.common.graph.EndpointPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cofh.lib.util.Constants.BUCKET_VOLUME;
import static cofh.thermal.dynamics.init.registries.TDynGrids.FLUID_GRID;

/**
 * @author King Lemming
 */
public class FluidGrid extends Grid<FluidGrid, FluidGridNode> implements IFluidHandler {

    protected static final int DUCT_CAPACITY = 3000;

    protected static final String TAG_STORAGE = "Storage";

    protected final FluidGridStorage storage = new FluidGridStorage(0);

    protected FluidStack renderFluid = FluidStack.EMPTY;
    protected FluidStack prevRenderFluid = FluidStack.EMPTY;
    protected TimeTracker timeTracker = new TimeTracker();
    protected boolean wasFilled;
    protected boolean needsUpdate;

    protected FluidGridNode[] distArray = new FluidGridNode[0];
    protected int distIndex = 0;

    protected FluidGridNode[] nodeList = new FluidGridNode[0];
    protected int nodeTracker = 0;
    protected boolean isSendingFluid = false;

    public FluidGrid(UUID id, Level world) {

        super(FLUID_GRID.get(), id, world);
    }

    @Override
    public FluidGridNode newNode() {

        return new FluidGridNode(this);
    }

    @Override
    public void tick() {

        if (distArray.length != getNodes().size()) {
            distArray = getNodes().values().toArray(new FluidGridNode[0]);
        }
        int curIndex = distIndex;

        if (distIndex >= distArray.length) {
            distIndex = 0;
        }
        for (int i = distIndex; i < distArray.length; ++i) {
            rrPreNodeTick(i);
        }
        for (int i = 0; i < distIndex; ++i) {
            rrPreNodeTick(i);
        }
        renderUpdate();

        for (int i = distIndex; i < distArray.length; ++i) {
            if (rrNodeTick(curIndex, i)) {
                return;
            }
        }
        for (int i = 0; i < distIndex; ++i) {
            if (rrNodeTick(curIndex, i)) {
                return;
            }
        }
        ++distIndex;
    }

    private void rrPreNodeTick(int i) {

        if (distArray[i].isLoaded()) {
            distArray[i].attachmentTick();
        }
    }

    private boolean rrNodeTick(int curIndex, int i) {

        if (!distArray[i].isLoaded()) {
            return false;
        }
        distArray[i].distributionTick();
        if (getFluid().isEmpty()) {
            distIndex = i + 1;
            if (curIndex == distIndex) {
                --distIndex;
            }
            return true;
        }
        return false;
    }

    private void renderUpdate() {

        prevRenderFluid = renderFluid;
        renderFluid = getFluid().copyWithAmount(BUCKET_VOLUME);

        if (!FluidHelper.fluidsEqual(prevRenderFluid, renderFluid) || wasFilled && timeTracker.hasDelayPassed(world, 40) || needsUpdate) {
            if (!wasFilled && renderFluid.isEmpty()) {
                timeTracker.markTime(world);
                wasFilled = true;
                return;
            }
            updateHosts();
            wasFilled = false;
            needsUpdate = false;
        }
    }

    @Override
    public void onModified() {

        distArray = new FluidGridNode[0];
        nodeList = new FluidGridNode[0];
        recalculateCapacity();
        super.onModified();
    }

    @Override
    public boolean canMerge(FluidGrid from) {

        FluidStack fluid = getFluid();
        FluidStack fromFluid = from.getFluid();
        return fluid.isEmpty() || fromFluid.isEmpty() || FluidStack.isSameFluidSameComponents(fluid, fromFluid);
    }

    @Override
    public void onMerge(FluidGrid from) {

        long fluidAmount = (long) this.getFluidAmount() + from.getFluidAmount();
        recalculateCapacity();
        if (storage.getFluid().isEmpty()) {
            storage.setFluid(from.getFluid().copyWithAmount(saturatingInt(fluidAmount)));
        } else {
            storage.setFluid(storage.getFluid().copyWithAmount(saturatingInt(fluidAmount)));
        }

        needsUpdate = true;

        refreshCapabilities();
        from.refreshCapabilities();
    }

    @Override
    public void onSplit(List<FluidGrid> others) {

        int totalDucts = 0;
        for (FluidGrid grid : others) {
            totalDucts = saturatingInt((long) totalDucts + grid.getDuctCount());
            grid.recalculateCapacity();
            if (!this.renderFluid.isEmpty()) {
                grid.needsUpdate = true;
            }
            grid.refreshCapabilities();
        }
        this.refreshCapabilities();
        if (getFluid().isEmpty() || totalDucts == 0) {
            return;
        }
        int fluidAmount = getFluid().getAmount();
        int fluidPerDuct = fluidAmount / totalDucts;
        int remainder = fluidAmount % totalDucts;

        for (FluidGrid grid : others) {
            grid.setFluid(getFluid().copyWithAmount(saturatingInt((long) fluidPerDuct * grid.getDuctCount())));
        }
        for (FluidGrid grid : others) {
            int available = grid.getCapacity() - grid.getFluidAmount();
            if (available <= 0) {
                continue;
            }
            int toAdd = Math.min(available, remainder);
            grid.setFluid(getFluid().copyWithAmount(grid.getFluidAmount() + toAdd));
            remainder -= toAdd;
            if (remainder == 0) {
                break;
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        CompoundTag tag = super.serializeNBT(provider);
        // Fluid data must be nested: FluidStack serialization uses the "id" key, which would otherwise
        // overwrite the grid UUID that GridContainer stores under "id".
        tag.put(TAG_STORAGE, storage.serializeNBT(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

        super.deserializeNBT(provider, nbt);
        recalculateCapacity();
        if (nbt.contains(TAG_STORAGE, CompoundTag.TAG_COMPOUND)) {
            storage.deserializeNBT(provider, nbt.getCompound(TAG_STORAGE));
        } else {
            // Legacy: older saves merged the fluid data into the grid tag itself. It is only parsed
            // when "id" actually holds a fluid id (STRING); the grid UUID (INT_ARRAY) is not fluid data.
            storage.read(provider, nbt);
        }
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        if (GridHelper.getGridHost(tile) != null) {
            return false; // We cannot externally connect to other grids.
        }
        if (dir != null) {
            return FluidHelper.hasFluidHandlerCap(tile, dir);
        }
        return false;
    }

    @Nullable
    @SuppressWarnings ("unchecked")
    public <T, C> T getCapability(BlockCapability<T, C> capability) {

        if (capability == Capabilities.FluidHandler.BLOCK) {
            return (T) this;
        }
        return null;
    }

    @Override
    public void refreshCapabilities() {

        for (var node : getNodes().entrySet()) {
            if (!node.getValue().isLoaded()) {
                continue;
            }
            if (getLevel().getBlockEntity(node.getKey()) instanceof DuctBlockEntity<?, ?> duct) {
                duct.invalidateAttachments();
            }
            getLevel().invalidateCapabilities(node.getKey());
        }
    }

    //@formatter:off
    public int getCapacity() { return storage.getCapacity(); }
    public FluidStack getFluid() { return storage.getFluid(); }
    public FluidStack getRenderFluid() { return renderFluid; }
    public int getFluidAmount() { return storage.getFluid().getAmount(); }
    public void setCapacity(int capacity) { storage.setCapacity(capacity); }
    public void setFluid(FluidStack fluid) { storage.setFluid(fluid); }

    @Override public int getTanks() { return storage.getTanks(); }
    @Override public FluidStack getFluidInTank(int tank) { return storage.getFluidInTank(tank); }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) { return storage.drain(resource, action); }
    @Override public FluidStack drain(int maxDrain, FluidAction action) { return storage.drain(maxDrain, action); }
    @Override public int getTankCapacity(int tank) { return storage.getTankCapacity(tank); }
    @Override public boolean isFluidValid(int tank, @Nonnull FluidStack stack) { return storage.isFluidValid(tank, stack); }
    //@formatter:on

    @Override
    public int fill(FluidStack resource, FluidAction action) {

        if (resource.isEmpty() || isSendingFluid || (!storage.getFluid().isEmpty() && !FluidStack.isSameFluidSameComponents(storage.getFluid(), resource))) {
            return 0;
        }
        int added = storage.fill(resource, action);
        int overflow = resource.getAmount() - added;
        if (overflow <= 0) {
            return added;
        }
        FluidGridNode[] list = nodeList;
        if (list.length != getNodes().size()) {
            list = getNodes().values().toArray(new FluidGridNode[0]);
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.length == 0) {
            return added;
        }
        int tempTracker = nodeTracker;
        int toSend = overflow;
        Set<BlockPos> visitedTargets = new HashSet<>();
        isSendingFluid = true;
        try {
            for (int i = nodeTracker; i < list.length && toSend > 0; ++i) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                toSend -= list[i].transmitFluid(resource.copyWithAmount(toSend), action.simulate(), visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            for (int i = 0; i < list.length && i < nodeTracker && toSend > 0; ++i) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                toSend -= list[i].transmitFluid(resource.copyWithAmount(toSend), action.simulate(), visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            if (toSend > 0) {
                ++nodeTracker;
            }
            if (nodeTracker >= list.length) {
                nodeTracker = 0;
            }
            if (action.simulate()) {
                nodeTracker = tempTracker;
            }
        } finally {
            isSendingFluid = false;
        }
        return added + (overflow - toSend);
    }

    private void recalculateCapacity() {

        storage.setCapacity(saturatingInt((long) getDuctCount() * DUCT_CAPACITY));
    }

    private int getDuctCount() {

        long count = nodeGraph.nodes().size();
        for (EndpointPair<FluidGridNode> edge : nodeGraph.edges()) {
            count += GridHelper.numBetween(edge.nodeU().getPos(), edge.nodeV().getPos());
        }
        return saturatingInt(count);
    }

    private static int saturatingInt(long value) {

        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }
}
