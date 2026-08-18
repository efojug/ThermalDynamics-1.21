package cofh.thermal.dynamics.compat.mekanism.grid;

import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * A single shared Chemical tank for a Chemical Duct grid.
 */
public final class ChemicalGridStorage implements IChemicalHandler, INBTSerializable<CompoundTag> {

    private long capacity;
    private ChemicalStack chemical = ChemicalStack.EMPTY;

    public ChemicalGridStorage(long capacity) {

        setCapacity(capacity);
    }

    public ChemicalGridStorage setCapacity(long capacity) {

        this.capacity = Math.max(0, capacity);
        if (!chemical.isEmpty() && chemical.getAmount() > this.capacity) {
            chemical = chemical.copyWithAmount(this.capacity);
        }
        return this;
    }

    public ChemicalGridStorage setChemical(ChemicalStack chemical) {

        this.chemical = chemical.isEmpty() ? ChemicalStack.EMPTY : chemical.copyWithAmount(Math.min(chemical.getAmount(), capacity));
        return this;
    }

    public long getCapacity() {

        return capacity;
    }

    public ChemicalStack getChemical() {

        return chemical;
    }

    public long insert(ChemicalStack resource, Action action) {

        return resource.getAmount() - insertChemical(0, resource, action).getAmount();
    }

    public ChemicalStack extract(long amount, Action action) {

        return extractChemical(0, amount, action);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        CompoundTag tag = new CompoundTag();
        if (!chemical.isEmpty()) {
            tag.put("Chemical", chemical.saveOptional(provider));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {

        setChemical(ChemicalStack.parseOptional(provider, tag.getCompound("Chemical")));
    }

    @Override
    public int getChemicalTanks() {

        return 1;
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {

        return tank == 0 ? chemical : ChemicalStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {

        if (tank == 0) {
            setChemical(stack);
        }
    }

    @Override
    public long getChemicalTankCapacity(int tank) {

        return tank == 0 ? capacity : 0;
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {

        return tank == 0 && !stack.isEmpty();
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {

        if (tank != 0 || stack.isEmpty() || !isValid(tank, stack) || !chemical.isEmpty() && !ChemicalStack.isSameChemical(chemical, stack)) {
            return stack;
        }
        long accepted = Math.min(capacity - chemical.getAmount(), stack.getAmount());
        if (accepted <= 0) {
            return stack;
        }
        if (action.execute()) {
            if (chemical.isEmpty()) {
                chemical = stack.copyWithAmount(accepted);
            } else {
                chemical.grow(accepted);
            }
        }
        return stack.copyWithAmount(stack.getAmount() - accepted);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {

        if (tank != 0 || amount <= 0 || chemical.isEmpty()) {
            return ChemicalStack.EMPTY;
        }
        long extracted = Math.min(amount, chemical.getAmount());
        ChemicalStack result = chemical.copyWithAmount(extracted);
        if (action.execute()) {
            chemical.shrink(extracted);
            if (chemical.isEmpty()) {
                chemical = ChemicalStack.EMPTY;
            }
        }
        return result;
    }

}
