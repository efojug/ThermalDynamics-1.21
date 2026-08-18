package cofh.thermal.dynamics.common.block;

import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class ItemDuctBlock extends DuctBlock {

    public ItemDuctBlock(Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {

        super(properties, blockEntityType);
    }

    @Nullable
    @Override
    @SuppressWarnings ("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {

        if (!level.isClientSide() || type != blockEntityType.get()) {
            return null;
        }
        return (tickerLevel, tickerPos, tickerState, blockEntity) -> ((ItemDuctBlockEntity) blockEntity).clientTick();
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {

        if (state.getBlock() != newState.getBlock() && level.getBlockEntity(pos) instanceof ItemDuctBlockEntity duct) {
            duct.dropItemContents();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

}
