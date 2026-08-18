package cofh.thermal.dynamics.common.config;

import cofh.core.common.config.IBaseConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.Supplier;

public class TDynConfig implements IBaseConfig {

    public static int itemFilterStacksPerTick = 16;

    private Supplier<Integer> filterStacks;

    @Override
    public void apply(ModConfigSpec.Builder builder) {

        builder.push("Ducts");
        builder.push("Item Duct");
        filterStacks = builder
                .comment("Maximum number of 64-item traveling stacks created each tick by one Item Duct Filter.")
                .defineInRange("Filter Stacks Per Tick", 16, 1, 256);
        builder.pop(2);
    }

    @Override
    public void refresh() {

        if (filterStacks != null) {
            itemFilterStacksPerTick = filterStacks.get();
        }
    }

}
