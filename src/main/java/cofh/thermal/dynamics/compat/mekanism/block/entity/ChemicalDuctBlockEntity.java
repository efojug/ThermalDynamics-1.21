package cofh.thermal.dynamics.compat.mekanism.block.entity;

import cofh.core.common.network.packet.client.TileStatePacket;
import cofh.lib.api.block.entity.IPacketHandlerTile;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridHostUpdateable;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGrid;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGridNode;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nonnull;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_DUCT_BLOCK_ENTITY;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_GRID;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;
import static cofh.thermal.core.client.ThermalTextures.BLANK_TEXTURE;

public class ChemicalDuctBlockEntity extends DuctBlockEntity<ChemicalGrid, ChemicalGridNode> implements IGridHostUpdateable, IPacketHandlerTile {

    private static final String TAG_RENDER_CHEMICAL = "RenderChemical";
    private static final String TAG_RENDER_ALPHA = "RenderAlpha";

    private ChemicalStack renderChemical = ChemicalStack.EMPTY;
    private int renderAlpha = 0xFF;

    public ChemicalDuctBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {

        super(type, pos, state);
    }

    public ChemicalDuctBlockEntity(BlockPos pos, BlockState state) {

        super(CHEMICAL_DUCT_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected boolean canConnectToBlock(Direction dir) {

        if (!connections[dir.ordinal()].allowBlockConnection()) {
            return false;
        }
        BlockEntity tile = level.getBlockEntity(getBlockPos().relative(dir));
        return tile != null && GridHelper.getGridHost(tile) == null &&
                level.getCapability(CHEMICAL_HANDLER, tile.getBlockPos(), tile.getBlockState(), tile, dir.getOpposite()) != null;
    }

    @Override
    public IGridType<ChemicalGrid> getGridType() {

        return CHEMICAL_GRID.get();
    }

    @Override
    public void update() {

        renderChemical = getGrid().getRenderChemical();
        renderAlpha = getGrid().getRenderAlpha();
        TileStatePacket.sendToClient(this);
    }

    @Nonnull
    @Override
    public ModelData getModelData() {

        modelData.setFill(renderChemical.isEmpty() ? BLANK_TEXTURE : renderChemical.getChemical().getIcon());
        modelData.setFillColor(renderChemical.isEmpty() ? 0xFFFFFFFF : renderAlpha * 99 / 100 << 24 | renderChemical.getChemicalTint() & 0xFFFFFF);
        modelData.setFillLuminous(false);
        return super.getModelData();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        if (!renderChemical.isEmpty()) {
            tag.put(TAG_RENDER_CHEMICAL, renderChemical.save(provider));
        }
        tag.putInt(TAG_RENDER_ALPHA, renderAlpha);
        super.saveAdditional(tag, provider);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.loadAdditional(tag, provider);
        renderChemical = ChemicalStack.parseOptional(provider, tag.getCompound(TAG_RENDER_CHEMICAL));
        renderAlpha = tag.contains(TAG_RENDER_ALPHA) ? tag.getInt(TAG_RENDER_ALPHA) : 0xFF;
    }

    @Override
    public FriendlyByteBuf getStatePacket(FriendlyByteBuf buffer) {

        ChemicalStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buffer, renderChemical);
        buffer.writeByte(renderAlpha);
        super.getStatePacket(buffer);
        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {

        renderChemical = ChemicalStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buffer);
        renderAlpha = buffer.readUnsignedByte();
        super.handleStatePacket(buffer);
    }

    @Override
    public boolean canConnectTo(IDuct<?, ?> other, Direction dir) {

        if (!level.isClientSide() && other.getGrid() instanceof ChemicalGrid otherGrid) {
            ChemicalStack chemical = getGrid().getChemical();
            ChemicalStack otherChemical = otherGrid.getChemical();
            if (!chemical.isEmpty() && !otherChemical.isEmpty() && !ChemicalStack.isSameChemical(chemical, otherChemical)) {
                return false;
            }
        }
        return super.canConnectTo(other, dir);
    }

}
