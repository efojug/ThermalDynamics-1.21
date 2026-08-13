package cofh.thermal.dynamics.common.grid.energy;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;

public class EnergyGridNode extends GridNode<EnergyGrid> implements ITickableGridNode {

    protected Direction[] distArray = new Direction[0];
    protected int distIndex = 0;

    protected EnergyGridNode(EnergyGrid grid) {

        super(grid);
    }

    protected void cacheConnections() {

        for (Direction dir : Direction.values()) {
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
        Level world = getWorld();

        int accepted = 0;
        for (int i = distIndex; i < distArray.length && accepted < energy; ++i) {
            accepted += transmitEnergyDir(world, pos, duct, distArray[i], energy - accepted, simulate);
        }
        for (int i = 0; i < distIndex && accepted < energy; ++i) {
            accepted += transmitEnergyDir(world, pos, duct, distArray[i], energy - accepted, simulate);
        }
        if (simulate) {
            distIndex = tempIndex;
        }
        return accepted;
    }

    private int transmitEnergyDir(Level world, BlockPos pos, IDuct<?, ?> duct, Direction dir, int amount, boolean simulate) {

        if (duct.getConnectionType(dir) == DISABLED) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        BlockEntity tile = world.getBlockEntity(pos.relative(dir));
        if (tile == null) {
            return 0;
        }
        IEnergyStorage storage = attachment.wrapExternalCapability(Capabilities.EnergyStorage.BLOCK,
                world.getCapability(Capabilities.EnergyStorage.BLOCK, tile.getBlockPos(), tile.getBlockState(), tile, dir.getOpposite()));
        if (storage == null) {
            return 0;
        }
        return storage.receiveEnergy(amount, simulate);
    }

}
