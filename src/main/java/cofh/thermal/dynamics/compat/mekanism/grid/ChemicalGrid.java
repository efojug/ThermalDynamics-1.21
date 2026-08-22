package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.ThermalDynamics;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.grid.BufferedContentGrid;
import cofh.thermal.dynamics.common.grid.GridRenderState;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_GRID;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;

/**
 * Chemical equivalent of the superconducting Fluiduct grid.
 */
public class ChemicalGrid extends BufferedContentGrid<ChemicalGrid, ChemicalGridNode, ChemicalStack> implements IChemicalHandler {

    private static final long DUCT_CAPACITY = 5_000L;
    private static final long RENDER_AMOUNT = 1L;
    private static final int MIN_RENDER_ALPHA = Math.round(0.2F * 255.0F);

    private final ChemicalGridStorage storage = new ChemicalGridStorage(0);

    public ChemicalGrid(UUID id, Level world) {

        super(CHEMICAL_GRID.get(), id, world, ChemicalOverflowOps.INSTANCE, RENDER_AMOUNT, "Chemical");
    }

    @Override
    public ChemicalGridNode newNode() {

        return new ChemicalGridNode(this);
    }

    // region CONTENT HOOKS
    @Override
    protected ChemicalStack storedStack() {

        return storage.getChemical();
    }

    @Override
    protected void setStored(ChemicalStack type, long amount) {

        storage.setChemical(type.isEmpty() || amount <= 0 ? ChemicalStack.EMPTY : type.copyWithAmount(amount));
    }

    @Override
    protected long storageCapacity() {

        return storage.getCapacity();
    }

    @Override
    protected void setStorageCapacity(long capacity) {

        storage.setCapacity(capacity);
    }

    @Override
    protected long ductCapacity() {

        return DUCT_CAPACITY;
    }

    @Override
    protected int renderAlpha(ChemicalStack held) {

        if (held.isEmpty() || storage.getCapacity() <= 0) {
            return 0xFF;
        }
        float scale = Math.min(1.0F, held.getAmount() / (float) storage.getCapacity());

        // Matches Mekanism's pressurized tube opacity while avoiding imperceptible state updates.
        // Quantized so continuous flow does not force a host update packet every tick.
        return Math.max(MIN_RENDER_ALPHA, GridRenderState.quantizeAlpha(Math.round(scale * 255.0F)));
    }

    @Override
    protected void drainHeld(long amount) {

        isDrainingHeld = true;
        try {
            extractChemical(0, amount, Action.EXECUTE);
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
    // endregion

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        return GridHelper.getGridHost(tile) == null && dir != null && tile.getLevel() != null &&
                tile.getLevel().getCapability(CHEMICAL_HANDLER, tile.getBlockPos(), tile.getBlockState(), tile, dir) != null;
    }

    @Nullable
    @SuppressWarnings ("unchecked")
    public <T, C> T getCapability(BlockCapability<T, C> capability) {

        return capability == CHEMICAL_HANDLER ? (T) this : null;
    }

    @Nullable
    @Override
    @SuppressWarnings ("unchecked")
    public <T, C> T getExternalCapability(BlockCapability<T, C> capability, BlockPos externalPos) {

        return capability == CHEMICAL_HANDLER ? (T) new ExternalChemicalHandler(externalPos) : null;
    }

    /**
     * The handler handed to a specific neighboring block. Inserts are gated on routability -
     * content the grid could never deliver anywhere is rejected outright, so push logic that
     * probes with SIMULATE (AE2 pattern providers, ejectors) picks a real destination instead of
     * stranding content in the duct. Accepted inserts mark the pusher as a content origin, so
     * distribution never sends the content back where it came from.
     */
    private final class ExternalChemicalHandler implements IChemicalHandler {

        private final BlockPos externalPos;

        private ExternalChemicalHandler(BlockPos externalPos) {

            this.externalPos = externalPos.immutable();
        }

        //@formatter:off
        @Override public int getChemicalTanks() { return ChemicalGrid.this.getChemicalTanks(); }
        @Override public ChemicalStack getChemicalInTank(int tank) { return ChemicalGrid.this.getChemicalInTank(tank); }
        @Override public void setChemicalInTank(int tank, ChemicalStack stack) { ChemicalGrid.this.setChemicalInTank(tank, stack); }
        @Override public long getChemicalTankCapacity(int tank) { return ChemicalGrid.this.getChemicalTankCapacity(tank); }
        @Override public boolean isValid(int tank, ChemicalStack stack) { return ChemicalGrid.this.isValid(tank, stack); }
        @Override public ChemicalStack extractChemical(int tank, long amount, Action action) { return ChemicalGrid.this.extractChemical(tank, amount, action); }
        @Override public ChemicalStack extractChemical(long amount, Action action) { return ChemicalGrid.this.extractChemical(amount, action); }
        //@formatter:on

        @Override
        public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {

            if (tank != 0) {
                return stack;
            }
            return insertChemical(stack, action);
        }

        @Override
        public ChemicalStack insertChemical(ChemicalStack resource, Action action) {

            if (resource.isEmpty()) {
                return resource;
            }
            if (simulateRoutable(resource, resource.getAmount(), externalPos) <= 0) {
                return resource;
            }
            if (action.simulate()) {
                return ChemicalGrid.this.insertChemical(resource, action);
            }
            boolean added = markContentOrigin(externalPos);
            ChemicalStack remainder = ChemicalGrid.this.insertChemical(resource, action);
            if (added && remainder.getAmount() >= resource.getAmount()) {
                unmarkContentOrigin(externalPos);
            }
            return remainder;
        }

    }

    //@formatter:off
    public long getCapacity() { return storage.getCapacity(); }
    public ChemicalStack getChemical() { return storage.getChemical(); }
    public ChemicalStack getHeldChemical() { return heldContent(); }
    public ChemicalStack getRenderChemical() { return renderState.renderStack(); }
    public long getHeldAmount() { return heldAmountLong(); }
    public void setChemical(ChemicalStack chemical) {
        long before = storage.getChemical().getAmount();
        storage.setChemical(chemical);
        noteHeldSet(before, storage.getChemical().getAmount());
    }
    public ChemicalStack extractChemical(long amount, Action action) {
        // Route through the tank overload: it drains parked overflow first. Bypassing it here would
        // strand overflow content for any external puller using the tankless API.
        return extractChemical(0, amount, action);
    }
    public void extractStorage(long amount) { if (amount > 0) auditNoteOut(storage.extract(amount, Action.EXECUTE).getAmount()); }
    public ChemicalStack replayOverflow(ChemicalStack offered) {
        isReplayingOverflow = true;
        try { return insertChemical(offered, Action.EXECUTE); }
        finally { isReplayingOverflow = false; }
    }
    private void noteHeldSet(long before, long after) {
        if (after >= before) auditNoteIn(after - before); else auditNoteOut(before - after);
    }
    //@formatter:on

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {

        if (tank != 0) {
            return stack;
        }
        return insertChemical(stack, action);
    }

    @Override
    public ChemicalStack insertChemical(ChemicalStack resource, Action action) {

        ChemicalStack held = getHeldChemical();
        if (resource.isEmpty() || isSendingContent || !held.isEmpty() && !ChemicalStack.isSameChemical(held, resource)) {
            return resource;
        }
        ChemicalStack original = resource;
        long rejected = 0;
        if (!isReplayingOverflow) {
            long headroom = overflowHeadroom();
            if (headroom <= 0) return resource;
            if (resource.getAmount() > headroom) {
                rejected = resource.getAmount() - headroom;
                resource = resource.copyWithAmount(headroom);
            }
        }
        long added = storage.insert(resource, action);
        long overflow = resource.getAmount() - added;
        if (overflow <= 0) {
            if (action.execute() && !isReplayingOverflow) {
                auditNoteIn(added);
            }
            return rejected <= 0 ? ChemicalStack.EMPTY : original.copyWithAmount(rejected);
        }
        long sent = distributeOverflow(resource, overflow, action.execute());
        if (action.execute() && !isReplayingOverflow) {
            auditNoteIn(added + sent);
        }
        long totalRemaining = rejected + (overflow - sent);
        return totalRemaining == 0 ? ChemicalStack.EMPTY : original.copyWithAmount(totalRemaining);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {

        if (tank != 0) return ChemicalStack.EMPTY;
        ChemicalStack result;
        if (overflowBuffer.isEmpty()) {
            result = storage.extract(amount, action);
        } else {
            ChemicalStack pending = overflowBuffer.peek(amount);
            long pendingAmount = pending.getAmount();
            if (action.execute()) overflowBuffer.drain(pendingAmount);
            ChemicalStack rest = amount > pendingAmount ? storage.extract(amount - pendingAmount, action) : ChemicalStack.EMPTY;
            result = rest.isEmpty() ? pending : pending.copyWithAmount(pendingAmount + rest.getAmount());
        }
        if (action.execute() && !isDrainingHeld) {
            auditNoteOut(result.getAmount());
        }
        return result;
    }

    @Override
    public int getChemicalTanks() {

        return storage.getChemicalTanks();
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {

        return storage.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {

        // Nothing in this mod calls this; an external write bypasses the insert/extract ledger,
        // so fold its delta in and flag it - spam here means some mod mutates the grid directly.
        long before = tank == 0 ? storage.getChemical().getAmount() : 0;
        storage.setChemicalInTank(tank, stack);
        long after = tank == 0 ? storage.getChemical().getAmount() : 0;
        noteHeldSet(before, after);
        if (before != after) {
            ThermalDynamics.LOG.warn("Chemical grid {} content set externally via setChemicalInTank: {} -> {}", getId(), before, after);
        }
    }

    @Override
    public long getChemicalTankCapacity(int tank) {

        return storage.getChemicalTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {

        return storage.isValid(tank, stack);
    }

}
