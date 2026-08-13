package cofh.thermal.dynamics.common.grid.fluid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

public class FluidGridNode extends GridNode<FluidGrid> implements ITickableGridNode {

    protected Direction[] distArray = new Direction[0];
    protected int distIndex = 0;

    protected FluidGridNode(FluidGrid grid) {

        super(grid);
    }

    protected void cacheConnections() {

        for (Direction dir : DIRECTIONS) {
            if (grid.canConnectOnSide(pos.relative(dir), dir.getOpposite())) {
                connections.add(dir);
            }
        }
        distArray = connections.toArray(new Direction[0]);
        cached = true;
    }

    @Override
    public void attachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return;
        }
        for (Direction dir : DIRECTIONS) {
            duct.getAttachment(dir).tick();
        }
    }

    @Override
    public void distributionTick() {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();

        if (duct != null && distArray.length > 0) {
            ++distIndex;
            distIndex %= distArray.length;
            Level world = getWorld();

            for (int i = distIndex; i < distArray.length; ++i) {
                tickDir(world, pos, duct, distArray[i]);
            }
            for (int i = 0; i < distIndex; ++i) {
                tickDir(world, pos, duct, distArray[i]);
            }
        }
    }

    public int transmitFluid(FluidStack fluid, boolean simulate) {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null || distArray.length == 0 || fluid.isEmpty()) {
            return 0;
        }
        Level world = getWorld();
        FluidAction action = simulate ? SIMULATE : EXECUTE;
        int amount = fluid.getAmount();
        int remaining = amount;
        int tempIndex = distIndex;
        ++distIndex;
        distIndex %= distArray.length;

        for (int i = distIndex; i < distArray.length && remaining > 0; ++i) {
            remaining -= fillDir(world, pos, duct, distArray[i], fluid.copyWithAmount(remaining), action);
        }
        for (int i = 0; i < distIndex && remaining > 0; ++i) {
            remaining -= fillDir(world, pos, duct, distArray[i], fluid.copyWithAmount(remaining), action);
        }
        if (simulate) {
            distIndex = tempIndex;
        }
        return amount - remaining;
    }

    private void tickDir(Level world, BlockPos pos, IDuct<?, ?> duct, Direction dir) {

        FluidStack fluid = grid.getFluid();
        int accepted = fillDir(world, pos, duct, dir, fluid.copyWithAmount(fluid.getAmount()), EXECUTE);
        if (accepted > 0) {
            grid.drain(accepted, EXECUTE);
        }
    }

    private int fillDir(Level world, BlockPos pos, IDuct<?, ?> duct, Direction dir, FluidStack fluid, FluidAction action) {

        if (duct.getConnectionType(dir) == DISABLED) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        BlockEntity tile = world.getBlockEntity(pos.relative(dir));
        if (tile == null) {
            return 0;
        }
        IFluidHandler handler = attachment.wrapExternalCapability(Capabilities.FluidHandler.BLOCK,
                world.getCapability(Capabilities.FluidHandler.BLOCK, tile.getBlockPos(), tile.getBlockState(), tile, dir.getOpposite()));
        if (handler != null) {
            return handler.fill(fluid, action);
        }
        return 0;
    }

}
