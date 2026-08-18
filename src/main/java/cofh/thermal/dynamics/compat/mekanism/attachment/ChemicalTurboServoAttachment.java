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
        if (gridCap == null && duct.getGrid() instanceof ChemicalGrid grid) {
            gridCap = grid;
        }
        if (externalCap == null && world() != null) {
            net.minecraft.core.BlockPos target = pos().relative(side);
            net.minecraft.world.level.block.entity.BlockEntity targetTile = world().getBlockEntity(target);
            if (targetTile != null) {
                externalCap = world().getCapability(cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER,
                        target, targetTile.getBlockState(), targetTile, side.getOpposite());
            }
        }
        if (gridCap == null || externalCap == null) {
            return;
        }
        for (int tank = 0; tank < externalCap.getChemicalTanks(); ++tank) {
            ChemicalStack contained = externalCap.getChemicalInTank(tank);
            if (contained.isEmpty() || !ChemicalFilterHelper.valid(filter, contained)) {
                continue;
            }
            ChemicalStack simulated = externalCap.extractChemical(tank, Long.MAX_VALUE, Action.SIMULATE);
            long accepted = simulated.getAmount() - gridCap.insertChemical(simulated, Action.SIMULATE).getAmount();
            if (accepted <= 0) {
                continue;
            }
            ChemicalStack actual = externalCap.extractChemical(tank, accepted, Action.EXECUTE);
            if (!actual.isEmpty()) {
                gridCap.insertChemical(actual, Action.EXECUTE);
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
