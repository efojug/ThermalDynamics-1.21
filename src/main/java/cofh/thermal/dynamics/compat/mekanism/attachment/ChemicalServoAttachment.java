package cofh.thermal.dynamics.compat.mekanism.attachment;

import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.attachment.IRedstoneControllableAttachment;
import cofh.thermal.dynamics.common.attachment.RedstoneControlLogic;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGrid;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.jetbrains.annotations.Nullable;

import static cofh.lib.util.constants.NBTTags.TAG_AMOUNT;
import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;

public class ChemicalServoAttachment implements IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.servo");
    public static final long TRANSFER = 50;
    public static final long MAX_TRANSFER = 1_000;

    protected final IDuct<?, ?> duct;
    protected final Direction side;
    protected ChemicalFilter filter;
    protected final RedstoneControlLogic rsControl = new RedstoneControlLogic(this);
    protected long amountTransfer = TRANSFER;
    protected IChemicalHandler gridCap;
    protected IChemicalHandler externalCap;

    public ChemicalServoAttachment(IDuct<?, ?> duct, Direction side) {

        this.duct = duct;
        this.side = side;
        filter = new ChemicalFilter(15);
    }

    @Override
    public IDuct<?, ?> duct() {

        return duct;
    }

    @Override
    public Direction side() {

        return side;
    }

    public long getTransfer() {

        return TRANSFER;
    }

    @Override
    public void invalidate() {

        gridCap = null;
        externalCap = null;
    }

    @Override
    public IAttachment read(CompoundTag nbt) {

        return read(nbt, world() == null ? null : world().registryAccess());
    }

    @Override
    public IAttachment read(CompoundTag nbt, HolderLookup.Provider provider) {

        if (nbt.isEmpty()) {
            return this;
        }
        amountTransfer = Math.clamp(nbt.getLong(TAG_AMOUNT), 0, MAX_TRANSFER);
        if (provider != null) {
            filter.read(nbt, provider);
        }
        rsControl.read(nbt);
        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        return write(nbt, world() == null ? null : world().registryAccess());
    }

    @Override
    public CompoundTag write(CompoundTag nbt, HolderLookup.Provider provider) {

        nbt.putString(TAG_TYPE, SERVO);
        nbt.putLong(TAG_AMOUNT, amountTransfer);
        if (provider != null) {
            filter.write(nbt, provider);
        }
        rsControl.write(nbt);
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
            BlockPos target = pos().relative(side);
            BlockEntity targetTile = world().getBlockEntity(target);
            if (targetTile != null) {
                externalCap = world().getCapability(CHEMICAL_HANDLER, target, targetTile.getBlockState(), targetTile, side.getOpposite());
            }
        }
        if (gridCap == null || externalCap == null) {
            return;
        }
        amountTransfer = Math.min(amountTransfer, MAX_TRANSFER - TRANSFER) + TRANSFER;
        ChemicalStack extracted = extractFiltered(externalCap, amountTransfer, Action.SIMULATE);
        if (extracted.isEmpty()) {
            return;
        }
        long accepted = extracted.getAmount() - gridCap.insertChemical(extracted, Action.SIMULATE).getAmount();
        if (accepted <= 0) {
            return;
        }
        ChemicalStack actual = extractFiltered(externalCap, accepted, Action.EXECUTE);
        if (actual.isEmpty()) {
            return;
        }
        long inserted = actual.getAmount() - gridCap.insertChemical(actual, Action.EXECUTE).getAmount();
        amountTransfer = Math.max(0, amountTransfer - inserted);
    }

    protected ChemicalStack extractFiltered(IChemicalHandler handler, long amount, Action action) {

        for (int tank = 0; tank < handler.getChemicalTanks(); ++tank) {
            ChemicalStack contained = handler.getChemicalInTank(tank);
            if (!contained.isEmpty() && ChemicalFilterHelper.valid(filter, contained)) {
                return handler.extractChemical(tank, amount, action);
            }
        }
        return ChemicalStack.EMPTY;
    }

    @Override
    public boolean needsTick() {

        return true;
    }

    @Override
    public ItemStack getItem() {

        return new ItemStack(ITEMS.get(ID_SERVO_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? SERVO_ATTACHMENT_ACTIVE_LOC : SERVO_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {

        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {

        return new ChemicalServoAttachmentMenu(id, player.level(), pos(), side, inventory, player);
    }

    @Override
    @SuppressWarnings ("unchecked")
    public <T, C> T wrapGridCapability(BlockCapability<T, C> capability, T gridCapability) {

        if (capability == CHEMICAL_HANDLER && gridCapability instanceof IChemicalHandler handler) {
            return (T) new OutputDisabledChemicalHandler(handler);
        }
        return gridCapability;
    }

    @Override
    @SuppressWarnings ("unchecked")
    public <T, C> T wrapExternalCapability(BlockCapability<T, C> capability, T externalCapability) {

        if (capability == CHEMICAL_HANDLER && externalCapability instanceof IChemicalHandler handler) {
            return (T) new InputDisabledChemicalHandler(handler);
        }
        return externalCapability;
    }

    public ChemicalFilter getChemicalFilter() {

        return filter;
    }

    public void setFilterChemical(int slot, ChemicalStack chemical) {

        filter.setChemical(slot, chemical);
        onControlUpdate();
    }

    @Override
    public FriendlyByteBuf getConfigPacket(FriendlyByteBuf buffer) {

        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());
        return buffer;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {

        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
    }

    @Override
    public FriendlyByteBuf getControlPacket(FriendlyByteBuf buffer) {

        rsControl.writeToBuffer(buffer);
        getConfigPacket(buffer);
        writeFilterToBuffer(buffer);
        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {

        rsControl.readFromBuffer(buffer);
        handleConfigPacket(buffer);
        readFilterFromBuffer(buffer);
    }

    @Override
    public RedstoneControlLogic redstoneControl() {

        return rsControl;
    }

    @Override
    public void readConveyableData(Player player, CompoundTag tag) {

        rsControl.readSettings(tag);
        filter.read(tag, player.level().registryAccess());
        onControlUpdate();
    }

    @Override
    public void writeConveyableData(Player player, CompoundTag tag) {

        rsControl.writeSettings(tag);
        filter.write(tag, player.level().registryAccess());
    }

    protected void writeFilterToBuffer(FriendlyByteBuf buffer) {

        buffer.writeByte(filter.size());
        for (ChemicalStack chemical : filter.getChemicals()) {
            ChemicalStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buffer, chemical);
        }
    }

    protected void readFilterFromBuffer(FriendlyByteBuf buffer) {

        int size = Byte.toUnsignedInt(buffer.readByte());
        java.util.List<ChemicalStack> chemicals = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            chemicals.add(ChemicalStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buffer));
        }
        filter.setChemicals(chemicals);
    }

    private static class OutputDisabledChemicalHandler implements IChemicalHandler {

        private final IChemicalHandler wrapped;

        private OutputDisabledChemicalHandler(IChemicalHandler wrapped) {

            this.wrapped = wrapped;
        }

        @Override public int getChemicalTanks() { return wrapped.getChemicalTanks(); }
        @Override public ChemicalStack getChemicalInTank(int tank) { return wrapped.getChemicalInTank(tank); }
        @Override public void setChemicalInTank(int tank, ChemicalStack stack) { wrapped.setChemicalInTank(tank, stack); }
        @Override public long getChemicalTankCapacity(int tank) { return wrapped.getChemicalTankCapacity(tank); }
        @Override public boolean isValid(int tank, ChemicalStack stack) { return false; }
        @Override public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) { return stack; }
        @Override public ChemicalStack extractChemical(int tank, long amount, Action action) { return ChemicalStack.EMPTY; }
    }

    private class InputDisabledChemicalHandler implements IChemicalHandler {

        private final IChemicalHandler wrapped;

        private InputDisabledChemicalHandler(IChemicalHandler wrapped) {

            this.wrapped = wrapped;
        }

        @Override public int getChemicalTanks() { return wrapped.getChemicalTanks(); }
        @Override public ChemicalStack getChemicalInTank(int tank) { return wrapped.getChemicalInTank(tank); }
        @Override public void setChemicalInTank(int tank, ChemicalStack stack) { wrapped.setChemicalInTank(tank, stack); }
        @Override public long getChemicalTankCapacity(int tank) { return wrapped.getChemicalTankCapacity(tank); }
        @Override public boolean isValid(int tank, ChemicalStack stack) { return false; }
        @Override public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) { return stack; }
        @Override public ChemicalStack extractChemical(int tank, long amount, Action action) {
            ChemicalStack contained = wrapped.getChemicalInTank(tank);
            return rsControl.getState() && ChemicalFilterHelper.valid(filter, contained) ? wrapped.extractChemical(tank, amount, action) : ChemicalStack.EMPTY;
        }
    }

}
