package cofh.thermal.dynamics.compat.mekanism.client;

import cofh.thermal.dynamics.client.gui.attachment.ServoAttachmentScreen;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;

public class ChemicalServoAttachmentScreen extends ChemicalServoAttachmentScreenBase<ChemicalServoAttachmentMenu> {

    public ChemicalServoAttachmentScreen(ChemicalServoAttachmentMenu menu, Inventory inventory, Component title) {

        super(menu, inventory, title);
    }

}

class ChemicalServoAttachmentScreenBase<M extends ChemicalServoAttachmentMenu> extends ServoAttachmentScreen<M> {

    protected ChemicalServoAttachmentScreenBase(M menu, Inventory inventory, Component title) {

        super(menu, inventory, title, menu.attachment);
        info = generatePanelInfo("info.thermal.chemical_servo_attachment");
    }

    @Override
    protected int getFilterSize() {

        return menu.getFilterSize();
    }

    @Override
    protected void addFilterElement(Slot slot, int index) {

        addElement(new ChemicalFilterElement(this, slot.x, slot.y).setChemical(() -> menu.getFilterStacks().get(index)).setSize(16, 16));
    }

}
