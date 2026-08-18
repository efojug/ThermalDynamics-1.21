package cofh.thermal.dynamics.client.gui.attachment;

import cofh.core.client.gui.element.ElementFluid;
import cofh.thermal.dynamics.common.inventory.attachment.FluidTurboServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;
public class FluidTurboServoAttachmentScreen extends ServoAttachmentScreen<FluidTurboServoAttachmentMenu> {

    public FluidTurboServoAttachmentScreen(FluidTurboServoAttachmentMenu container, Inventory inv, Component titleIn) {

        super(container, inv, titleIn, container.attachment);
        info = generatePanelInfo("info.thermal.fluid_turbo_servo_attachment");
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
