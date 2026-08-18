package cofh.thermal.dynamics.client.gui.attachment;

import cofh.core.client.gui.element.ElementFluid;
import cofh.thermal.dynamics.common.inventory.attachment.FluidServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;

public class FluidServoAttachmentScreen extends ServoAttachmentScreen<FluidServoAttachmentMenu> {

    public FluidServoAttachmentScreen(FluidServoAttachmentMenu container, Inventory inv, Component titleIn) {

        super(container, inv, titleIn, container.attachment);
        info = generatePanelInfo("info.thermal.fluid_servo_attachment");
    }

    @Override
    protected int getFilterSize() {

        return menu.getFilterSize();
    }

    @Override
    protected void addFilterElement(Slot slot, int index) {

        addElement(new ElementFluid(this, slot.x, slot.y).setFluid(() -> menu.getFilterStacks().get(index)).setSize(16, 16));
    }

}
