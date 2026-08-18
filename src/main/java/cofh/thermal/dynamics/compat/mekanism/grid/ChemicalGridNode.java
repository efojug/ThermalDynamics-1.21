package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.common.grid.ContentGridNode;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;

public class ChemicalGridNode extends ContentGridNode<ChemicalGrid, ChemicalStack, IChemicalHandler> {

    protected ChemicalGridNode(ChemicalGrid grid) {

        super(grid);
    }

    @Override
    protected BlockCapability<IChemicalHandler, Direction> capability() {

        return CHEMICAL_HANDLER;
    }

    @Override
    protected long fill(IChemicalHandler handler, ChemicalStack stack, long amount, boolean execute) {

        return amount - handler.insertChemical(stack.copyWithAmount(amount), execute ? Action.EXECUTE : Action.SIMULATE).getAmount();
    }

    @Override
    protected boolean isEmptyStack(ChemicalStack stack) {

        return stack.isEmpty();
    }

}
