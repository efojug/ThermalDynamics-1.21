package cofh.thermal.dynamics.init.data.providers;

import cofh.lib.init.data.LootTableProviderCoFH;
import cofh.thermal.dynamics.init.data.tables.TDynBlockLootTables;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TDynLootTableProvider extends LootTableProviderCoFH {

    public TDynLootTableProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {

        super(output, List.of(
                new SubProviderEntry(TDynBlockLootTables::new, LootContextParamSets.BLOCK)
        ), registries);
    }

}
