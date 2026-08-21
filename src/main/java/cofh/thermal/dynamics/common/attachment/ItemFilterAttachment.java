package cofh.thermal.dynamics.common.attachment;

import cofh.core.util.filter.BaseItemFilter;
import cofh.core.util.filter.IFilter;
import cofh.core.util.helpers.ItemHelper;
import cofh.lib.api.IConveyableData;
import cofh.lib.util.Utils;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.config.TDynConfig;
import cofh.thermal.dynamics.common.grid.item.ItemGrid;
import cofh.thermal.dynamics.common.grid.item.ItemRoute;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import cofh.thermal.dynamics.common.inventory.attachment.ItemServoAttachmentMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static cofh.lib.util.constants.NBTTags.TAG_TYPE;
import static cofh.thermal.core.ThermalCore.ITEMS;
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_ACTIVE_LOC;
import static cofh.thermal.dynamics.client.TDynTextures.FILTER_ATTACHMENT_LOC;
import static cofh.thermal.dynamics.init.registries.TDynIDs.FILTER;
import static cofh.thermal.dynamics.init.registries.TDynIDs.ID_FILTER_ATTACHMENT;

/**
 * The item duct's only extraction attachment. Pulls filtered items from the adjacent inventory each
 * tick and routes them into the grid. (Item ducts have no plain servo; fluid/chemical ducts have no
 * filter — this class carries the whole item-side extraction pipeline.)
 */
public class ItemFilterAttachment implements IFilterableAttachment, IRedstoneControllableAttachment, IConveyableData, MenuProvider {

    public static final Component DISPLAY_NAME = Component.translatable("attachment.thermal.filter");
    public static final int TRANSFER = 64;

    protected final IDuct<?, ?> duct;
    protected final Direction side;
    private final Set<BlockPos> excludedTargets;
    protected final BaseItemFilter filter = new BaseItemFilter(15);
    protected final RedstoneControlLogic rsControl = new RedstoneControlLogic(this);
    protected final List<ItemStack> legacyStuffedItems = new ArrayList<>();
    @Nullable
    private SourceSnapshot sourceSnapshot;
    @Nullable
    private ItemDuctBlockEntity tickItemDuct;
    @Nullable
    private ItemGrid tickItemGrid;
    @Nullable
    private SourceSnapshot tickSource;

    public ItemFilterAttachment(IDuct<?, ?> duct, Direction side) {

        this.duct = duct;
        this.side = side;
        this.excludedTargets = Set.of(duct.getHostPos().relative(side));
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

        sourceSnapshot = null;
        tickItemDuct = null;
        tickItemGrid = null;
        tickSource = null;
    }

    @Override
    public IAttachment read(CompoundTag nbt) {

        return read(nbt, world() == null ? Utils.BUILTIN_ACCESS : world().registryAccess());
    }

    @Override
    public IAttachment read(CompoundTag nbt, HolderLookup.Provider provider) {

        if (nbt.isEmpty()) {
            return this;
        }
        filter.read(provider, nbt);
        rsControl.read(nbt);
        legacyStuffedItems.clear();
        for (Tag tag : nbt.getList("StuffedItems", Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(provider, (CompoundTag) tag);
            if (!stack.isEmpty()) {
                legacyStuffedItems.add(stack);
            }
        }
        return this;
    }

    @Override
    public CompoundTag write(CompoundTag nbt) {

        CompoundTag result = write(nbt, world() == null ? Utils.BUILTIN_ACCESS : world().registryAccess());
        result.remove("StuffedItems");
        return result;
    }

    @Override
    public CompoundTag write(CompoundTag nbt, HolderLookup.Provider provider) {

        nbt.putString(TAG_TYPE, FILTER);
        filter.write(provider, nbt);
        rsControl.write(nbt);
        if (!legacyStuffedItems.isEmpty()) {
            ListTag items = new ListTag();
            for (ItemStack stack : legacyStuffedItems) {
                items.add(stack.save(provider, new CompoundTag()));
            }
            nbt.put("StuffedItems", items);
        }
        return nbt;
    }

    @Override
    public void tick() {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct)) {
            return;
        }
        if (!legacyStuffedItems.isEmpty()) {
            itemDuct.drainLegacyStuffedItems();
        }
        if (!rsControl.getState()) {
            return;
        }
        ItemGrid itemGrid = itemDuct.getGrid();
        if (itemGrid == null || itemGrid.hasWaitingItems()) {
            return;
        }
        IItemHandler handler = itemDuct.getCachedExternalItemHandler(side);
        int slotCount = handler == null ? 0 : handler.getSlots();
        if (slotCount <= 0) {
            return;
        }
        SourceSnapshot source = sourceSnapshot(handler, slotCount);
        // This is a transport-stack budget (16 * 64 by default), not an inventory-slot limit.
        int maxStacks = TDynConfig.itemFilterStacksPerTick;
        tickItemDuct = itemDuct;
        tickItemGrid = itemGrid;
        tickSource = source;
        try {
            for (int sent = 0; sent < maxStacks; ++sent) {
                if (!pullAndRoute()) {
                    break;
                }
            }
        } finally {
            tickItemDuct = null;
            tickItemGrid = null;
            tickSource = null;
        }
    }

    @Override
    public boolean needsTick() {

        return true;
    }

    protected boolean pullAndRoute() {

        if (tickItemDuct != null && tickItemGrid != null && tickSource != null) {
            return pullAndRoute(tickItemDuct, tickItemGrid, tickSource, excludedTargets);
        }
        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || world() == null) {
            return false;
        }
        ItemGrid itemGrid = itemDuct.getGrid();
        if (itemGrid == null || itemGrid.hasWaitingItems()) {
            return false;
        }
        IItemHandler handler = itemDuct.getCachedExternalItemHandler(side);
        int slotCount = handler == null ? 0 : handler.getSlots();
        if (slotCount <= 0) {
            return false;
        }
        return pullAndRoute(itemDuct, itemGrid, sourceSnapshot(handler, slotCount), excludedTargets);
    }

    private SourceSnapshot sourceSnapshot(IItemHandler handler, int slotCount) {

        if (sourceSnapshot == null || !sourceSnapshot.matches(handler, slotCount)) {
            sourceSnapshot = new SourceSnapshot(handler, slotCount);
        } else {
            sourceSnapshot.reset();
        }
        return sourceSnapshot;
    }

    private boolean pullAndRoute(ItemDuctBlockEntity itemDuct, ItemGrid itemGrid, SourceSnapshot source,
            Set<BlockPos> excludedTargets) {

        Candidate candidate = findCandidate(source);
        if (candidate == null) {
            return false;
        }
        ItemGrid.RouteCapacity routed = itemGrid.findRouteWithCapacity(pos(), excludedTargets, candidate.stack(), null, null);
        if (routed == null) {
            return false;
        }
        ItemRoute route = routed.route();
        int capacity = routed.capacity();
        ItemStack sending = candidate.stack().copyWithCount(Math.min(candidate.stack().getCount(), capacity));
        ItemStack extracted = extractMatching(source, sending, candidate.slots(), candidate.slotCount());
        if (extracted.isEmpty()) {
            return false;
        }
        source.advanceCandidate(candidate.startSlot());
        itemDuct.addTravelingItem(new TravelingItem(extracted, pos(), side, route));
        return true;
    }

    public List<ItemStack> legacyStuffedItems() {

        return legacyStuffedItems;
    }

    /**
     * Return routing may put an item back through the same input attachment that extracted it.
     * This does not make the attachment a general output target; the source duct performs the
     * endpoint identity check before calling this predicate.
     */
    public boolean acceptsSourceReturn(ItemStack stack) {

        return !stack.isEmpty() && filter.valid(stack);
    }

    public ItemStack insertFromExternal(ItemStack stack, boolean simulate) {

        if (!(duct instanceof ItemDuctBlockEntity itemDuct) || stack.isEmpty() || !filter.valid(stack)
                || itemDuct.getGrid() == null || itemDuct.getGrid().hasWaitingItems()) {
            return stack;
        }
        ItemStack sending = stack.copyWithCount(Math.min(stack.getCount(), TRANSFER));
        ItemGrid.RouteCapacity routed = itemDuct.getGrid().findRouteWithCapacity(pos(), excludedTargets, sending, null, null);
        if (routed == null) {
            return stack;
        }
        ItemRoute route = routed.route();
        int capacity = routed.capacity();
        sending = sending.copyWithCount(Math.min(sending.getCount(), capacity));
        if (!simulate) {
            itemDuct.addTravelingItem(new TravelingItem(sending, pos(), side, route));
        }
        return stack.copyWithCount(stack.getCount() - sending.getCount());
    }

    private Candidate findCandidate(SourceSnapshot source) {

        int slotCount = source.slots();
        int activeStart = source.activeCandidateStart();
        if (activeStart >= 0 && activeStart < slotCount) {
            ItemStack initial = source.get(activeStart);
            if (!initial.isEmpty() && filter.valid(initial)) {
                return buildCandidate(source, initial, activeStart, source.activeCandidateCount());
            }
            source.discardCandidate();
        }
        int start = slotCount == 0 ? 0 : Math.floorMod(source.candidateCursor(), slotCount);
        for (int offset = 0; offset < slotCount; ++offset) {
            int slot = (start + offset) % slotCount;
            ItemStack initial = source.get(slot);
            if (initial.isEmpty() || !filter.valid(initial)) {
                continue;
            }
            return buildCandidate(source, initial, slot, 0);
        }
        source.finishScan();
        return null;
    }

    private Candidate buildCandidate(SourceSnapshot source, ItemStack initial, int startSlot, int previousCount) {

        ItemStack combined = initial.copyWithCount(Math.min(initial.getCount(), TRANSFER));
        // At most TRANSFER slots can contribute a positive amount to one transport stack.
        int[] matchingSlots = source.candidateSlots();
        int matchingCount = 1;
        matchingSlots[0] = startSlot;
        for (int i = 1; i < previousCount && combined.getCount() < TRANSFER; ++i) {
            int slot = matchingSlots[i];
            if (slot <= startSlot) {
                continue;
            }
            ItemStack extra = source.get(slot);
            if (!extra.isEmpty() && ItemHelper.itemsEqualWithTags(initial, extra)) {
                int amount = Math.min(TRANSFER - combined.getCount(), extra.getCount());
                if (amount > 0) {
                    combined.grow(amount);
                    matchingSlots[matchingCount++] = slot;
                }
            }
        }
        for (int next = startSlot + 1; next < source.slots() && combined.getCount() < TRANSFER; ++next) {
            if (containsSlot(matchingSlots, matchingCount, next)) {
                continue;
            }
            ItemStack extra = source.get(next);
            if (!extra.isEmpty() && ItemHelper.itemsEqualWithTags(initial, extra)) {
                int amount = Math.min(TRANSFER - combined.getCount(), extra.getCount());
                if (amount > 0) {
                    combined.grow(amount);
                    matchingSlots[matchingCount++] = next;
                }
            }
        }
        source.beginCandidate(startSlot, matchingCount);
        return new Candidate(combined, matchingSlots, matchingCount, startSlot);
    }

    private ItemStack extractMatching(SourceSnapshot source, ItemStack candidate, int[] preferredSlots, int preferredCount) {

        ItemStack combined = ItemStack.EMPTY;
        int remaining = candidate.getCount();
        for (int i = 0; i < preferredCount; ++i) {
            if (remaining <= 0) {
                break;
            }
            int slot = preferredSlots[i];
            ItemStack extracted = extractFromSlot(source, candidate, slot, remaining);
            if (!extracted.isEmpty()) {
                combined = append(combined, extracted);
                remaining -= extracted.getCount();
            }
        }
        // A different block entity may have changed the source between the snapshot and execute.
        // Preserve the old fallback behavior by checking slots that were not in the plan.
        for (int slot = 0; slot < source.slots() && remaining > 0; ++slot) {
            if (containsSlot(preferredSlots, preferredCount, slot)) {
                continue;
            }
            ItemStack extracted = extractFromSlot(source, candidate, slot, remaining);
            if (!extracted.isEmpty()) {
                combined = append(combined, extracted);
                remaining -= extracted.getCount();
            }
        }
        return combined;
    }

    private ItemStack extractFromSlot(SourceSnapshot source, ItemStack candidate, int slot, int amount) {

        ItemStack sample = source.refresh(slot, amount);
        if (sample.isEmpty() || !ItemHelper.itemsEqualWithTags(candidate, sample)) {
            return ItemStack.EMPTY;
        }
        ItemStack extracted = source.handler().extractItem(slot, amount, false);
        // Re-simulate lazily if another batch reaches this slot. This also handles custom handlers
        // whose slot limit is larger than the normal item max stack size.
        source.invalidate(slot);
        return extracted;
    }

    private static ItemStack append(ItemStack combined, ItemStack extracted) {

        if (combined.isEmpty()) {
            return extracted;
        }
        combined.grow(extracted.getCount());
        return combined;
    }

    private static boolean containsSlot(int[] slots, int count, int slot) {

        for (int i = 0; i < count; ++i) {
            if (slots[i] == slot) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(ItemStack stack, int[] slots, int slotCount, int startSlot) {

    }

    /**
     * A tick-scoped lazy view of the source handler. IItemHandler has no content-version contract,
     * so retaining this across ticks could hide items inserted by another block entity or a player.
     */
    private static final class SourceSnapshot {

        private final IItemHandler handler;
        private final ItemStack[] stacks;
        private final int[] candidateSlots;
        private int[] touchedSlots;
        private int touchedCount;
        private int candidateCursor;
        private int activeCandidateStart = -1;
        private int activeCandidateCount;

        private SourceSnapshot(IItemHandler handler, int slotCount) {

            this.handler = handler;
            this.stacks = new ItemStack[Math.max(0, slotCount)];
            this.candidateSlots = new int[Math.min(stacks.length, TRANSFER)];
            this.touchedSlots = new int[Math.min(stacks.length, TRANSFER)];
        }

        private boolean matches(IItemHandler handler, int slotCount) {

            return this.handler == handler && stacks.length == slotCount;
        }

        private void reset() {

            for (int i = 0; i < touchedCount; ++i) {
                stacks[touchedSlots[i]] = null;
            }
            touchedCount = 0;
            activeCandidateStart = -1;
            activeCandidateCount = 0;
        }

        private IItemHandler handler() {

            return handler;
        }

        private int slots() {

            return stacks.length;
        }

        private int[] candidateSlots() {

            return candidateSlots;
        }

        private int candidateCursor() {

            return candidateCursor;
        }

        private int activeCandidateStart() {

            return activeCandidateStart;
        }

        private int activeCandidateCount() {

            return activeCandidateCount;
        }

        private void beginCandidate(int startSlot, int count) {

            candidateCursor = startSlot;
            activeCandidateStart = startSlot;
            activeCandidateCount = count;
        }

        private void discardCandidate() {

            if (activeCandidateStart >= 0) {
                candidateCursor = Math.min(stacks.length, activeCandidateStart + 1);
            }
            activeCandidateStart = -1;
            activeCandidateCount = 0;
        }

        private void finishScan() {

            activeCandidateStart = -1;
            activeCandidateCount = 0;
        }

        private void advanceCandidate(int startSlot) {

            if (stacks.length > 0) {
                // Rotate after every successful transfer, even when the source slot was replenished.
                // Otherwise a continuously-fed slot 0 can consume the whole per-tick budget and
                // starve every later slot indefinitely.
                candidateCursor = (startSlot + 1) % stacks.length;
            }
            activeCandidateStart = -1;
            activeCandidateCount = 0;
        }

        private ItemStack get(int slot) {

            if (slot < 0 || slot >= stacks.length) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = stacks[slot];
            return stack == null ? refresh(slot, TRANSFER) : stack;
        }

        private ItemStack refresh(int slot, int amount) {

            if (slot < 0 || slot >= stacks.length) {
                return ItemStack.EMPTY;
            }
            if (stacks[slot] == null) {
                rememberTouched(slot);
            }
            ItemStack stack = handler.extractItem(slot, amount, true);
            stacks[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            return stacks[slot];
        }

        private void rememberTouched(int slot) {

            if (touchedCount >= touchedSlots.length) {
                touchedSlots = Arrays.copyOf(touchedSlots, Math.max(1, touchedSlots.length * 2));
            }
            touchedSlots[touchedCount++] = slot;
        }

        private void invalidate(int slot) {

            if (slot >= 0 && slot < stacks.length) {
                stacks[slot] = null;
            }
        }
    }

    @Override
    public ItemStack getItem() {

        return new ItemStack(ITEMS.get(ID_FILTER_ATTACHMENT));
    }

    @Override
    public ResourceLocation getTexture() {

        return rsControl.getState() ? FILTER_ATTACHMENT_ACTIVE_LOC : FILTER_ATTACHMENT_LOC;
    }

    @Override
    public Component getDisplayName() {

        return DISPLAY_NAME;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {

        return new ItemServoAttachmentMenu(id, player.level(), pos(), side, inventory, player);
    }

    @Override
    public IFilter getFilter() {

        return filter;
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
        return buffer;
    }

    @Override
    public void handleControlPacket(FriendlyByteBuf buffer) {

        rsControl.readFromBuffer(buffer);
        handleConfigPacket(buffer);
    }

    @Override
    public RedstoneControlLogic redstoneControl() {

        return rsControl;
    }

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

}
