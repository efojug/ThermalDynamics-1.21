package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.core.common.network.packet.client.ModelUpdatePacket;
import cofh.core.common.network.packet.client.TileRedstonePacket;
import cofh.core.common.network.packet.client.TileStatePacket;
import cofh.lib.api.IConveyableData;
import cofh.lib.api.block.entity.IPacketHandlerTile;
import cofh.lib.api.block.entity.ITileLocation;
import cofh.lib.util.Utils;
import cofh.lib.util.helpers.BlockHelper;
import cofh.thermal.core.common.item.RedprintItem;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridContainer;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.client.model.data.DuctModelData;
import cofh.thermal.dynamics.common.attachment.*;
import cofh.thermal.dynamics.common.grid.Grid;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.lib.util.constants.NBTTags.*;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.*;
import static cofh.thermal.dynamics.client.model.data.DuctModelData.DUCT_MODEL_DATA;

public abstract class DuctBlockEntity<G extends Grid<G, N>, N extends GridNode<G>> extends BlockEntity implements IDuct<G, N>, ITileLocation, IPacketHandlerTile {

    protected final DuctModelData modelData = new DuctModelData();
    // Only available server side.
    @Nullable
    protected G grid = null;
    protected ConnectionType[] connections = {ALLOWED, ALLOWED, ALLOWED, ALLOWED, ALLOWED, ALLOWED};
    protected IAttachment[] attachments = {EmptyAttachment.INSTANCE, EmptyAttachment.INSTANCE, EmptyAttachment.INSTANCE, EmptyAttachment.INSTANCE, EmptyAttachment.INSTANCE, EmptyAttachment.INSTANCE};

    protected boolean clientConnectionStateInitialized;
    protected int redstonePower;

    public DuctBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {

        super(type, pos, state);
        modelData.setNeedsRefresh();
    }

    public boolean attemptConnect(Direction side) {

        if (level == null || Utils.isClientWorld(level)) {
            return false;
        }
        IDuct<?, ?> adjacent = GridHelper.getGridHost(getLevel(), getBlockPos().relative(side));
        if (adjacent instanceof DuctBlockEntity<?, ?> other) {
            IGridContainer gridContainer = IGridContainer.getGrid(level);
            if (gridContainer == null || other.connections[side.getOpposite().ordinal()] == FORCED) {
                return false;
            }
            connections[side.ordinal()] = ALLOWED;
            other.connections[side.getOpposite().ordinal()] = ALLOWED;

            if (!gridContainer.onDuctSideConnected(this, side)) {
                connections[side.ordinal()] = DISABLED;
                other.connections[side.getOpposite().ordinal()] = DISABLED;
                return false;
            }
            setChanged();
            other.setChanged();

            level.invalidateCapabilities(worldPosition);
            level.invalidateCapabilities(other.worldPosition);
            TileStatePacket.sendToClient(this);
            TileStatePacket.sendToClient(other);
        } else {
            connections[side.ordinal()] = ALLOWED;
            setChanged();
            callNeighborStateChange();
            level.invalidateCapabilities(worldPosition);
            TileStatePacket.sendToClient(this);
        }
        return true;
    }

    public boolean attemptDisconnect(Direction side, Player player) {

        if (attemptAttachmentRemove(side, player)) {
            return true;
        }
        if (level == null || Utils.isClientWorld(level)) {
            return false;
        }
        IDuct<?, ?> adjacent = GridHelper.getGridHost(level, getBlockPos().relative(side));
        if (adjacent instanceof DuctBlockEntity<?, ?> other) { // TODO: This should be moved up to IGridHost as a common implementation for (eventual) multiparts.
            IGridContainer gridContainer = IGridContainer.getGrid(level);
            if (gridContainer == null) {
                return false;
            }
            gridContainer.onDuctSideDisconnecting(this, side);

            connections[side.ordinal()] = DISABLED;
            other.connections[side.getOpposite().ordinal()] = DISABLED;

            setChanged();
            other.setChanged();

            level.invalidateCapabilities(worldPosition);
            level.invalidateCapabilities(other.worldPosition);
            TileStatePacket.sendToClient(this);
            TileStatePacket.sendToClient(other);
        } else {
            connections[side.ordinal()] = DISABLED;
            setChanged();
            callNeighborStateChange();
            level.invalidateCapabilities(worldPosition);
            TileStatePacket.sendToClient(this);
        }
        return true;
    }

    public boolean attemptAttachmentInstall(Direction side, Player player, String type) {

        if (attachments[side.ordinal()] != EmptyAttachment.INSTANCE) {
            return false;
        }
        IAttachment attachment = AttachmentRegistry.getAttachment(type, new CompoundTag(), this, side);
        if (attachment == null || attachment == EmptyAttachment.INSTANCE) {
            return false;
        }
        attachments[side.ordinal()] = attachment;
        connections[side.ordinal()] = FORCED;

        ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (offhand.has(DataComponents.CUSTOM_DATA) && offhand.getItem() instanceof RedprintItem) {
            attachmentRedprintInteraction(offhand, side, player);
        }
        setChanged();
        callNeighborStateChange();
        level.invalidateCapabilities(worldPosition);

        // TODO: Send FULL Update Packet
        TileStatePacket.sendToClient(this);
        return true;
    }

    public boolean attemptAttachmentRemove(Direction side, Player player) {

        ItemStack attachmentItem = attachments[side.ordinal()].getItem();
        if (!attachmentItem.isEmpty()) {
            if (player == null || !player.addItem(attachmentItem)) {
                Utils.dropDismantleStackIntoWorld(attachmentItem, level, worldPosition);
            }
            attachments[side.ordinal()] = EmptyAttachment.INSTANCE;
            IDuct<?, ?> adjacent = GridHelper.getGridHost(level, getBlockPos().relative(side));
            connections[side.ordinal()] = adjacent == null ? ALLOWED : DISABLED;
            setChanged();
            callNeighborStateChange();
            level.invalidateCapabilities(worldPosition);

            // TODO: Send FULL Update Packet
            TileStatePacket.sendToClient(this);
            return true;
        }
        return false;
    }

    public boolean attachmentRedprintInteraction(ItemStack stack, Direction side, Player player) {

        if (side != null && attachments[side.ordinal()] instanceof IConveyableData conveyableData) {
            if (!stack.has(DataComponents.CUSTOM_DATA)) {
                CompoundTag tag = new CompoundTag();
                conveyableData.writeConveyableData(player, tag);
                if (tag.isEmpty()) {
                    return false;
                }
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 0.7F);
                return true;
            }
            conveyableData.readConveyableData(player, stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
            player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 0.8F);
            return true;
        }
        return false;
    }

    public boolean openDuctGui(Player player) {

        if (this instanceof MenuProvider provider) {
            player.openMenu(provider, pos());
            return true;
        }
        return false;
    }

    public boolean openAttachmentGui(Direction side, Player player) {

        if (side != null && attachments[side.ordinal()] instanceof MenuProvider provider) {
            AttachmentHelper.openAttachmentScreen((ServerPlayer) player, provider, pos(), side);
            return true;
        }
        return false;
    }

    public void dropAttachments() {

        for (Direction dir : DIRECTIONS) {
            ItemStack attachmentItem = attachments[dir.ordinal()].getItem();
            if (!attachmentItem.isEmpty()) {
                Utils.dropDismantleStackIntoWorld(attachmentItem, level, worldPosition);
            }
        }
    }

    /**
     * To be used only on block dismantle.
     *
     * @param player The player to attempt to return attachments to.
     */
    public void dismantleAttachments(Player player, boolean returnDrops) {

        for (Direction dir : DIRECTIONS) {
            ItemStack attachmentItem = attachments[dir.ordinal()].getItem();
            if (!attachmentItem.isEmpty()) {
                if (!returnDrops || player == null || !player.addItem(attachmentItem)) {
                    Utils.dropDismantleStackIntoWorld(attachmentItem, level, worldPosition);
                }
                attachments[dir.ordinal()] = EmptyAttachment.INSTANCE;
            }
        }
    }

    public void invalidateAttachments() {

        for (IAttachment attachment : attachments) {
            attachment.invalidate();
        }
    }

    public void calcDuctModelDataServer() {

        if (level == null || Utils.isClientWorld(level) || getGrid() == null) {
            return;
        }
        modelData.clearState();
        for (var dir : Direction.values()) {
            if (getGrid().isConnectedTo(worldPosition, worldPosition.relative(dir))) {
                modelData.setInternalConnection(dir, true);
            }
            if (connections[dir.ordinal()] == FORCED) {
                modelData.setExternalConnection(dir, true);
            }
        }
        if (getNode() == null) {
            return;
        }
        for (var dir : getNode().getConnections()) {
            modelData.setExternalConnection(dir, connections[dir.ordinal()] != DISABLED);
        }
    }

    // This is called only in getShape()
    @Nonnull
    public DuctModelData getDuctModelData() {

        if (modelData.needsRefresh()) {
            if (level != null && level.isClientSide()) {
                calcDuctModelDataClient();
            } else {
                calcDuctModelDataServer();
            }
        }
        return modelData;
    }

    protected void callNeighborStateChange() {

        if (level == null) {
            return;
        }
        level.updateNeighborsAt(pos(), block());
    }

    protected abstract boolean canConnectToBlock(Direction dir);

    /**
     * Recomputes the connection state from client-side data (the synced connection array and neighbor block
     * entities). Used to refresh the model data and, via {@link #getDuctModelData()}, the collision shape.
     */
    public void calcDuctModelDataClient() {

        modelData.clearState();
        for (Direction dir : DIRECTIONS) {
            IDuct<?, ?> adjacent = GridHelper.getGridHost(level, getBlockPos().relative(dir));
            boolean adjacentInitialized = !(adjacent instanceof DuctBlockEntity<?, ?> duct) || duct.clientConnectionStateInitialized;
            modelData.setInternalConnection(dir, clientConnectionStateInitialized && adjacentInitialized && adjacent != null &&
                    connections[dir.ordinal()].allowDuctConnection() && adjacent.getConnectionType(dir.getOpposite()).allowDuctConnection() &&
                    canConnectTo(adjacent, dir) && adjacent.canConnectTo(this, dir.getOpposite()));
            modelData.setExternalConnection(dir, canConnectToBlock(dir) || connections[dir.ordinal()] == FORCED);
            modelData.setAttachment(dir, attachments[dir.ordinal()].getTexture());
        }
    }

    @Nonnull
    @Override
    public ModelData getModelData() {

        calcDuctModelDataClient();
        return ModelData.builder()
                .with(DUCT_MODEL_DATA, modelData)
                .build();
    }

    // region NETWORK
    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {

        return ClientboundBlockEntityDataPacket.create(this);
    }

    // REDSTONE
    @Override
    public FriendlyByteBuf getRedstonePacket(FriendlyByteBuf buffer) {

        buffer.writeInt(redstonePower);

        return buffer;
    }

    @Override
    public void handleRedstonePacket(FriendlyByteBuf buffer) {

        int power = buffer.readInt();
        for (IAttachment attachment : attachments) {
            if (attachment instanceof IRedstoneControllableAttachment redstoneControllableAttachment) {
                redstoneControllableAttachment.redstoneControl().setPower(power);
            }
        }
    }

    // STATE
    @Override
    public FriendlyByteBuf getStatePacket(FriendlyByteBuf buffer) {

        modelData.setNeedsRefresh();

        for (ConnectionType connection : connections) {
            buffer.writeByte(connection.ordinal());
        }
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < 6; ++i) {
            CompoundTag attachmentTag = new CompoundTag();
            attachments[i].write(attachmentTag);
            if (!attachmentTag.isEmpty()) {
                tag.put(TAG_ATTACHMENT + i, attachmentTag);
            }
        }
        buffer.writeNbt(tag);

        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {

        for (int i = 0; i < 6; ++i) {
            connections[i] = ConnectionType.VALUES[buffer.readByte()];
        }
        clientConnectionStateInitialized = true;
        CompoundTag tag = buffer.readNbt();

        if (tag != null) {
            for (int i = 0; i < 6; ++i) {
                if (tag.contains(TAG_ATTACHMENT + i)) {
                    CompoundTag attachmentTag = tag.getCompound(TAG_ATTACHMENT + i);
                    attachments[i] = AttachmentRegistry.getAttachment(attachmentTag.getString(TAG_TYPE), attachmentTag, this, DIRECTIONS[i]);
                } else {
                    attachments[i] = EmptyAttachment.INSTANCE;
                }
            }
        }
        modelData.setNeedsRefresh();
        requestModelDataUpdate();
    }
    // endregion

    // region NBT
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {

        modelData.setNeedsRefresh();
        return saveWithoutMetadata(provider);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.loadAdditional(tag, provider);

        redstonePower = tag.getInt(TAG_RS_POWER);

        byte[] bConn = tag.getByteArray(TAG_SIDES);
        if (bConn.length == 6) {
            for (int i = 0; i < 6; ++i) {
                connections[i] = ConnectionType.VALUES[bConn[i]];
            }
        }
        for (int i = 0; i < 6; ++i) {
            if (tag.contains(TAG_ATTACHMENT + i)) {
                CompoundTag attachmentTag = tag.getCompound(TAG_ATTACHMENT + i);
                attachments[i] = AttachmentRegistry.getAttachment(attachmentTag.getString(TAG_TYPE), attachmentTag, this, DIRECTIONS[i]);
            } else {
                attachments[i] = EmptyAttachment.INSTANCE;
            }
        }
        if (level != null && level.isClientSide()) {
            clientConnectionStateInitialized = bConn.length == 6;
            // Connection data changed on the client: recompute the shape and refresh the model data.
            modelData.setNeedsRefresh();
            requestModelDataUpdate();
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.saveAdditional(tag, provider);

        tag.putInt(TAG_RS_POWER, redstonePower);

        byte[] bConn = new byte[6];
        for (int i = 0; i < 6; ++i) {
            bConn[i] = (byte) connections[i].ordinal();
        }
        tag.putByteArray(TAG_SIDES, bConn);

        for (int i = 0; i < 6; ++i) {
            CompoundTag attachmentTag = new CompoundTag();
            attachments[i].write(attachmentTag);
            if (!attachmentTag.isEmpty()) {
                tag.put(TAG_ATTACHMENT + i, attachmentTag);
            }
        }
    }
    // endregion

    // region IGridHost
    @Override
    public final Level getHostWorld() {

        return world();
    }

    @Override
    public final BlockPos getHostPos() {

        return pos();
    }

    @Override
    public boolean hasGrid() {

        return grid != null;
    }

    @Override
    public final G getGrid() {

        if (level.isClientSide()) {
            throw new UnsupportedOperationException("No grid representation on client.");
        }
        if (grid == null) {
            IGridContainer gridContainer = IGridContainer.getGrid(level);
            assert gridContainer != null;
            grid = gridContainer.getGrid(getGridType(), getBlockPos());
        }
        // assert grid != null;
        return grid;
    }

    @Override
    public final void setGrid(G grid) {

        if (level.isClientSide()) {
            throw new UnsupportedOperationException("No grid representation on client.");
        }
        this.grid = grid;
    }

    @Override
    public void neighborChanged(Block blockIn, BlockPos fromPos) {

        Direction dir = BlockHelper.getSide(fromPos.subtract(worldPosition));
        if (dir != null) {
            attachments[dir.ordinal()].invalidate();
        }
        if (level != null) {
            redstonePower = level.getBestNeighborSignal(worldPosition);
            for (IAttachment attachment : attachments) {
                if (attachment instanceof IRedstoneControllableAttachment redstoneControllableAttachment) {
                    redstoneControllableAttachment.redstoneControl().setPower(redstonePower);
                }
            }
            TileRedstonePacket.sendToClient(this);
        }
        modelData.setNeedsRefresh();
    }

    @Override
    public void onAttachmentUpdate() {

        modelData.setNeedsRefresh();
        setChanged();
        callNeighborStateChange();
        ModelUpdatePacket.sendToClient(getLevel(), getBlockPos());
    }

    @Nonnull
    @Override
    public IAttachment getAttachment(Direction dir) {

        return attachments[dir.ordinal()];
    }

    @Override
    public boolean canConnectTo(IDuct<?, ?> other, Direction dir) {

        return IDuct.super.canConnectTo(other, dir) && connections[dir.ordinal()].allowDuctConnection();
    }

    @Override
    public ConnectionType getConnectionType(Direction dir) {

        return connections[dir.ordinal()];
    }

    @Override
    public void setConnectionType(Direction dir, ConnectionType type) {

        connections[dir.ordinal()] = type;
        setChanged();
        callNeighborStateChange();
        if (level != null) {
            level.invalidateCapabilities(worldPosition);
        }
        TileStatePacket.sendToClient(this);
    }
    // endregion

    // region ILocationAccess
    @Override
    public Block block() {

        return getBlockState().getBlock();
    }

    @Override
    public BlockState state() {

        return getBlockState();
    }

    @Override
    public BlockPos pos() {

        return worldPosition;
    }

    @Override
    public Level world() {

        return level;
    }
    // endregion

    @Nullable
    public <T, C> T getCapability(BlockCapability<T, C> capability, Direction side) {

        if (side == null || level == null || level.isClientSide() || connections[side.ordinal()] == DISABLED || getGrid() == null) {
            return null;
        }
        return attachments[side.ordinal()].wrapGridCapability(capability, getGrid().getCapability(capability));
    }

}
