package cofh.thermal.dynamics.common.block;

import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public class ItemDuctBlock extends DuctBlock {

    public ItemDuctBlock(Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {

        super(properties, blockEntityType);
    }

    // Client-side traveling items are ticked centrally by ClientTravelingItemIndex; no BE ticker.

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {

        if (state.getBlock() != newState.getBlock() && level.getBlockEntity(pos) instanceof ItemDuctBlockEntity duct) {
            duct.dropItemContents();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

}
