package cofh.thermal.dynamics.compat.mekanism;

import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.TDynApi;
import cofh.thermal.dynamics.common.block.DuctBlock;
import cofh.thermal.dynamics.common.attachment.AttachmentRegistry;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.item.DuctBlockItem;
import cofh.thermal.dynamics.compat.mekanism.block.entity.ChemicalDuctBlockEntity;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGrid;
import cofh.thermal.dynamics.compat.mekanism.grid.ChemicalGridNode;
import cofh.thermal.dynamics.compat.mekanism.attachment.ChemicalServoAttachment;
import cofh.thermal.dynamics.compat.mekanism.attachment.ChemicalTurboServoAttachment;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalTurboServoAttachmentMenu;
import cofh.thermal.dynamics.compat.mekanism.network.data.server.ChemicalFilterPayload;
import cofh.thermal.dynamics.compat.mekanism.network.packet.server.ChemicalFilterPacket;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.inventory.MenuType;
import cofh.core.util.ProxyUtils;

import java.util.function.Supplier;

import static cofh.lib.util.Utils.itemProperties;
import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;
import static cofh.thermal.core.ThermalCore.BLOCKS;
import static cofh.thermal.core.ThermalCore.BLOCK_ENTITIES;
import static cofh.thermal.core.ThermalCore.CONTAINERS;
import static cofh.thermal.core.init.registries.ThermalCreativeTabs.devicesTab;
import static cofh.thermal.core.util.RegistrationHelper.registerBlock;
import static cofh.thermal.dynamics.ThermalDynamics.GRIDS;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_CHEMICAL_DUCT;
import static net.covers1624.quack.util.SneakyUtils.unsafeCast;
import static net.minecraft.resources.ResourceLocation.fromNamespaceAndPath;
import static net.minecraft.world.level.block.state.BlockBehaviour.Properties.of;

/**
 * Optional Mekanism integration. This class must only be loaded after the Mekanism mod-presence check.
 */
public final class MekanismCompat {

    public static final BlockCapability<IChemicalHandler, Direction> CHEMICAL_HANDLER = BlockCapability.createSided(fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    public static final Supplier<IGridType<ChemicalGrid>> CHEMICAL_GRID = GRIDS.register("chemical_grid", () -> IGridType.of(ChemicalGrid::new));
    public static final Supplier<BlockEntityType<ChemicalDuctBlockEntity>> CHEMICAL_DUCT_BLOCK_ENTITY = BLOCK_ENTITIES.register(ID_CHEMICAL_DUCT,
            () -> BlockEntityType.Builder.of(ChemicalDuctBlockEntity::new, BLOCKS.get(ID_CHEMICAL_DUCT)).build(null));
    private static final DeferredHolder<Item, Item> CHEMICAL_DUCT_ITEM = devicesTab(50, registerBlock(ID_CHEMICAL_DUCT,
            () -> new DuctBlock(of().sound(SoundType.LANTERN).strength(1.0F).dynamicShape().noOcclusion(), unsafeCast(CHEMICAL_DUCT_BLOCK_ENTITY)),
            () -> new DuctBlockItem(BLOCKS.get(ID_CHEMICAL_DUCT), itemProperties()).setModId(ID_THERMAL_DYNAMICS)));
    public static final Supplier<DuctBlock> CHEMICAL_DUCT = () -> (DuctBlock) BLOCKS.get(ID_CHEMICAL_DUCT);
    public static final DeferredHolder<MenuType<?>, MenuType<ChemicalServoAttachmentMenu>> CHEMICAL_SERVO_ATTACHMENT_CONTAINER = CONTAINERS.register("chemical_servo_attachment",
            () -> IMenuTypeExtension.create((id, inventory, data) -> new ChemicalServoAttachmentMenu(id, ProxyUtils.getClientWorld(), data.readBlockPos(), data.readEnum(Direction.class), inventory, ProxyUtils.getClientPlayer())));
    public static final DeferredHolder<MenuType<?>, MenuType<ChemicalTurboServoAttachmentMenu>> CHEMICAL_TURBO_SERVO_ATTACHMENT_CONTAINER = CONTAINERS.register("chemical_turbo_servo_attachment",
            () -> IMenuTypeExtension.create((id, inventory, data) -> new ChemicalTurboServoAttachmentMenu(id, ProxyUtils.getClientWorld(), data.readBlockPos(), data.readEnum(Direction.class), inventory, ProxyUtils.getClientPlayer())));

    private MekanismCompat() {

    }

    public static void register() {

        AttachmentRegistry.registerAttachmentFactory("servo", new ChemicalServoFactory());
        AttachmentRegistry.registerAttachmentFactory("turbo_servo", new ChemicalTurboServoFactory());
    }

    private static final class ChemicalServoFactory implements cofh.thermal.dynamics.common.attachment.IAttachmentFactory<cofh.thermal.dynamics.common.attachment.IAttachment> {

        @Override
        public cofh.thermal.dynamics.common.attachment.IAttachment createAttachment(net.minecraft.nbt.CompoundTag nbt, cofh.thermal.dynamics.api.grid.IDuct<?, ?> duct, Direction side) {

            return createAttachment(nbt, duct, side, null);
        }

        @Override
        public cofh.thermal.dynamics.common.attachment.IAttachment createAttachment(net.minecraft.nbt.CompoundTag nbt, cofh.thermal.dynamics.api.grid.IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {

            if (duct.getGridType() == CHEMICAL_GRID.get()) {
                return provider == null ? new ChemicalServoAttachment(duct, side).read(nbt) : new ChemicalServoAttachment(duct, side).read(nbt, provider);
            }
            return AttachmentRegistry.SERVO_FACTORY.createAttachment(nbt, duct, side, provider);
        }
    }

    private static final class ChemicalTurboServoFactory implements cofh.thermal.dynamics.common.attachment.IAttachmentFactory<cofh.thermal.dynamics.common.attachment.IAttachment> {

        @Override
        public cofh.thermal.dynamics.common.attachment.IAttachment createAttachment(net.minecraft.nbt.CompoundTag nbt, cofh.thermal.dynamics.api.grid.IDuct<?, ?> duct, Direction side) {

            return createAttachment(nbt, duct, side, null);
        }

        @Override
        public cofh.thermal.dynamics.common.attachment.IAttachment createAttachment(net.minecraft.nbt.CompoundTag nbt, cofh.thermal.dynamics.api.grid.IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {

            if (duct.getGridType() == CHEMICAL_GRID.get()) {
                return provider == null ? new ChemicalTurboServoAttachment(duct, side).read(nbt) : new ChemicalTurboServoAttachment(duct, side).read(nbt, provider);
            }
            return AttachmentRegistry.TURBO_SERVO_FACTORY.createAttachment(nbt, duct, side, provider);
        }
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {

        event.registerBlockEntity(TDynApi.GRID_HOST_CAPABILITY, CHEMICAL_DUCT_BLOCK_ENTITY.get(), (tile, ctx) -> tile);
        event.registerBlockEntity(CHEMICAL_HANDLER, CHEMICAL_DUCT_BLOCK_ENTITY.get(), (tile, ctx) -> tile.getCapability(CHEMICAL_HANDLER, ctx));
    }

    public static void registerNetworking(PayloadRegistrar registrar) {

        registrar.playToServer(ChemicalFilterPayload.TYPE, ChemicalFilterPayload.STREAM_CODEC, ChemicalFilterPacket.get()::handle);
    }

}
