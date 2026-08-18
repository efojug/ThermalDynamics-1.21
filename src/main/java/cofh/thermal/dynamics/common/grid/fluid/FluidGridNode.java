package cofh.thermal.dynamics.common.grid.fluid;

import cofh.thermal.dynamics.common.grid.ContentGridNode;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

public class FluidGridNode extends ContentGridNode<FluidGrid, FluidStack, IFluidHandler> {

    protected FluidGridNode(FluidGrid grid) {

        super(grid);
    }

    @Override
    protected BlockCapability<IFluidHandler, Direction> capability() {

        return Capabilities.FluidHandler.BLOCK;
    }

    @Override
    protected long fill(IFluidHandler handler, FluidStack stack, long amount, boolean execute) {

        int request = (int) Math.min(amount, Integer.MAX_VALUE);
        return handler.fill(request == stack.getAmount() ? stack : stack.copyWithAmount(request), execute ? EXECUTE : SIMULATE);
    }

    @Override
    protected boolean isEmptyStack(FluidStack stack) {

        return stack.isEmpty();
    }

}
