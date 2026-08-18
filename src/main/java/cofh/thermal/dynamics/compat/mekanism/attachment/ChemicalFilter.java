package cofh.thermal.dynamics.compat.mekanism.attachment;

import cofh.core.util.filter.IFilterOptions;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Chemical filters store ChemicalStacks directly. Chemical container items are never filter entries.
 */
public final class ChemicalFilter implements IFilterOptions {

    private static final String TAG_FILTER = "ChemicalFilter";
    private static final String TAG_CHEMICALS = "Chemicals";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_ALLOW_LIST = "AllowList";
    private static final String TAG_CHECK_NBT = "CheckNBT";

    private final List<ChemicalStack> chemicals;
    private boolean allowList;
    private boolean checkNBT;

    public ChemicalFilter(int size) {

        chemicals = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            chemicals.add(ChemicalStack.EMPTY);
        }
    }

    public int size() {

        return chemicals.size();
    }

    public List<ChemicalStack> getChemicals() {

        return chemicals;
    }

    public void setChemicals(List<ChemicalStack> entries) {

        for (int i = 0; i < chemicals.size(); ++i) {
            setChemical(i, i < entries.size() ? entries.get(i) : ChemicalStack.EMPTY);
        }
    }

    public void setChemical(int slot, ChemicalStack chemical) {

        if (slot >= 0 && slot < chemicals.size()) {
            chemicals.set(slot, chemical.isEmpty() ? ChemicalStack.EMPTY : chemical.copyWithAmount(1));
        }
    }

    public boolean valid(ChemicalStack chemical) {

        if (chemical.isEmpty()) {
            return false;
        }
        for (ChemicalStack entry : chemicals) {
            if (!entry.isEmpty() && ChemicalStack.isSameChemical(entry, chemical)) {
                return allowList;
            }
        }
        return !allowList;
    }

    public void read(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {

        CompoundTag filterTag = nbt.getCompound(TAG_FILTER);
        if (filterTag.isEmpty()) {
            return;
        }
        for (int i = 0; i < chemicals.size(); ++i) {
            chemicals.set(i, ChemicalStack.EMPTY);
        }
        for (Tag tag : filterTag.getList(TAG_CHEMICALS, Tag.TAG_COMPOUND)) {
            CompoundTag chemicalTag = (CompoundTag) tag;
            int slot = chemicalTag.getByte(TAG_SLOT);
            if (slot >= 0 && slot < chemicals.size()) {
                setChemical(slot, ChemicalStack.parseOptional(registries, chemicalTag));
            }
        }
        allowList = filterTag.getBoolean(TAG_ALLOW_LIST);
        checkNBT = filterTag.getBoolean(TAG_CHECK_NBT);
    }

    public void write(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {

        CompoundTag filterTag = new CompoundTag();
        ListTag entries = new ListTag();
        for (int i = 0; i < chemicals.size(); ++i) {
            ChemicalStack chemical = chemicals.get(i);
            if (!chemical.isEmpty()) {
                CompoundTag chemicalTag = (CompoundTag) chemical.save(registries);
                chemicalTag.putByte(TAG_SLOT, (byte) i);
                entries.add(chemicalTag);
            }
        }
        filterTag.put(TAG_CHEMICALS, entries);
        filterTag.putBoolean(TAG_ALLOW_LIST, allowList);
        filterTag.putBoolean(TAG_CHECK_NBT, checkNBT);
        nbt.put(TAG_FILTER, filterTag);
    }

    @Override
    public boolean getAllowList() {

        return allowList;
    }

    @Override
    public boolean setAllowList(boolean allowList) {

        this.allowList = allowList;
        return true;
    }

    @Override
    public boolean getCheckNBT() {

        return checkNBT;
    }

    @Override
    public boolean setCheckNBT(boolean checkNBT) {

        this.checkNBT = checkNBT;
        return true;
    }

}
