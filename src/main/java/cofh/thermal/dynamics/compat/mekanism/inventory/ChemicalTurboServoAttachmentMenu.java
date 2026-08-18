package cofh.thermal.dynamics.compat.mekanism.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_TURBO_SERVO_ATTACHMENT_CONTAINER;

public class ChemicalTurboServoAttachmentMenu extends ChemicalServoAttachmentMenu {

    public ChemicalTurboServoAttachmentMenu(int id, Level world, BlockPos pos, Direction side, Inventory inventory, Player player) {

        super(CHEMICAL_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), id, world, pos, side, inventory, player);
    }

}
