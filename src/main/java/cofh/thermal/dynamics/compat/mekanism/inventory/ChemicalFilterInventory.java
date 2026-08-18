package cofh.thermal.dynamics.compat.mekanism.inventory;

import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A false-copy inventory for chemical filters. It deliberately never accepts ItemStacks.
 */
class ChemicalFilterInventory implements Container {

    private final NonNullList<ChemicalStack> chemicals;
    private final AbstractContainerMenu eventHandler;

    ChemicalFilterInventory(AbstractContainerMenu eventHandler, List<ChemicalStack> contents, int size) {

        chemicals = NonNullList.withSize(size, ChemicalStack.EMPTY);
        this.eventHandler = eventHandler;
        readFromSource(contents);
    }

    List<ChemicalStack> getChemicals() {

        return chemicals;
    }

    void readFromSource(List<ChemicalStack> contents) {

        for (int i = 0; i < chemicals.size(); ++i) {
            chemicals.set(i, i < contents.size() && !contents.get(i).isEmpty() ? contents.get(i).copyWithAmount(1) : ChemicalStack.EMPTY);
        }
    }

    void setChemical(int slot, ChemicalStack chemical) {

        if (slot < 0 || slot >= chemicals.size()) {
            return;
        }
        chemicals.set(slot, chemical.isEmpty() ? ChemicalStack.EMPTY : chemical.copyWithAmount(1));
        eventHandler.slotsChanged(this);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {

        return stack.isEmpty();
    }

    @Override
    public int getContainerSize() {

        return chemicals.size();
    }

    @Override
    public boolean isEmpty() {

        return chemicals.stream().allMatch(ChemicalStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {

        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {

        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {

        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {

        if (stack.isEmpty()) {
            setChemical(slot, ChemicalStack.EMPTY);
        }
    }

    @Override
    public void setChanged() {

    }

    @Override
    public boolean stillValid(Player player) {

        return true;
    }

    @Override
    public void clearContent() {

        for (int i = 0; i < chemicals.size(); ++i) {
            chemicals.set(i, ChemicalStack.EMPTY);
        }
    }

}
