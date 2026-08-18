package cofh.thermal.dynamics.common.block.entity.duct;

import cofh.core.common.network.packet.client.TileStatePacket;
import cofh.core.util.helpers.FluidHelper;
import cofh.core.util.helpers.RenderHelper;
import cofh.lib.api.block.entity.IPacketHandlerTile;
import cofh.thermal.dynamics.api.grid.IGridHostLuminous;
import cofh.thermal.dynamics.api.grid.IGridHostUpdateable;
import cofh.thermal.dynamics.common.grid.fluid.FluidGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static cofh.lib.util.constants.NBTTags.TAG_RENDER_FLUID;
import static cofh.thermal.core.client.ThermalTextures.BLANK_TEXTURE;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.FLUID_DUCT_WINDOWED_BLOCK_ENTITY;

public class FluidDuctWindowedBlockEntity extends FluidDuctBlockEntity implements IGridHostUpdateable, IGridHostLuminous, IPacketHandlerTile {

    private static final String TAG_RENDER_ALPHA = "RenderAlpha";

    FluidStack renderFluid = FluidStack.EMPTY;
    private int renderAlpha = 0xFF;
    private FluidStack lastModelFluid = FluidStack.EMPTY;
    private int lastModelAlpha = Integer.MIN_VALUE;
    private int lastModelColor;
    private boolean lastModelLuminous;

    public FluidDuctWindowedBlockEntity(BlockPos pos, BlockState state) {

        super(FLUID_DUCT_WINDOWED_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {

        super.onLoad();
    }

    @Override
    public void update() {

        renderFluid = getGrid().getRenderFluid();
        renderAlpha = getGrid().getRenderAlpha();
        TileStatePacket.sendToClient(this);
    }

    @Override
    public int getLightValue() {

        return FluidHelper.luminosity(renderFluid);
    }

    @Nonnull
    @Override
    public ModelData getModelData() {

        int color = FluidHelper.color(renderFluid);
        boolean lighterThanAirGas = FluidGrid.isLighterThanAirGas(renderFluid);
        if (lighterThanAirGas) {
            color = renderAlpha << 24 | color & 0xFFFFFF;
        }
        int alpha = (color >>> 24) * 99 / 100;
        int fillColor = alpha << 24 | color & 0xFFFFFF;
        boolean luminous = FluidHelper.luminosity(renderFluid) > 0;
        if (!FluidHelper.fluidsEqual(lastModelFluid, renderFluid) || lastModelAlpha != renderAlpha
                || lastModelColor != fillColor || lastModelLuminous != luminous) {
            lastModelFluid = renderFluid.isEmpty() ? FluidStack.EMPTY : renderFluid.copy();
            lastModelAlpha = renderAlpha;
            lastModelColor = fillColor;
            lastModelLuminous = luminous;
            modelData.setFill(renderFluid.isEmpty() ? BLANK_TEXTURE : RenderHelper.getFluidTexture(renderFluid).contents().name());
            modelData.setFillColor(fillColor);
            modelData.setFillLuminous(luminous);
            modelData.setNeedsRefresh();
        }
        return super.getModelData();
    }

    // region NBT
    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        if (!renderFluid.isEmpty()) {
            tag.put(TAG_RENDER_FLUID, renderFluid.save(provider, new CompoundTag()));
        }
        tag.putInt(TAG_RENDER_ALPHA, renderAlpha);
        super.saveAdditional(tag, provider);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {

        super.loadAdditional(tag, provider);

        renderFluid = FluidStack.parseOptional(provider, tag.getCompound(TAG_RENDER_FLUID));
        renderAlpha = tag.contains(TAG_RENDER_ALPHA) ? tag.getInt(TAG_RENDER_ALPHA) : 0xFF;
    }
    // endregion

    // region NETWORK
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {

        return saveWithoutMetadata(provider);
    }

    // STATE
    @Override
    public FriendlyByteBuf getStatePacket(FriendlyByteBuf buffer) {

        FluidStack.OPTIONAL_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buffer, renderFluid);
        buffer.writeByte(renderAlpha);

        super.getStatePacket(buffer);

        return buffer;
    }

    @Override
    public void handleStatePacket(FriendlyByteBuf buffer) {

        renderFluid = FluidStack.OPTIONAL_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buffer);
        renderAlpha = buffer.readUnsignedByte();

        super.handleStatePacket(buffer);
    }
    // endregion
}
