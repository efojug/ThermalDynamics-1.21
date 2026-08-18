package cofh.thermal.dynamics;

import cofh.lib.util.DeferredRegisterCoFH;
import cofh.core.common.config.ConfigManager;
import cofh.thermal.dynamics.api.TDynApi;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.client.DebugRenderer;
import cofh.thermal.dynamics.client.gui.ItemBufferScreen;
import cofh.thermal.dynamics.client.gui.attachment.EnergyLimiterAttachmentScreen;
import cofh.thermal.dynamics.client.gui.attachment.FluidFilterAttachmentScreen;
import cofh.thermal.dynamics.client.gui.attachment.FluidServoAttachmentScreen;
import cofh.thermal.dynamics.client.gui.attachment.FluidTurboServoAttachmentScreen;
import cofh.thermal.dynamics.client.gui.attachment.ItemServoAttachmentScreen;
import cofh.thermal.dynamics.client.gui.attachment.ItemTurboServoAttachmentScreen;
import cofh.thermal.dynamics.common.block.entity.ItemBufferBlockEntity;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.config.TDynConfig;
import cofh.thermal.dynamics.common.event.GridEvents;
import cofh.thermal.dynamics.common.network.PacketHandler;
import cofh.thermal.dynamics.init.registries.*;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cofh.lib.util.FlagManager.setFlag;
import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;
import static cofh.thermal.core.ThermalCore.BLOCKS;
import static cofh.thermal.dynamics.init.registries.TDynBlockEntities.*;
import static cofh.thermal.dynamics.init.registries.TDynContainers.*;
import static cofh.thermal.dynamics.init.registries.TDynIDs.*;
import static cofh.thermal.lib.util.ThermalFlags.FLAG_XP_STORAGE_AUGMENT;
import static cofh.thermal.lib.util.ThermalIDs.ID_DEVICE_COLLECTOR;
import static cofh.thermal.lib.util.ThermalIDs.ID_DEVICE_NULLIFIER;
import static net.covers1624.quack.util.SneakyUtils.unsafeCast;

@Mod (ID_THERMAL_DYNAMICS)
public class ThermalDynamics {

    public static final Logger LOG = LogManager.getLogger(ID_THERMAL_DYNAMICS);
    public static final ConfigManager CONFIG_MANAGER = new ConfigManager();

    public static final ResourceLocation GRID_REGISTRY_LOC = ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, ID_GRID_TYPE);
    public static final DeferredRegisterCoFH<IGridType<?>> GRIDS = DeferredRegisterCoFH.create(GRID_REGISTRY_LOC, ID_THERMAL_DYNAMICS);

    public static final Registry<IGridType<?>> GRID_TYPE_REGISTRY = GRIDS.makeRegistry(e -> e.sync(false));

    private static boolean mekanismLoaded;

    public ThermalDynamics(ModContainer modContainer, IEventBus modEventBus) {

        setFeatureFlags();
        CONFIG_MANAGER.register(modEventBus).addServerConfig(new TDynConfig());

        mekanismLoaded = ModList.get().isLoaded("mekanism");
        if (mekanismLoaded) {
            cofh.thermal.dynamics.compat.mekanism.MekanismCompat.register();
        }

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::menuScreenSetup);
        modEventBus.addListener(this::capSetup);
        modEventBus.addListener(this::registrySetup);

        modEventBus.addListener(PacketHandler::registerNetworking);

        GRIDS.register(modEventBus);

        TDynBlocks.register();
        TDynItems.register();
        TDynGrids.register();

        TDynContainers.register();
        TDynBlockEntities.register();

        GridEvents.register();
    }

    private void setFeatureFlags() {

        setFlag(ID_DEVICE_COLLECTOR, true);
        setFlag(ID_DEVICE_NULLIFIER, true);

        setFlag(FLAG_XP_STORAGE_AUGMENT, true);
    }

    // region INITIALIZATION
    private void commonSetup(final FMLCommonSetupEvent event) {

    }

    private void registrySetup(final NewRegistryEvent event) {

        CONFIG_MANAGER.setupServer();
    }

    private void clientSetup(final FMLClientSetupEvent event) {

                event.enqueueWork(this::registerRenderLayers);
        DebugRenderer.register();
    }

    private void capSetup(RegisterCapabilitiesEvent event) {

        event.registerBlockEntity(TDynApi.GRID_HOST_CAPABILITY, ENERGY_DUCT_BLOCK_ENTITY.get(), (tile, ctx) -> tile);
        event.registerBlockEntity(TDynApi.GRID_HOST_CAPABILITY, FLUID_DUCT_BLOCK_ENTITY.get(), (tile, ctx) -> tile);
        event.registerBlockEntity(TDynApi.GRID_HOST_CAPABILITY, FLUID_DUCT_WINDOWED_BLOCK_ENTITY.get(), (tile, ctx) -> tile);
        event.registerBlockEntity(TDynApi.GRID_HOST_CAPABILITY, ITEM_DUCT_BLOCK_ENTITY.get(), (tile, ctx) -> tile);
        if (mekanismLoaded) {
            cofh.thermal.dynamics.compat.mekanism.MekanismCompat.registerCapabilities(event);
        }

        registerPassthroughCapability(event, Capabilities.EnergyStorage.BLOCK, unsafeCast(ENERGY_DUCT_BLOCK_ENTITY.get()));
        registerPassthroughCapability(event, Capabilities.FluidHandler.BLOCK, unsafeCast(FLUID_DUCT_BLOCK_ENTITY.get()));
        registerPassthroughCapability(event, Capabilities.FluidHandler.BLOCK, unsafeCast(FLUID_DUCT_WINDOWED_BLOCK_ENTITY.get()));
        registerPassthroughCapability(event, Capabilities.ItemHandler.BLOCK, unsafeCast(ITEM_DUCT_BLOCK_ENTITY.get()));

        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ITEM_BUFFER_BLOCK_ENTITY.get(), ItemBufferBlockEntity::getItemHandler);
    }

    private <T> void registerPassthroughCapability(RegisterCapabilitiesEvent event, BlockCapability<T, Direction> cap, BlockEntityType<DuctBlockEntity<?, ?>> type) {

        event.registerBlockEntity(cap, type, (tile, ctx) -> tile.getCapability(cap, ctx));
    }
    // endregion

    // region HELPERS
    private void menuScreenSetup(final RegisterMenuScreensEvent event) {

        event.register(ITEM_BUFFER_CONTAINER.get(), ItemBufferScreen::new);

        event.register(ENERGY_LIMITER_ATTACHMENT_CONTAINER.get(), EnergyLimiterAttachmentScreen::new);
        event.register(FLUID_FILTER_ATTACHMENT_CONTAINER.get(), FluidFilterAttachmentScreen::new);
        event.register(FLUID_SERVO_ATTACHMENT_CONTAINER.get(), FluidServoAttachmentScreen::new);
        event.register(FLUID_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), FluidTurboServoAttachmentScreen::new);
        event.register(ITEM_SERVO_ATTACHMENT_CONTAINER.get(), ItemServoAttachmentScreen::new);
        event.register(ITEM_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), ItemTurboServoAttachmentScreen::new);
        if (mekanismLoaded) {
            cofh.thermal.dynamics.compat.mekanism.client.MekanismClientCompat.registerMenuScreens(event);
        }
    }

    private void registerRenderLayers() {

        RenderType cutout = RenderType.cutout();
        RenderType translucent = RenderType.translucent();

        // RenderTypeLookup.setRenderLayer(ENERGY_DISTRIBUTOR_BLOCK, cutout);

        // RenderTypeLookup.setRenderLayer(BLOCKS.get(ID_ENDER_TUNNEL), translucent);

        // RenderTypeLookup.setRenderLayer(BLOCKS.get(ID_DEVICE_FLUID_BUFFER), cutout);
        // RenderTypeLookup.setRenderLayer(BLOCKS.get(ID_DEVICE_ITEM_BUFFER), cutout);

        ItemBlockRenderTypes.setRenderLayer(BLOCKS.get(ID_ENERGY_DUCT), cutout);
        ItemBlockRenderTypes.setRenderLayer(BLOCKS.get(ID_FLUID_DUCT), renderType -> renderType == cutout || renderType == translucent);
        ItemBlockRenderTypes.setRenderLayer(BLOCKS.get(ID_FLUID_DUCT_WINDOWED), renderType -> renderType == cutout || renderType == translucent);
        ItemBlockRenderTypes.setRenderLayer(BLOCKS.get(ID_ITEM_DUCT), cutout);
        if (mekanismLoaded) {
            cofh.thermal.dynamics.compat.mekanism.client.MekanismClientCompat.registerRenderLayers(cutout, translucent);
        }
    }
    // endregion
}
