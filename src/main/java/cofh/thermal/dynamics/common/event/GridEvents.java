package cofh.thermal.dynamics.common.event;

import cofh.thermal.dynamics.api.grid.IGridContainer;
import cofh.thermal.dynamics.common.grid.GridContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

public class GridEvents {

    public static void register() {

        NeoForge.EVENT_BUS.addListener(GridEvents::onWorldTick);
        NeoForge.EVENT_BUS.addListener(GridEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(GridEvents::onChunkUnload);
    }

    private static void onWorldTick(LevelTickEvent.Post event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        IGridContainer gridContainer = IGridContainer.getGrid(level);
        if (gridContainer != null) {
            ((GridContainer) gridContainer).onWorldTick();
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        IGridContainer gridContainer = IGridContainer.getGrid(level);
        if (gridContainer != null) {
            ((GridContainer) gridContainer).onChunkLoad(event.getChunk());
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        IGridContainer gridContainer = IGridContainer.getGrid(level);
        if (gridContainer != null) {
            ((GridContainer) gridContainer).onChunkUnload(event.getChunk());
        }
    }

}
