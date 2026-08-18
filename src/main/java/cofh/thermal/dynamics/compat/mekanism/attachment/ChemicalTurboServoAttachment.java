package cofh.thermal.dynamics.compat.mekanism.attachment;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGrid;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalTurboServoAttachmentMenu;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.TURBO_SERVO_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.TURBO_SERVO_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_TURBO_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.TURBO_SERVO;

public class ChemicalTurboServoAttachment extends ChemicalServoAttachment {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.turbo_servo");

    public ChemicalTurboServoAttachment(IDuct<?, ?> duct, Direction side) {

        super(duct, side);
        filter = new ChemicalFilter(1);
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        super.write(nbt);
        nbt.putString(TAG_TYPE, TURBO_SERVO);
        return nbt;
    }

    @Override
    public CompoundTag write(CompoundTag nbt, HolderLookup.Provider provider) {

        super.write(nbt, provider);
        nbt.putString(TAG_TYPE, TURBO_SERVO);
        return nbt;
    }

    @Override
    public void tick() {

        if (!rsControl.getState()) {
            return;
        }
        if (!(duct.getGrid() instanceof ChemicalGrid grid)) {
            return;
        }
        IChemicalHandler external = externalHandler();
        if (external == null) return;
        long remaining = grid.overflowHeadroom();
        for (int tank = 0; tank < external.getChemicalTanks() && remaining > 0; ++tank) {
            long moved = transferTank(external, tank, remaining);
            remaining -= moved;
            if (moved == 0 && !grid.getOverflowBuffer().isEmpty()) {
                break;
            }
        }
    }

    @Override
    public ItemStack getItem() {

        return new ItemStack(ITEMS.get(ID_TURBO_SERVO_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? TURBO_SERVO_ATTACHMENT_ACTIVE_LOC : TURBO_SERVO_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {

        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {

        return new ChemicalTurboServoAttachmentMenu(id, player.level(), pos(), side, inventory, player);
    }

}
