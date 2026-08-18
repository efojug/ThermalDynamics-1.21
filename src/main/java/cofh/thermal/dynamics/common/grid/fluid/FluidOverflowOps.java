package cofh.thermal.dynamics.common.grid.fluid;

import cofh.thermal.dynamics.common.grid.OverflowBuffer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;

public final class FluidOverflowOps implements OverflowBuffer.Ops<FluidStack> {
    public static final FluidOverflowOps INSTANCE = new FluidOverflowOps();
    private FluidOverflowOps() { }
    public FluidStack empty() { return FluidStack.EMPTY; }
    public boolean isEmpty(FluidStack s) { return s.isEmpty(); }
    public long amount(FluidStack s) { return s.getAmount(); }
    public FluidStack withAmount(FluidStack s, long n) { return s.copyWithAmount((int) Math.min(Integer.MAX_VALUE, Math.max(0, n))); }
    public boolean sameType(FluidStack a, FluidStack b) { return FluidStack.isSameFluidSameComponents(a, b); }
    public long maxAmount() { return Integer.MAX_VALUE; }
    public CompoundTag save(FluidStack s, HolderLookup.Provider p) { return (CompoundTag) s.save(p, new CompoundTag()); }
    public FluidStack load(CompoundTag t, HolderLookup.Provider p) { return FluidStack.parseOptional(p, t); }
}
