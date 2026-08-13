package cofh.thermal.dynamics.init.data;

import cofh.thermal.dynamics.init.data.providers.TDynItemModelProvider;
import cofh.thermal.dynamics.init.data.providers.TDynLootTableProvider;
import cofh.thermal.dynamics.init.data.providers.TDynRecipeProvider;
import cofh.thermal.dynamics.init.data.providers.TDynTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

@EventBusSubscriber (bus = EventBusSubscriber.Bus.MOD, modid = ID_THERMAL_DYNAMICS)
public class TDynDataGen {

    @SubscribeEvent
    public static void gatherData(final GatherDataEvent event) {

        DataGenerator gen = event.getGenerator();
        PackOutput output = gen.getPackOutput();
        ExistingFileHelper exFileHelper = event.getExistingFileHelper();

        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        TDynTagsProvider.Block blockTags = new TDynTagsProvider.Block(output, lookup, exFileHelper);
        gen.addProvider(event.includeServer(), blockTags);
        gen.addProvider(event.includeServer(), new TDynTagsProvider.Item(output, lookup, blockTags.contentsGetter(), exFileHelper));

        gen.addProvider(event.includeServer(), new TDynLootTableProvider(output, lookup));
        gen.addProvider(event.includeServer(), new TDynRecipeProvider(output, lookup));

        gen.addProvider(event.includeClient(), new TDynItemModelProvider(output, exFileHelper));
    }

}
