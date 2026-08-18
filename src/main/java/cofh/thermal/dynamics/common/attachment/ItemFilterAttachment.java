package cofh.thermal.dynamics.common.attachment;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.config.TDynConfig;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.FILTER;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_FILTER_ATTACHMENT;

/**
 * The item duct's filter item retains the item's active extraction behavior.
 */
public class ItemFilterAttachment extends ItemServoAttachment {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.filter");

    public ItemFilterAttachment(IDuct<?, ?> duct, Direction side) {

        super(duct, side);
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        super.write(nbt);
        nbt.putString(TAG_TYPE, FILTER);
        return nbt;
    }

    @Override
    public void tick() {

        if (!rsControl.getState()) {
            return;
        }
        int maxStacks = TDynConfig.itemFilterStacksPerTick;
        int sent = sendStuffedItems(maxStacks);
        for (; sent < maxStacks; ++sent) {
            if (!pullAndRoute()) {
                break;
            }
        }
    }

    @Override
    public ItemStack getItem() {

        return new ItemStack(ITEMS.get(ID_FILTER_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? FILTER_ATTACHMENT_ACTIVE_LOC : FILTER_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {

        return DISPLAY_NAME;
    }

}
