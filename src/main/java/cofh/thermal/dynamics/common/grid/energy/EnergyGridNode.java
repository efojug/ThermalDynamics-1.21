package cofh.thermal.dynamics.common.grid.energy;

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
import net.neoforged.neoforge.energy.IEnergyStorage;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;

public class EnergyGridNode extends GridNode<EnergyGrid> implements ITickableGridNode {

    protected EnergyConnection[] distArray = new EnergyConnection[0];
    protected int distIndex = 0;

    protected EnergyGridNode(EnergyGrid grid) {

        super(grid);
    }

    protected void cacheConnections() {

        Level world = getWorld();
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        connections.clear();
        for (Direction dir : Direction.values()) {
            BlockPos targetPos = pos.relative(dir);
            if (world.isLoaded(targetPos) && grid.canConnectOnSide(targetPos, dir.getOpposite())) {
                connections.add(dir);
            }
        }
        distArray = connections.stream().map(dir -> new EnergyConnection(serverLevel, dir, pos.relative(dir))).toArray(EnergyConnection[]::new);
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
    }

    public int transmitEnergy(int energy, boolean simulate) {

        if (!cached) {
            cacheConnections();
        }
        if (energy <= 0 || distArray.length == 0) {
            return 0;
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return 0;
        }
        int tempIndex = distIndex;
        ++distIndex;
        distIndex %= distArray.length;
        int accepted = 0;
        for (int i = distIndex; i < distArray.length && accepted < energy; ++i) {
            accepted += transmitEnergyDir(duct, distArray[i], energy - accepted, simulate);
        }
        for (int i = 0; i < distIndex && accepted < energy; ++i) {
            accepted += transmitEnergyDir(duct, distArray[i], energy - accepted, simulate);
        }
        if (simulate) {
            distIndex = tempIndex;
        }
        return accepted;
    }

    private int transmitEnergyDir(IDuct<?, ?> duct, EnergyConnection connection, int amount, boolean simulate) {

        Direction dir = connection.direction;
        if (duct.getConnectionType(dir) == DISABLED) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        if (connection.consumeInvalidation()) {
            attachment.invalidate();
        }
        IEnergyStorage storage = attachment.wrapExternalCapability(Capabilities.EnergyStorage.BLOCK,
                connection.capabilityCache.getCapability());
        if (storage == null) {
            return 0;
        }
        return storage.receiveEnergy(amount, simulate);
    }

    private final class EnergyConnection {

        private final Direction direction;
        private final BlockCapabilityCache<IEnergyStorage, Direction> capabilityCache;
        private boolean invalidated;

        private EnergyConnection(ServerLevel world, Direction direction, BlockPos targetPos) {

            this.direction = direction;
            capabilityCache = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, world, targetPos, direction.getOpposite(),
                    () -> cached && isLoaded(), () -> invalidated = true);
        }

        private boolean consumeInvalidation() {

            boolean result = invalidated;
            invalidated = false;
            return result;
        }

    }

}
