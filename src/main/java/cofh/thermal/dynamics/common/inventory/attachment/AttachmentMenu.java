package cofh.thermal.dynamics.common.inventory.attachment;

import cofh.core.common.inventory.ContainerMenuCoFH;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

public abstract class AttachmentMenu extends ContainerMenuCoFH {

    private static final int GUI_WIDTH = 176;
    private static final int FILTER_SLOT_SPACING = 18;
    private static final int SERVO_FILTER_BUTTON_GAP = 7;
    private static final int SERVO_FILTER_BUTTON_WIDTH = 20;

    public final BlockEntity hostTile;
    public final IAttachment baseAttachment;

    public AttachmentMenu(@Nullable MenuType<?> type, int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(type, id, inventory, player);
        hostTile = world.getBlockEntity(pos);

        if (hostTile instanceof IDuct<?, ?> duct) {
            this.baseAttachment = duct.getAttachment(side);
        } else {
            this.baseAttachment = null;
        }
    }

    @Override
    public boolean stillValid(Player player) {

        return baseAttachment != null && hostTile != null && !hostTile.isRemoved();
    }

    protected static int getCenteredContentX(int contentWidth) {

        return (GUI_WIDTH - contentWidth) / 2;
    }

    protected static int getCenteredServoFilterX(int rowSize) {

        return getCenteredContentX(rowSize * FILTER_SLOT_SPACING + SERVO_FILTER_BUTTON_GAP + SERVO_FILTER_BUTTON_WIDTH);
    }

}
