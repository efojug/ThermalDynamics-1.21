package cofh.thermal.dynamics.common.grid.fluid;

import cofh.core.util.helpers.FluidHelper;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.BufferedContentGrid;
import cofh.thermal.dynamics.common.grid.GridRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

import static cofh.lib.util.Constants.BUCKET_VOLUME;
import static cofh.thermal.dynamics.init.registries.TDynGrids.FLUID_GRID;

/**
 * @author King Lemming
 */
public class FluidGrid extends BufferedContentGrid<FluidGrid, FluidGridNode, FluidStack> implements IFluidHandler {

    protected static final int DUCT_CAPACITY = 3000;
    private static final int MIN_GAS_RENDER_ALPHA = Math.round(0.2F * 255.0F);

    protected final FluidGridStorage storage = new FluidGridStorage(0);

    public FluidGrid(UUID id, Level world) {

        super(FLUID_GRID.get(), id, world, FluidOverflowOps.INSTANCE, BUCKET_VOLUME, "Fluid");
    }

    @Override
    public FluidGridNode newNode() {

        return new FluidGridNode(this);
    }

    // region CONTENT HOOKS
    @Override
    protected FluidStack storedStack() {

        return storage.getFluid();
    }

    @Override
    protected void setStored(FluidStack type, long amount) {

        storage.setFluid(type.isEmpty() || amount <= 0 ? FluidStack.EMPTY : type.copyWithAmount(saturatingInt(amount)));
    }

    @Override
    protected long storageCapacity() {

        return storage.getCapacity();
    }

    @Override
    protected void setStorageCapacity(long capacity) {

        storage.setCapacity(saturatingInt(capacity));
    }

    @Override
    protected long ductCapacity() {

        return DUCT_CAPACITY;
    }

    @Override
    protected int renderAlpha(FluidStack held) {

        if (!isLighterThanAirGas(held) || storage.getCapacity() <= 0) {
            return 0xFF;
        }
        float scale = Math.min(1.0F, getFluidAmount() / (float) storage.getCapacity());

        // Matches Mekanism's mechanical pipe opacity for lighter-than-air gaseous fluids.
        // Quantized so continuous flow does not force a host update packet every tick.
        return Math.max(MIN_GAS_RENDER_ALPHA, GridRenderState.quantizeAlpha(Math.round(scale * 255.0F)));
    }

    @Override
    protected void drainHeld(long amount) {

        isDrainingHeld = true;
        try {
            drain((int) Math.min(amount, Integer.MAX_VALUE), FluidAction.EXECUTE);
        } finally {
            isDrainingHeld = false;
        }
    }

    @Override
    protected CompoundTag saveStorage(HolderLookup.Provider provider) {

        return storage.serializeNBT(provider);
    }

    @Override
    protected void loadStorage(HolderLookup.Provider provider, CompoundTag tag) {

        storage.deserializeNBT(provider, tag);
    }

    @Override
    protected void readLegacyStorage(HolderLookup.Provider provider, CompoundTag nbt) {

        // Legacy: older saves merged the fluid data into the grid tag itself. It is only parsed
        // when "id" actually holds a fluid id (STRING); the grid UUID (INT_ARRAY) is not fluid data.
        storage.read(provider, nbt);
    }
    // endregion

    public static boolean isLighterThanAirGas(FluidStack fluid) {

        return !fluid.isEmpty() && fluid.is(Tags.Fluids.GASEOUS) && fluid.getFluidType().getDensity(fluid) <= 0;
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

    @Nullable
    @Override
    @SuppressWarnings ("unchecked")
    public <T, C> T getExternalCapability(BlockCapability<T, C> capability, BlockPos externalPos) {

        if (capability == Capabilities.FluidHandler.BLOCK) {
            return (T) new ExternalFluidHandler(externalPos);
        }
        return null;
    }

    /**
     * The handler handed to a specific neighboring block. Inserts are gated on routability -
     * content the grid could never deliver anywhere is rejected outright, so push logic that
     * probes with SIMULATE (AE2 pattern providers, ejectors) picks a real destination instead of
     * stranding content in the duct. Accepted inserts mark the pusher as a content origin, so
     * distribution never sends the content back where it came from.
     */
    private final class ExternalFluidHandler implements IFluidHandler {

        private final BlockPos externalPos;

        private ExternalFluidHandler(BlockPos externalPos) {

            this.externalPos = externalPos.immutable();
        }

        //@formatter:off
        @Override public int getTanks() { return FluidGrid.this.getTanks(); }
        @Override public @Nonnull FluidStack getFluidInTank(int tank) { return FluidGrid.this.getFluidInTank(tank); }
        @Override public int getTankCapacity(int tank) { return FluidGrid.this.getTankCapacity(tank); }
        @Override public boolean isFluidValid(int tank, @Nonnull FluidStack stack) { return FluidGrid.this.isFluidValid(tank, stack); }
        @Override public @Nonnull FluidStack drain(FluidStack resource, FluidAction action) { return FluidGrid.this.drain(resource, action); }
        @Override public @Nonnull FluidStack drain(int maxDrain, FluidAction action) { return FluidGrid.this.drain(maxDrain, action); }
        //@formatter:on

        @Override
        public int fill(FluidStack resource, FluidAction action) {

            if (resource.isEmpty()) {
                return 0;
            }
            if (simulateRoutable(resource, resource.getAmount(), externalPos) <= 0) {
                return 0;
            }
            if (action.simulate()) {
                return FluidGrid.this.fill(resource, action);
            }
            boolean added = markContentOrigin(externalPos);
            int filled = FluidGrid.this.fill(resource, action);
            if (added && filled <= 0) {
                unmarkContentOrigin(externalPos);
            }
            return filled;
        }

    }

    //@formatter:off
    public int getCapacity() { return storage.getCapacity(); }
    public FluidStack getFluid() { return storage.getFluid(); }
    public FluidStack getHeldFluid() { return heldContent(); }
    public FluidStack getRenderFluid() { return renderState.renderStack(); }
    public int getFluidAmount() { return saturatingInt(heldAmountLong()); }
    public void setCapacity(int capacity) { storage.setCapacity(capacity); }
    public void setFluid(FluidStack fluid) {
        int before = storage.getFluid().getAmount();
        storage.setFluid(fluid);
        int after = storage.getFluid().getAmount();
        if (after >= before) auditNoteIn(after - before); else auditNoteOut(before - after);
    }
    public void drainStorage(int amount) { if (amount > 0) auditNoteOut(storage.drain(amount, FluidAction.EXECUTE).getAmount()); }
    public int replayOverflow(FluidStack offered) {
        isReplayingOverflow = true;
        try { return fill(offered, FluidAction.EXECUTE); }
        finally { isReplayingOverflow = false; }
    }

    @Override public int getTanks() { return storage.getTanks(); }
    @Override public FluidStack getFluidInTank(int tank) { return storage.getFluidInTank(tank); }
    @Override public FluidStack drain(FluidStack resource, FluidAction action) {
        FluidStack held = getHeldFluid();
        return resource.isEmpty() || held.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, held) ? FluidStack.EMPTY : drain(resource.getAmount(), action);
    }
    @Override public FluidStack drain(int maxDrain, FluidAction action) {
        FluidStack result;
        if (overflowBuffer.isEmpty()) {
            result = storage.drain(maxDrain, action);
        } else {
            FluidStack pending = overflowBuffer.peek(maxDrain);
            int pendingAmount = pending.getAmount();
            if (action.execute()) overflowBuffer.drain(pendingAmount);
            FluidStack rest = maxDrain > pendingAmount ? storage.drain(maxDrain - pendingAmount, action) : FluidStack.EMPTY;
            result = rest.isEmpty() ? pending : pending.copyWithAmount(pendingAmount + rest.getAmount());
        }
        if (action.execute() && !isDrainingHeld) auditNoteOut(result.getAmount());
        return result;
    }
    @Override public int getTankCapacity(int tank) { return storage.getTankCapacity(tank); }
    @Override public boolean isFluidValid(int tank, @Nonnull FluidStack stack) { return storage.isFluidValid(tank, stack); }
    //@formatter:on

    @Override
    public int fill(FluidStack resource, FluidAction action) {

        FluidStack held = getHeldFluid();
        if (resource.isEmpty() || isSendingContent || (!held.isEmpty() && !FluidStack.isSameFluidSameComponents(held, resource))) {
            return 0;
        }
        if (!isReplayingOverflow) {
            long headroom = overflowHeadroom();
            if (headroom <= 0) return 0;
            if (resource.getAmount() > headroom) resource = resource.copyWithAmount((int) Math.min(headroom, Integer.MAX_VALUE));
        }
        int added = storage.fill(resource, action);
        int overflow = resource.getAmount() - added;
        if (overflow <= 0) {
            if (action.execute() && !isReplayingOverflow) {
                auditNoteIn(added);
            }
            return added;
        }
        long sent = distributeOverflow(resource, overflow, action.execute());
        if (action.execute() && !isReplayingOverflow) {
            auditNoteIn(added + sent);
        }
        return added + (int) sent;
    }

}
