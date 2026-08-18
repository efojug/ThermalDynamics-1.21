package cofh.thermal.dynamics.common.attachment;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

import static cofh.thermal.dynamics.init.registries.TDynGrids.FLUID_GRID;
import static cofh.thermal.dynamics.init.registries.TDynGrids.ITEM_GRID;
import static cofh.thermal.dynamics.init.registries.TDynIDs.*;

public class AttachmentRegistry {

    public static final IAttachmentFactory<IAttachment> FILTER_FACTORY = new IAttachmentFactory<>() {
        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side) {
            return createAttachment(nbt, duct, side, null);
        }

        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {
            if (duct instanceof ItemDuctBlockEntity || duct.getGridType() == ITEM_GRID.get()) {
                ItemFilterAttachment attachment = new ItemFilterAttachment(duct, side);
                return provider == null ? attachment.read(nbt) : attachment.read(nbt, provider);
            }
            return EmptyAttachment.INSTANCE;
        }
    };

    public static final IAttachmentFactory<IAttachment> SERVO_FACTORY = new IAttachmentFactory<>() {
        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side) {
            return createAttachment(nbt, duct, side, null);
        }

        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {
            if (duct.getGridType() == FLUID_GRID.get()) {
                return new FluidServoAttachment(duct, side).read(nbt);
            }
            return EmptyAttachment.INSTANCE;
        }
    };

    public static final IAttachmentFactory<IAttachment> TURBO_SERVO_FACTORY = new IAttachmentFactory<>() {
        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side) {
            return createAttachment(nbt, duct, side, null);
        }

        @Override
        public IAttachment createAttachment(CompoundTag nbt, IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {
            if (duct.getGridType() == FLUID_GRID.get()) {
                return new FluidTurboServoAttachment(duct, side).read(nbt);
            }
            return EmptyAttachment.INSTANCE;
        }
    };

    protected static final Map<String, IAttachmentFactory<? extends IAttachment>> ATTACHMENT_FACTORY_MAP = new Object2ObjectOpenHashMap<>();

    static {
        registerAttachmentFactory(ENERGY_LIMITER, EnergyLimiterAttachment.FACTORY);
        registerAttachmentFactory(FILTER, FILTER_FACTORY);
        registerAttachmentFactory(SERVO, SERVO_FACTORY);
        registerAttachmentFactory(TURBO_SERVO, TURBO_SERVO_FACTORY);
    }

    public static boolean registerAttachmentFactory(String type, IAttachmentFactory<?> factory) {

        if (type == null || type.isEmpty() || factory == null) {
            return false;
        }
        ATTACHMENT_FACTORY_MAP.put(type, factory);
        return true;
    }

    public static IAttachment getAttachment(String type, CompoundTag nbt, IDuct<?, ?> duct, Direction side) {

        return getAttachment(type, nbt, duct, side, null);
    }

    public static IAttachment getAttachment(String type, CompoundTag nbt, IDuct<?, ?> duct, Direction side, HolderLookup.Provider provider) {

        if (ATTACHMENT_FACTORY_MAP.containsKey(type)) {
            return ATTACHMENT_FACTORY_MAP.get(type).createAttachment(nbt, duct, side, provider);
        }
        return EmptyAttachment.INSTANCE;
    }

}
