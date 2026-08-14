package cofh.thermal.dynamics.common.grid.fluid;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;

public final class FluidGridStorage implements IFluidHandler, INBTSerializable<CompoundTag> {

    private int capacity;

    private FluidStack fluid = FluidStack.EMPTY;

    public FluidGridStorage(int capacity) {

        this.capacity = Math.max(0, capacity);
    }

    public FluidGridStorage setCapacity(int capacity) {

        this.capacity = Math.max(0, capacity);
        if (fluid.getAmount() > this.capacity) {
            fluid = fluid.copyWithAmount(this.capacity);
        }
        return this;
    }

    public FluidGridStorage setFluid(FluidStack fluid) {

        this.fluid = fluid.copyWithAmount(Math.min(fluid.getAmount(), capacity));
        return this;
    }

    public int getCapacity() {

        return capacity;
    }

    public FluidStack getFluid() {

        return fluid;
    }

    // region NBT
    public FluidGridStorage read(HolderLookup.Provider provider, CompoundTag nbt) {

        // Fluid stacks are stored with "id" as a fluid resource location (STRING). Grid-level tags use
        // "id" for the grid UUID (INT_ARRAY) and must never be parsed as a fluid stack.
        if (nbt.getTagType("id") == Tag.TAG_STRING) {
            setFluid(FluidStack.parseOptional(provider, nbt));
        }
        return this;
    }

    public CompoundTag write(HolderLookup.Provider provider, CompoundTag nbt) {

        if (!fluid.isEmpty()) {
            nbt.merge((CompoundTag) fluid.save(provider, new CompoundTag()));
        }
        return nbt;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        return write(provider, new CompoundTag());
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

        read(provider, nbt);
    }
    // endregion

    @Override
    public int getTanks() {

        return 1;
    }

    @Nonnull
    @Override
    public FluidStack getFluidInTank(int tank) {

        return fluid;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {

        if (resource.isEmpty() || !isFluidValid(0, resource)) {
            return 0;
        }
        if (action.simulate()) {
            if (fluid.isEmpty()) {
                return Math.min(capacity, resource.getAmount());
            }
            if (!FluidStack.isSameFluidSameComponents(fluid, resource)) {
                return 0;
            }
            return Math.min(capacity - fluid.getAmount(), resource.getAmount());
        }
        if (fluid.isEmpty()) {
            setFluid(resource.copyWithAmount(Math.min(capacity, resource.getAmount())));
            return fluid.getAmount();
        }
        if (!FluidStack.isSameFluidSameComponents(fluid, resource)) {
            return 0;
        }
        if (fluid.getAmount() >= capacity) {
            return 0;
        }
        int filled = capacity - fluid.getAmount();

        if (resource.getAmount() < filled) {
            fluid.grow(resource.getAmount());
            filled = resource.getAmount();
        } else {
            fluid.setAmount(capacity);
        }
        return filled;
    }

    @Nonnull
    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {

        if (resource.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, fluid)) {
            return FluidStack.EMPTY;
        }
        return drain(resource.getAmount(), action);
    }

    @Nonnull
    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {

        if (maxDrain <= 0 || fluid.isEmpty()) {
            return FluidStack.EMPTY;
        }
        int drained = maxDrain;
        if (fluid.getAmount() < drained) {
            drained = fluid.getAmount();
        }
        FluidStack stack = fluid.copyWithAmount(drained);
        if (action.execute()) {
            fluid.shrink(drained);
            if (fluid.isEmpty()) {
                setFluid(FluidStack.EMPTY);
            }
        }
        return stack;
    }

    @Override
    public int getTankCapacity(int tank) {

        return capacity;
    }

    @Override
    public boolean isFluidValid(int tank, @Nonnull FluidStack stack) {

        return true;
    }
    // endregion
}
