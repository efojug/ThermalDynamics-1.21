package cofh.thermal.dynamics.client.gui.attachment;

import cofh.core.client.gui.ContainerScreenCoFH;
import cofh.core.client.gui.element.ElementButton;
import cofh.core.client.gui.element.SimpleTooltip;
import cofh.core.client.gui.element.panel.RSControlPanel;
import cofh.core.util.filter.IFilterOptions;
import cofh.thermal.dynamics.common.attachment.IRedstoneControllableAttachment;
import cofh.thermal.dynamics.common.inventory.attachment.AttachmentMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static cofh.core.util.helpers.GuiHelper.createSlot;
import static cofh.lib.util.Constants.PATH_GUI;
import static cofh.lib.util.helpers.SoundHelper.playClickSound;

/**
 * Shared layout and controls for duct servos. Concrete screens only provide
 * their filter-entry renderer and type-specific status elements.
 */
public abstract class ServoAttachmentScreen<M extends AttachmentMenu & IFilterOptions> extends ContainerScreenCoFH<M> {

    public static final ResourceLocation TEXTURE = ResourceLocation.parse(PATH_GUI + "generic.png");
    public static final String TEX_DENY_LIST = PATH_GUI + "filters/filter_deny_list.png";
    public static final String TEX_ALLOW_LIST = PATH_GUI + "filters/filter_allow_list.png";
    public static final String TEX_IGNORE_NBT = PATH_GUI + "filters/filter_ignore_nbt.png";
    public static final String TEX_USE_NBT = PATH_GUI + "filters/filter_use_nbt.png";

    protected final IRedstoneControllableAttachment attachment;

    protected ServoAttachmentScreen(M menu, Inventory inventory, Component title, IRedstoneControllableAttachment attachment) {

        super(menu, inventory, title);
        texture = TEXTURE;
        this.attachment = attachment;
    }

    @Override
    public void init() {

        super.init();
        addPanel(new RSControlPanel(this, attachment));
        for (int i = 0; i < getFilterSize(); ++i) {
            Slot slot = menu.slots.get(i);
            addElement(createSlot(this, slot.x, slot.y));
            addFilterElement(slot, i);
        }
        addServoElements();
        addFilterButtons();
    }

    protected abstract int getFilterSize();

    protected int getFilterButtonX() {

        int filterSize = getFilterSize();
        if (filterSize == 0) {
            return 105;
        }
        int rows = Math.min(Math.max(filterSize / 3, 1), 3);
        int rowSize = filterSize / rows;

        return menu.slots.get(0).x + rowSize * 18 + 7;
    }

    protected void addFilterElement(Slot slot, int index) {

    }

    protected void addServoElements() {

    }

    protected void addFilterButtons() {

        int filterButtonX = getFilterButtonX();

        addElement(new ElementButton(this, filterButtonX, 22) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {

                menu.setAllowList(true);
                playClickSound(0.7F);
                return true;
            }
        }.setSize(20, 20).setTexture(TEX_DENY_LIST, 40, 20)
                .setTooltipFactory(new SimpleTooltip(Component.translatable("info.cofh.filter.allowlist.0")))
                .setVisible(() -> !menu.getAllowList()));

        addElement(new ElementButton(this, filterButtonX, 22) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {

                menu.setAllowList(false);
                playClickSound(0.4F);
                return true;
            }
        }.setSize(20, 20).setTexture(TEX_ALLOW_LIST, 40, 20)
                .setTooltipFactory(new SimpleTooltip(Component.translatable("info.cofh.filter.allowlist.1")))
                .setVisible(menu::getAllowList));

        addElement(new ElementButton(this, filterButtonX, 44) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {

                menu.setCheckNBT(true);
                playClickSound(0.7F);
                return true;
            }
        }.setSize(20, 20).setTexture(TEX_IGNORE_NBT, 40, 20)
                .setTooltipFactory(new SimpleTooltip(Component.translatable("info.cofh.filter.checkNBT.0")))
                .setVisible(() -> !menu.getCheckNBT()));

        addElement(new ElementButton(this, filterButtonX, 44) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {

                menu.setCheckNBT(false);
                playClickSound(0.4F);
                return true;
            }
        }.setSize(20, 20).setTexture(TEX_USE_NBT, 40, 20)
                .setTooltipFactory(new SimpleTooltip(Component.translatable("info.cofh.filter.checkNBT.1")))
                .setVisible(menu::getCheckNBT));
    }

}
