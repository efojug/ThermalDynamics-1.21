package cofh.thermal.dynamics.common.grid.energy;

import cofh.lib.common.energy.IRedstoneFluxStorage;

public final class EnergyGridStorage implements IRedstoneFluxStorage {

    protected final EnergyGrid grid;

    public EnergyGridStorage(EnergyGrid grid) {

        this.grid = grid;
    }

    // region IEnergyStorage
    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {

        return grid.receiveEnergy(maxReceive, simulate);
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
