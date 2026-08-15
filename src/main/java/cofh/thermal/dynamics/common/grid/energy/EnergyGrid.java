package cofh.thermal.dynamics.common.grid.energy;

import cofh.core.util.helpers.EnergyHelper;
import cofh.lib.common.energy.IRedstoneFluxStorage;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.grid.Grid;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

import static cofh.thermal.dynamics.init.registries.TDynGrids.ENERGY_GRID;

/**
 * @author covers1624
 */
public class EnergyGrid extends Grid<EnergyGrid, EnergyGridNode> implements IRedstoneFluxStorage {

    protected final EnergyGridStorage storage;

    protected EnergyGridNode[] nodeList = new EnergyGridNode[0];
    protected int nodeTracker = 0;
    protected boolean isSendingEnergy = false;

    public EnergyGrid(UUID id, Level world) {

        super(ENERGY_GRID.get(), id, world);
        storage = new EnergyGridStorage(this);
    }

    @Override
    public EnergyGridNode newNode() {

        return new EnergyGridNode(this);
    }

    @Override
    public void tick() {

        // Connections are initialized by receiveEnergy when a grid actually receives energy.
    }

    @Override
    public void onModified() {

        nodeList = new EnergyGridNode[0];
        super.onModified();
    }

    @Override
    public void onMerge(EnergyGrid from) {

        refreshCapabilities();
        from.refreshCapabilities();
    }

    @Override
    public void onSplit(List<EnergyGrid> others) {

        for (EnergyGrid grid : others) {
            grid.refreshCapabilities();
        }
        this.refreshCapabilities();
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        if (GridHelper.getGridHost(tile) != null) {
            return false; // We cannot externally connect to other grids.
        }
        if (dir != null) {
            return EnergyHelper.hasEnergyHandlerCap(tile, dir);
        }
        return false;
    }

    @Nullable
    @SuppressWarnings ("unchecked")
    public <T, C> T getCapability(BlockCapability<T, C> capability) {

        if (capability == Capabilities.EnergyStorage.BLOCK) {
            return (T) storage;
        }
        return null;
    }

    @Override
    public void refreshCapabilities() {

        for (var node : getNodes().entrySet()) {
            if (!node.getValue().isLoaded()) {
                continue;
            }
            if (getLevel().getBlockEntity(node.getKey()) instanceof DuctBlockEntity<?, ?> duct) {
                duct.invalidateAttachments();
            }
            getLevel().invalidateCapabilities(node.getKey());
        }
    }

    // region IEnergyStorage
    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {

        if (isSendingEnergy) {
            return 0;
        }
        EnergyGridNode[] list = nodeList;
        if (list.length != getNodes().size()) {
            list = getNodes().values().toArray(new EnergyGridNode[0]);
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.length == 0) {
            return 0;
        }
        int tempTracker = nodeTracker;
        int energy = maxReceive;
        isSendingEnergy = true;
        try {
            for (int i = nodeTracker; i < list.length && energy > 0; i++) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                energy -= list[i].transmitEnergy(energy, simulate);
                if (energy == 0) {
                    nodeTracker = i + 1;
                }
            }
            for (int i = 0; i < list.length && i < nodeTracker && energy > 0; i++) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                energy -= list[i].transmitEnergy(energy, simulate);
                if (energy == 0) {
                    nodeTracker = i + 1;
                }
            }
            if (energy > 0) {
                ++nodeTracker;
            }
            if (nodeTracker >= list.length) {
                nodeTracker = 0;
            }
            if (simulate) {
                nodeTracker = tempTracker;
            }
        } finally {
            isSendingEnergy = false;
        }
        return maxReceive - energy;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {

        return 0;
    }

    @Override
    public int getEnergyStored() {

        return 0;
    }

    @Override
    public int getMaxEnergyStored() {

        return Integer.MAX_VALUE;
    }

    @Override
    public boolean canExtract() {

        return false;
    }

    @Override
    public boolean canReceive() {

        return true;
    }
    // endregion
}
