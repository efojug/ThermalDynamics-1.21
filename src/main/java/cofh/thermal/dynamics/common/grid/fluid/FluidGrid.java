package cofh.thermal.dynamics.common.grid.fluid;

import cofh.core.util.helpers.FluidHelper;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.BufferedContentGrid;
import cofh.thermal.dynamics.common.grid.GridRenderState;
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

        drain((int) Math.min(amount, Integer.MAX_VALUE), FluidAction.EXECUTE);
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

    //@formatter:off
    public int getCapacity() { return storage.getCapacity(); }
    public FluidStack getFluid() { return storage.getFluid(); }
    public FluidStack getHeldFluid() { return heldContent(); }
    public FluidStack getRenderFluid() { return renderState.renderStack(); }
    public int getFluidAmount() { return saturatingInt(heldAmountLong()); }
    public void setCapacity(int capacity) { storage.setCapacity(capacity); }
    public void setFluid(FluidStack fluid) { storage.setFluid(fluid); }
    public void drainStorage(int amount) { if (amount > 0) storage.drain(amount, FluidAction.EXECUTE); }
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
        if (overflowBuffer.isEmpty()) return storage.drain(maxDrain, action);
        FluidStack pending = overflowBuffer.peek(maxDrain);
        int pendingAmount = pending.getAmount();
        if (action.execute()) overflowBuffer.drain(pendingAmount);
        FluidStack rest = maxDrain > pendingAmount ? storage.drain(maxDrain - pendingAmount, action) : FluidStack.EMPTY;
        return rest.isEmpty() ? pending : pending.copyWithAmount(pendingAmount + rest.getAmount());
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
            return added;
        }
        return added + (int) distributeOverflow(resource, overflow, action.execute());
    }

}
