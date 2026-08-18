package cofh.thermal.dynamics.common.grid.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

public record ItemRoute(List<Direction> steps, BlockPos destination, Direction destinationSide) {

}
