package cofh.thermal.dynamics.common.grid.fluid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import java.util.Set;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

public class FluidGridNode extends GridNode<FluidGrid> implements ITickableGridNode {

    protected FluidConnection[] distArray = new FluidConnection[0];
    protected int distIndex = 0;

    protected FluidGridNode(FluidGrid grid) {

        super(grid);
    }

    protected void cacheConnections() {

        Level world = getWorld();
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        connections.clear();
        for (Direction dir : DIRECTIONS) {
            BlockPos targetPos = pos.relative(dir);
            if (world.isLoaded(targetPos) && grid.canConnectOnSide(targetPos, dir.getOpposite())) {
                connections.add(dir);
            }
        }
        distArray = connections.stream().map(dir -> new FluidConnection(serverLevel, dir, pos.relative(dir))).toArray(FluidConnection[]::new);
        cached = true;
    }

    @Override
    public void attachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return;
        }
        for (Direction dir : DIRECTIONS) {
            IAttachment attachment = duct.getAttachment(dir);
            if (attachment.needsTick()) {
                attachment.tick();
            }
        }
    }

    public boolean needsAttachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return false;
        }
        for (Direction dir : DIRECTIONS) {
            if (duct.getAttachment(dir).needsTick()) {
                return true;
            }
        }
        return false;
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
            for (int i = distIndex; i < distArray.length; ++i) {
                tickDir(duct, distArray[i]);
            }
            for (int i = 0; i < distIndex; ++i) {
                tickDir(duct, distArray[i]);
            }
        }
    }

    public int transmitFluid(FluidStack fluid, int amount, boolean simulate, Set<BlockPos> visitedTargets) {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null || distArray.length == 0 || fluid.isEmpty() || amount <= 0) {
            return 0;
        }
        FluidAction action = simulate ? SIMULATE : EXECUTE;
        int remaining = amount;
        int tempIndex = distIndex;
        ++distIndex;
        distIndex %= distArray.length;

        for (int i = distIndex; i < distArray.length && remaining > 0; ++i) {
            remaining -= fillDir(duct, distArray[i], fluid, remaining, action, visitedTargets);
        }
        for (int i = 0; i < distIndex && remaining > 0; ++i) {
            remaining -= fillDir(duct, distArray[i], fluid, remaining, action, visitedTargets);
        }
        if (simulate) {
            distIndex = tempIndex;
        }
        return amount - remaining;
    }

    private void tickDir(IDuct<?, ?> duct, FluidConnection connection) {

        FluidStack fluid = grid.getFluid();
        int accepted = fillDir(duct, connection, fluid, fluid.getAmount(), EXECUTE, null);
        if (accepted > 0) {
            grid.drain(accepted, EXECUTE);
        }
    }

    private int fillDir(IDuct<?, ?> duct, FluidConnection connection, FluidStack fluid, int amount, FluidAction action, Set<BlockPos> visitedTargets) {

        Direction dir = connection.direction;
        if (duct.getConnectionType(dir) == DISABLED) {
            return 0;
        }
        if (visitedTargets != null && visitedTargets.contains(connection.targetPos)) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        if (connection.consumeInvalidation()) {
            attachment.invalidate();
        }
        IFluidHandler handler = attachment.wrapExternalCapability(Capabilities.FluidHandler.BLOCK,
                connection.capabilityCache.getCapability());
        if (handler != null) {
            int accepted = handler.fill(amount == fluid.getAmount() ? fluid : fluid.copyWithAmount(amount), action);
            if (accepted > 0 && visitedTargets != null) {
                visitedTargets.add(connection.targetPos);
            }
            return accepted;
        }
        return 0;
    }

    private final class FluidConnection {

        private final Direction direction;
        private final BlockPos targetPos;
        private final BlockCapabilityCache<IFluidHandler, Direction> capabilityCache;
        private boolean invalidated;

        private FluidConnection(ServerLevel world, Direction direction, BlockPos targetPos) {

            this.direction = direction;
            this.targetPos = targetPos;
            capabilityCache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, world, targetPos, direction.getOpposite(),
                    () -> cached && isLoaded(), () -> invalidated = true);
        }

        private boolean consumeInvalidation() {

            boolean result = invalidated;
            invalidated = false;
            return result;
        }

    }

}
