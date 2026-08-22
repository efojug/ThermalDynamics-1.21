package cofh.thermal.dynamics.compat.mekanism.attachment;

import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.ThermalDynamics;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.attachment.IRedstoneControllableAttachment;
import cofh.thermal.dynamics.common.attachment.RedstoneControlLogic;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGrid;
import cofh.thermal.dynamics.common.grid.OverflowBuffer;
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
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
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
    protected BlockCapabilityCache<IChemicalHandler, Direction> externalChemicalCache;
    private long externalCacheGeneration;

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

    @Override
    public void invalidate() {

        gridCap = null;
        ++externalCacheGeneration;
        externalChemicalCache = null;
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
        if (!(gridCap instanceof ChemicalGrid grid)) {
            return;
        }
        IChemicalHandler external = externalHandler();
        if (external == null) return;
        amountTransfer = Math.min(amountTransfer, MAX_TRANSFER - TRANSFER) + TRANSFER;
        long moved = 0;
        for (int tank = 0; tank < external.getChemicalTanks(); ++tank) {
            moved = transferTank(external, tank, amountTransfer);
            if (moved > 0) break;
        }
        amountTransfer = Math.max(0, amountTransfer - moved);
    }

    @Nullable
    protected IChemicalHandler externalHandler() {

        if (!(world() instanceof ServerLevel serverLevel)) return null;
        if (externalChemicalCache == null) {
            long generation = ++externalCacheGeneration;
            externalChemicalCache = BlockCapabilityCache.create(CHEMICAL_HANDLER, serverLevel,
                    pos().relative(side), side.getOpposite(),
                    () -> generation == externalCacheGeneration && world() instanceof ServerLevel,
                    () -> { });
        }
        return externalChemicalCache.getCapability();
    }

    protected long transferTank(IChemicalHandler external, int tank, long limit) {

        if (!(duct.getGrid() instanceof ChemicalGrid grid) || limit <= 0) return 0;
        if (!grid.getOverflowBuffer().isEmpty()) {
            drainOverflowIntoGrid(grid, Long.MAX_VALUE);
            if (!grid.getOverflowBuffer().isEmpty()) return 0;
        }
        long budget = Math.min(limit, grid.overflowHeadroom());
        if (budget <= 0) return 0;
        ChemicalStack simulated = external.extractChemical(tank, budget, Action.SIMULATE);
        if (simulated.isEmpty() || !ChemicalFilterHelper.valid(filter, simulated)
                || !grid.getOverflowBuffer().compatibleWith(simulated)) return 0;
        long accepted = simulated.getAmount() - grid.insertChemical(simulated, Action.SIMULATE).getAmount();
        if (accepted <= 0) return 0;
        ChemicalStack request = simulated.copyWithAmount(accepted);
        ChemicalStack actual = external.extractChemical(tank, accepted, Action.EXECUTE);
        if (actual.isEmpty()) return 0;
        if (!ChemicalStack.isSameChemical(actual, request) || actual.getAmount() > accepted
                || !ChemicalFilterHelper.valid(filter, actual)) {
            restoreToSource(external, tank, actual);
            return 0;
        }
        ChemicalStack remainder = grid.insertChemical(actual, Action.EXECUTE);
        if (!remainder.isEmpty()) {
            long parked = grid.getOverflowBuffer().add(remainder);
            grid.auditNoteIn(parked);
            grid.noteOverflowParked();
            if (parked < remainder.getAmount()) {
                ThermalDynamics.LOG.warn("Chemical overflow buffer rejected {}", remainder.getAmount() - parked);
            }
        }
        return actual.getAmount();
    }

    private static long drainOverflowIntoGrid(ChemicalGrid grid, long maxAmount) {

        OverflowBuffer<ChemicalStack> buffer = grid.getOverflowBuffer();
        ChemicalStack offered = buffer.peek(maxAmount);
        if (offered.isEmpty()) return 0;
        ChemicalStack remainder = grid.replayOverflow(offered);
        long accepted = offered.getAmount() - remainder.getAmount();
        buffer.drain(accepted);
        return accepted;
    }

    private static void restoreToSource(IChemicalHandler external, int tank, ChemicalStack stack) {

        ChemicalStack remainder = external.insertChemical(tank, stack, Action.EXECUTE);
        if (!remainder.isEmpty()) {
            ThermalDynamics.LOG.warn("Chemical servo could not return {} to a non-compliant source", remainder.getAmount());
        }
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
    public int configPacketSize() {

        return 2;
    }

    @Override
    public void handleConfigPacket(FriendlyByteBuf buffer) {

        boolean allowList = buffer.readBoolean();
        boolean checkNbt = buffer.readBoolean();
        filter.setAllowList(allowList);
        filter.setCheckNBT(checkNbt);
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
        if (size != filter.size()) {
            throw new IllegalArgumentException("Invalid chemical filter size: " + size);
        }
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
