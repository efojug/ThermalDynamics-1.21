package cofh.thermal.dynamics.client.gui.attachment;

import cofh.thermal.dynamics.common.inventory.attachment.ItemTurboServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;

public class ItemTurboServoAttachmentScreen extends ItemServoAttachmentScreenBase<ItemTurboServoAttachmentMenu> {

    public ItemTurboServoAttachmentScreen(ItemTurboServoAttachmentMenu menu, Inventory inventory, Component title) {

        super(menu, inventory, title);
        info = generatePanelInfo("info.thermal.item_filter_attachment");
    }

}
