package cofh.thermal.dynamics.common.inventory.attachment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import static cofh.thermal.dynamics.init.registries.TDynContainers.ITEM_TURBO_SERVO_ATTACHMENT_CONTAINER;

public class ItemTurboServoAttachmentMenu extends ItemServoAttachmentMenu {

    public ItemTurboServoAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(ITEM_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);
    }

}
