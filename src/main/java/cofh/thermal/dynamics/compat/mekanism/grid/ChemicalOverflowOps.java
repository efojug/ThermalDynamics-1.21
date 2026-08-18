package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.common.grid.OverflowBuffer;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

public final class ChemicalOverflowOps implements OverflowBuffer.Ops<ChemicalStack> {
    public static final ChemicalOverflowOps INSTANCE = new ChemicalOverflowOps();
    private ChemicalOverflowOps() { }
    public ChemicalStack empty() { return ChemicalStack.EMPTY; }
    public boolean isEmpty(ChemicalStack s) { return s.isEmpty(); }
    public long amount(ChemicalStack s) { return s.getAmount(); }
    public ChemicalStack withAmount(ChemicalStack s, long n) { return s.copyWithAmount(Math.max(0, n)); }
    public boolean sameType(ChemicalStack a, ChemicalStack b) { return ChemicalStack.isSameChemical(a, b); }
    public long maxAmount() { return Long.MAX_VALUE; }
    public CompoundTag save(ChemicalStack s, HolderLookup.Provider p) { return (CompoundTag) s.saveOptional(p); }
    public ChemicalStack load(CompoundTag t, HolderLookup.Provider p) { return ChemicalStack.parseOptional(p, t); }
}
