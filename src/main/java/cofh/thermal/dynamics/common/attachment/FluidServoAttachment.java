package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseFluidFilter;
import cofh.core.util.filter.IFilter;
import cofh.lib.api.IConveyableData;
import cofh.thermal.dynamics.ThermalDynamics;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.inventory.attachment.FluidServoAttachmentMenu;
import cofh.thermal.dynamics.common.grid.fluid.FluidGrid;
import cofh.thermal.dynamics.common.grid.OverflowBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

import static cofh.lib.util.Constants.BUCKET_VOLUME;
import static cofh.lib.util.constants.NBTTags.TAG_AMOUNT;
import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.SERVO_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_SERVO_ATTACHMENT;
import static cofh.thermal.dynamics.init.registries.TDynIDs.SERVO;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

public class FluidServoAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.servo");

    public static final int TRANSFER = 50;
    public static final int MAX_TRANSFER = BUCKET_VOLUME;

    protected final IDuct<?, ?> duct;
    protected final Direction side;

    public int amountTransfer = TRANSFER;

    protected BaseFluidFilter filter = new BaseFluidFilter(15);
    protected RedstoneControlLogic rsControl = new RedstoneControlLogic(this);
    protected net.neoforged.neoforge.capabilities.BlockCapabilityCache<IFluidHandler, Direction> externalFluidCache;
    private long externalCacheGeneration;

    public FluidServoAttachment(IDuct<?, ?> duct, Direction side) {

        this.duct = duct;
        this.side = side;
    }

    public final int getTransfer() {

        return TRANSFER;
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

        externalFluidCache = null;
        ++externalCacheGeneration;
    }

    /** Cached external fluid handler lookup; avoids a block entity + capability query every tick. Nullable. */
    protected IFluidHandler externalHandler() {

        if (!(world() instanceof ServerLevel serverLevel)) {
            return null;
        }
        if (externalFluidCache == null) {
            long generation = ++externalCacheGeneration;
            externalFluidCache = net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, serverLevel,
                    pos().relative(side), side.getOpposite(),
                    () -> generation == externalCacheGeneration && world() instanceof ServerLevel,
                    () -> { });
        }
        return externalFluidCache.getCapability();
    }

    @Override
    public IAttachment read(CompoundTag nbt) {

        if (nbt.isEmpty()) {
            return this;
        }
        amountTransfer = nbt.getInt(TAG_AMOUNT);

        filter.read(nbt);
        rsControl.read(nbt);

        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        nbt.putString(TAG_TYPE, SERVO);
        nbt.putInt(TAG_AMOUNT, amountTransfer);

        filter.write(nbt);
        rsControl.write(nbt);

        return nbt;
    }

    @Override
    public void tick() {

        if (!rsControl.getState()) {
            return;
        }
        if (!(duct.getGrid() instanceof FluidGrid grid)) {
            return;
        }
        amountTransfer = Math.min(amountTransfer + TRANSFER, MAX_TRANSFER);
        int moved = transferFromExternal(duct, side, filter, grid, amountTransfer, externalHandler());
        amountTransfer = Math.max(0, amountTransfer - moved);
    }

    protected static int transferFromExternal(IDuct<?, ?> duct, Direction side, IFilter filter,
            IFluidHandler gridCap, int maxAmount, IFluidHandler external) {

        if (!(gridCap instanceof FluidGrid grid) || maxAmount <= 0 || !(duct.getHostWorld() instanceof ServerLevel)) {
            return 0;
        }
        if (!grid.getOverflowBuffer().isEmpty()) {
            drainOverflowIntoGrid(grid, Integer.MAX_VALUE);
            if (!grid.getOverflowBuffer().isEmpty()) {
                return 0;
            }
        }
        if (external == null) {
            return 0;
        }
        int limit = (int) Math.min(maxAmount, Math.min(grid.overflowHeadroom(), Integer.MAX_VALUE));
        if (limit <= 0) {
            return 0;
        }
        FluidStack simulated = external.drain(limit, SIMULATE);
        if (simulated.isEmpty() || !filter.valid(simulated) || !grid.getOverflowBuffer().compatibleWith(simulated)) {
            return 0;
        }
        int accepted = Math.min(simulated.getAmount(), Math.max(0, grid.fill(simulated, SIMULATE)));
        if (accepted <= 0) {
            return 0;
        }
        FluidStack request = simulated.copyWithAmount(accepted);
        FluidStack extracted = external.drain(request, EXECUTE);
        if (extracted.isEmpty()) {
            return 0;
        }
        if (!FluidStack.isSameFluidSameComponents(extracted, request)
                || extracted.getAmount() > request.getAmount() || !filter.valid(extracted)) {
            restoreToSource(external, extracted);
            return 0;
        }
        int inserted = Math.max(0, grid.fill(extracted, EXECUTE));
        int leftover = extracted.getAmount() - inserted;
        if (leftover > 0) {
            long parked = grid.getOverflowBuffer().add(extracted, leftover);
            grid.noteOverflowParked();
            if (parked < leftover) {
                ThermalDynamics.LOG.warn("Fluid overflow buffer rejected {} mB", leftover - parked);
            }
        }
        return extracted.getAmount();
    }

    private static int drainOverflowIntoGrid(FluidGrid grid, int maxAmount) {

        OverflowBuffer<FluidStack> buffer = grid.getOverflowBuffer();
        FluidStack offered = buffer.peek(maxAmount);
        if (offered.isEmpty()) {
            return 0;
        }
        int accepted = Math.max(0, grid.replayOverflow(offered));
        buffer.drain(accepted);
        return accepted;
    }

    private static void restoreToSource(IFluidHandler external, FluidStack stack) {

        int restored = Math.max(0, external.fill(stack, EXECUTE));
        if (restored < stack.getAmount()) {
            ThermalDynamics.LOG.warn("Fluid servo could not return {} mB to a non-compliant source", stack.getAmount() - restored);
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
    public AbstractContainerMenu createMenu(int i, Inventory inventory, Player player) {

        return new FluidServoAttachmentMenu(i, player.level(), pos(), side, inventory, player);
    }

    @Nullable
    @Override
    public <T, C> T wrapGridCapability(BlockCapability<T, C> capability, T gridCapIn) {

        if (capability == Capabilities.FluidHandler.BLOCK) {
            if (gridCapIn instanceof IFluidHandler handler) {
                return (T) new WrappedGridFluidHandler(handler);
            }
        }
        return gridCapIn;
    }

    @Nullable
    @Override
    public <T, C> T wrapExternalCapability(BlockCapability<T, C> capability, T extCapIn) {

        if (capability == Capabilities.FluidHandler.BLOCK) {
            if (extCapIn instanceof IFluidHandler handler) {
                return (T) new WrappedExternalFluidHandler(handler, e -> rsControl.getState() && filter.valid(e));
            }
        }
        return extCapIn;
    }

    // region IFilterableAttachment
    @Override
    public IFilter getFilter() {

        return filter;
    }
    // endregion

    // region IPacketHandlerAttachment
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

        buffer.writeBoolean(filter.getAllowList());
        buffer.writeBoolean(filter.getCheckNBT());

        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {

        rsControl.readFromBuffer(buffer);

        filter.setAllowList(buffer.readBoolean());
        filter.setCheckNBT(buffer.readBoolean());
    }
    // endregion

    // region IRedstoneControllableAttachment
    @Override
    public RedstoneControlLogic redstoneControl() {

        return rsControl;
    }
    // endregion

    // region IConveyableData
    @Override
    public void readConveyableData(Player player, CompoundTag tag) {

        rsControl.readSettings(tag);
        filter.read(tag);

        onControlUpdate();
    }

    @Override
    public void writeConveyableData(Player player, CompoundTag tag) {

        rsControl.writeSettings(tag);
        filter.write(tag);
    }
    // endregion

    // region GRID WRAPPER CLASS
    private static class WrappedGridFluidHandler implements IFluidHandler {

        protected IFluidHandler wrappedHandler;

        public WrappedGridFluidHandler(IFluidHandler wrappedHandler) {

            this.wrappedHandler = wrappedHandler;
        }

        @Override
        public int getTanks() {

            return wrappedHandler.getTanks();
        }

        @NotNull
        @Override
        public FluidStack getFluidInTank(int tank) {

            return wrappedHandler.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {

            return wrappedHandler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {

            return wrappedHandler.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {

            return 0;
        }

        @NotNull
        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {

            return FluidStack.EMPTY;
        }

        @NotNull
        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {

            return FluidStack.EMPTY;
        }

    }
    // endregion

    // region EXTERNAL WRAPPER CLASS
    private static class WrappedExternalFluidHandler implements IFluidHandler {

        protected IFluidHandler wrappedHandler;

        protected Predicate<FluidStack> validator;

        public WrappedExternalFluidHandler(IFluidHandler wrappedHandler, Predicate<FluidStack> validator) {

            this.wrappedHandler = wrappedHandler;
            this.validator = validator;
        }

        @Override
        public int getTanks() {

            return wrappedHandler.getTanks();
        }

        @NotNull
        @Override
        public FluidStack getFluidInTank(int tank) {

            return wrappedHandler.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {

            return wrappedHandler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {

            return validator.test(stack) && wrappedHandler.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {

            return 0;
            // return validator.test(resource) ? wrappedHandler.fill(resource, action) : 0;
        }

        @NotNull
        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {

            return validator.test(resource) ? wrappedHandler.drain(resource, action) : FluidStack.EMPTY;
        }

        @NotNull
        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {

            return validator.test(wrappedHandler.drain(maxDrain, SIMULATE)) ? wrappedHandler.drain(maxDrain, action) : FluidStack.EMPTY;
        }

    }
    // endregion
}
