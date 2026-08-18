package cofh.thermal.dynamics.compat.mekanism.client;

import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalTurboServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;

public class ChemicalTurboServoAttachmentScreen extends ChemicalServoAttachmentScreenBase<ChemicalTurboServoAttachmentMenu> {

    public ChemicalTurboServoAttachmentScreen(ChemicalTurboServoAttachmentMenu menu, Inventory inventory, Component title) {

        super(menu, inventory, title);
        info = generatePanelInfo("info.thermal.chemical_turbo_servo_attachment");
    }

}
