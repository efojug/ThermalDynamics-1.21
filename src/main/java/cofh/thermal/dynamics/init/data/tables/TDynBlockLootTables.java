package cofh.thermal.dynamics.init.data.tables;

import cofh.lib.init.data.loot.BlockLootSubProviderCoFH;
import net.minecraft.core.HolderLookup;

import static cofh.thermal.core.ThermalCore.BLOCKS;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.init.registries.TDynIDs.*;

public class TDynBlockLootTables extends BlockLootSubProviderCoFH {

    public TDynBlockLootTables(HolderLookup.Provider provider) {

        super(provider);
    }

    @Override
    protected void generate() {

        var regBlocks = BLOCKS;
        var regItems = ITEMS;

        createSimpleDropTable(regBlocks.get(ID_ENERGY_DUCT));
        createSimpleDropTable(regBlocks.get(ID_FLUID_DUCT));
        createSimpleDropTable(regBlocks.get(ID_FLUID_DUCT_WINDOWED));
        createSimpleDropTable(regBlocks.get(ID_ITEM_DUCT));

        // createSyncDropTable(regBlocks.get(ID_ENERGY_DISTRIBUTOR));

        createSyncDropTable(regBlocks.get(ID_ITEM_BUFFER));
    }

}
