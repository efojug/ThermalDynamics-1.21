package cofh.thermal.dynamics.client.gui.attachment;

import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static cofh.core.util.helpers.GuiHelper.generatePanelInfo;

public class ItemServoAttachmentScreen extends ItemServoAttachmentScreenBase<ItemServoAttachmentMenu> {

    public ItemServoAttachmentScreen(ItemServoAttachmentMenu menu, Inventory inventory, Component title) {

        super(menu, inventory, title);
    }

}

class ItemServoAttachmentScreenBase<M extends ItemServoAttachmentMenu> extends ServoAttachmentScreen<M> {

    protected ItemServoAttachmentScreenBase(M menu, Inventory inventory, Component title) {

        super(menu, inventory, title, menu.attachment);
        info = generatePanelInfo("info.thermal.item_filter_attachment");
    }

    @Override
    protected int getFilterSize() {

        return menu.getFilterSize();
    }

}
