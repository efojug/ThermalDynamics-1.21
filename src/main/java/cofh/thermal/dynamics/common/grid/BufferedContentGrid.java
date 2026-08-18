package cofh.thermal.dynamics.common.grid;

import cofh.thermal.dynamics.ThermalDynamics;
import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.IGridType;
import cofh.thermal.dynamics.api.helper.GridHelper;
import com.google.common.graph.EndpointPair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base for grids that hold a single buffered content type (fluid, chemical): one shared storage
 * tank per grid, a long-domain overflow buffer, round-robin distribution to nodes, debounced
 * render sync, and proportional redistribution on merge/split. Content access goes through the
 * {@link OverflowBuffer.Ops} type-class, so this class never references a concrete stack type and
 * stays usable from optional-dependency modules.
 * <p>
 * Subclasses keep their storage implementation and public handler API; this class supplies the
 * shared algorithms via the abstract content hooks.
 */
public abstract class BufferedContentGrid<G extends BufferedContentGrid<G, N, S>, N extends ContentGridNode<G, S, ?>, S> extends Grid<G, N> {

    protected static final String TAG_STORAGE = "Storage";
    protected static final String TAG_OVERFLOW = "Overflow";

    protected final OverflowBuffer.Ops<S> contentOps;
    protected final OverflowBuffer<S> overflowBuffer;
    protected final GridRenderState<S> renderState;
    protected final OverflowWatchdog overflowWatchdog;
    private final String contentName;

    protected List<N> distList = List.of();
    protected int distIndex;
    protected List<N> attachmentNodeList = List.of();
    protected boolean attachmentNodesDirty = true;
    protected List<N> nodeList = List.of();
    protected int nodeTracker;
    protected boolean isSendingContent;
    protected boolean isReplayingOverflow;
    protected final ObjectOpenHashSet<BlockPos> visitedTargets = new ObjectOpenHashSet<>();
    // Reused scratch state for the per-tick equal-split output pass.
    private final List<N> outputNodes = new ArrayList<>();
    private final List<IDuct<?, ?>> outputHosts = new ArrayList<>();
    private final IntArrayList candidateNode = new IntArrayList();
    private final IntArrayList candidateConn = new IntArrayList();

    protected BufferedContentGrid(IGridType<G> gridType, UUID id, Level world, OverflowBuffer.Ops<S> ops, long renderAmount, String contentName) {

        super(gridType, id, world);
        this.contentOps = ops;
        this.contentName = contentName;
        this.overflowBuffer = new OverflowBuffer<>(ops);
        this.renderState = new GridRenderState<>(ops, renderAmount);
        this.overflowWatchdog = new OverflowWatchdog(contentName);
    }

    // region CONTENT HOOKS
    /** The stack currently held by the grid's storage tank (not the overflow buffer). */
    protected abstract S storedStack();

    /** Replaces the storage tank content with {@code amount} of {@code type} (empty type or non-positive amount clears it). */
    protected abstract void setStored(S type, long amount);

    protected abstract long storageCapacity();

    protected abstract void setStorageCapacity(long capacity);

    /** Storage capacity contributed by each duct block of this grid. */
    protected abstract long ductCapacity();

    protected abstract int renderAlpha(S held);

    protected abstract CompoundTag saveStorage(HolderLookup.Provider provider);

    protected abstract void loadStorage(HolderLookup.Provider provider, CompoundTag tag);

    /** Drains {@code amount} of the held content, overflow buffer first, then storage. */
    protected abstract void drainHeld(long amount);

    /** Hook for reading pre-{@code TAG_STORAGE} save layouts; default is a no-op. */
    protected void readLegacyStorage(HolderLookup.Provider provider, CompoundTag nbt) {

    }
    // endregion

    // region TICK
    @Override
    public void tick() {

        if (distList.size() != getNodes().size()) {
            distList = List.copyOf(getNodes().values());
        }
        if (attachmentNodesDirty) {
            rebuildAttachmentNodeList();
        }
        for (N node : attachmentNodeList) {
            if (node.isLoaded()) {
                node.attachmentTick();
            }
        }
        renderUpdate();
        overflowWatchdog.check(world, getId(), overflowBuffer);
        distributeOutput();
    }

    /**
     * Per-tick output distribution: the held content is split mathematically equally across every
     * endpoint connection of the grid (base share each, the remainder units go to a rotating window
     * of connections), then whatever endpoints rejected is re-offered once in rotating order so
     * uneven acceptance still reaches full throughput. Replaces the old round-robin walk where the
     * first-served connections could drain the whole grid.
     */
    private void distributeOutput() {

        S held = heldContent();
        if (contentOps.isEmpty(held)) {
            return;
        }
        long total = heldAmountLong();
        if (total <= 0) {
            return;
        }
        outputNodes.clear();
        outputHosts.clear();
        candidateNode.clear();
        candidateConn.clear();
        int connectionTotal = 0;
        for (N node : distList) {
            if (!node.isLoaded()) {
                continue;
            }
            int count = node.outputConnectionCount();
            if (count <= 0) {
                continue;
            }
            IDuct<?, ?> host = node.hostDuct();
            if (host == null) {
                continue;
            }
            int ordinal = outputNodes.size();
            outputNodes.add(node);
            outputHosts.add(host);
            for (int connection = 0; connection < count; ++connection) {
                candidateNode.add(ordinal);
                candidateConn.add(connection);
            }
            connectionTotal += count;
        }
        if (connectionTotal == 0) {
            return;
        }
        int offset = Math.floorMod(distIndex++, connectionTotal);
        long base = total / connectionTotal;
        long extra = total % connectionTotal;
        long acceptedTotal = 0;
        isSendingContent = true;
        try {
            for (int i = 0; i < connectionTotal; ++i) {
                int rotated = i - offset;
                if (rotated < 0) {
                    rotated += connectionTotal;
                }
                long request = base + (rotated < extra ? 1 : 0);
                if (request <= 0) {
                    continue;
                }
                int ordinal = candidateNode.getInt(i);
                acceptedTotal += outputNodes.get(ordinal).fillConnection(outputHosts.get(ordinal), candidateConn.getInt(i), held, request, true);
            }
            long remaining = total - acceptedTotal;
            for (int step = 0; step < connectionTotal && remaining > 0; ++step) {
                int i = offset + step;
                if (i >= connectionTotal) {
                    i -= connectionTotal;
                }
                int ordinal = candidateNode.getInt(i);
                long accepted = outputNodes.get(ordinal).fillConnection(outputHosts.get(ordinal), candidateConn.getInt(i), held, remaining, true);
                remaining -= accepted;
                acceptedTotal += accepted;
            }
        } finally {
            isSendingContent = false;
            outputNodes.clear();
            outputHosts.clear();
        }
        if (acceptedTotal > 0) {
            drainHeld(acceptedTotal);
        }
    }

    private void renderUpdate() {

        S held = heldContent();
        if (renderState.tick(world, held, renderAlpha(held))) {
            updateHosts();
        }
    }

    protected final void rebuildAttachmentNodeList() {

        List<N> tickableNodes = new ArrayList<>();
        for (N node : getNodes().values()) {
            if (node.needsAttachmentTick()) {
                tickableNodes.add(node);
            }
        }
        attachmentNodeList = List.copyOf(tickableNodes);
        attachmentNodesDirty = false;
    }
    // endregion

    // region TOPOLOGY
    @Override
    public void onModified() {

        distList = List.of();
        nodeList = List.of();
        attachmentNodeList = List.of();
        attachmentNodesDirty = true;
        renderState.requestUpdate();
        recalculateCapacity();
        super.onModified();
    }

    @Override
    public void onAttachmentsChanged() {

        attachmentNodesDirty = true;
    }

    @Override
    public boolean canMerge(G from) {

        S held = heldContent();
        S fromHeld = from.heldContent();
        if (!contentOps.isEmpty(held) && !contentOps.isEmpty(fromHeld) && !contentOps.sameType(held, fromHeld)) {
            return false;
        }
        return from.heldAmountLong() <= overflowHeadroom();
    }

    @Override
    public void onMerge(G from) {

        long total = heldAmountLong() + from.heldAmountLong();
        S type = contentOps.isEmpty(heldContent()) ? from.heldContent() : heldContent();
        overflowBuffer.clear();
        from.overflowBuffer.clear();
        recalculateCapacity();
        long intoStorage = Math.min(total, storageCapacity());
        setStored(type, intoStorage);
        long remainder = total - intoStorage;
        if (!contentOps.isEmpty(type) && remainder > 0) {
            overflowBuffer.add(type, remainder);
        }
        if (!overflowBuffer.isEmpty()) {
            noteOverflowParked();
        }
        renderState.requestUpdate();
        refreshCapabilities();
        from.refreshCapabilities();
    }

    @Override
    public void onSplit(List<G> others) {

        long totalDucts = 0;
        for (G grid : others) {
            totalDucts = saturatingAdd(totalDucts, grid.getDuctCountLong());
            grid.recalculateCapacity();
            grid.renderState.requestUpdate();
            grid.refreshCapabilities();
        }
        refreshCapabilities();
        renderState.requestUpdate();
        long total = heldAmountLong();
        if (contentOps.isEmpty(heldContent()) || totalDucts == 0 || total <= 0) {
            return;
        }
        S type = heldContent();
        long perDuct = total / totalDucts;
        long remainder = total % totalDucts;
        long placed = 0;
        for (G grid : others) {
            long share = saturatingMultiply(perDuct, grid.getDuctCountLong());
            long extra = Math.min(remainder, Math.max(0L, grid.overflowHeadroom()));
            share = saturatingAdd(share, extra);
            remainder -= extra;
            if (share <= 0) {
                continue;
            }
            long intoStorage = Math.min(share, grid.storageCapacity());
            grid.setStored(type, intoStorage);
            long intoBuffer = share - intoStorage;
            long parked = intoBuffer <= 0 ? 0 : grid.overflowBuffer.add(type, intoBuffer);
            placed += intoStorage + parked;
            if (parked > 0) {
                grid.noteOverflowParked();
            }
        }
        if (remainder > 0 && !others.isEmpty()) {
            long parked = others.get(0).overflowBuffer.add(type, remainder);
            placed += parked;
            if (parked > 0) {
                others.get(0).noteOverflowParked();
            }
        }
        if (placed != total) {
            ThermalDynamics.LOG.error("{} grid split placed {} of {}", contentName, placed, total);
        }
        overflowBuffer.clear();
        setStored(contentOps.empty(), 0);
    }
    // endregion

    // region NBT
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        CompoundTag tag = super.serializeNBT(provider);
        // Content data must be nested: stack serialization uses the "id" key, which would otherwise
        // overwrite the grid UUID that GridContainer stores under "id".
        tag.put(TAG_STORAGE, saveStorage(provider));
        if (!overflowBuffer.isEmpty()) {
            tag.put(TAG_OVERFLOW, overflowBuffer.serializeNBT(provider));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

        super.deserializeNBT(provider, nbt);
        recalculateCapacity();
        if (nbt.contains(TAG_STORAGE, CompoundTag.TAG_COMPOUND)) {
            loadStorage(provider, nbt.getCompound(TAG_STORAGE));
        } else {
            readLegacyStorage(provider, nbt);
        }
        overflowBuffer.clear();
        if (nbt.contains(TAG_OVERFLOW, CompoundTag.TAG_COMPOUND)) {
            overflowBuffer.deserializeNBT(provider, nbt.getCompound(TAG_OVERFLOW));
        }
        S stored = storedStack();
        if (!overflowBuffer.isEmpty()) {
            if (!contentOps.isEmpty(stored) && !contentOps.sameType(stored, overflowBuffer.type())) {
                ThermalDynamics.LOG.error("{} grid {} loaded incompatible overflow; discarding it", contentName, getId());
                overflowBuffer.clear();
            } else if (overflowBuffer.getAmount() > Long.MAX_VALUE - contentOps.amount(stored)) {
                ThermalDynamics.LOG.error("{} grid {} loaded overflow beyond long headroom; truncating", contentName, getId());
                overflowBuffer.drain(overflowBuffer.getAmount() - (Long.MAX_VALUE - contentOps.amount(stored)));
            }
        }
        if (!overflowBuffer.isEmpty()) {
            noteOverflowParked();
        }
    }
    // endregion

    // region CONTENT
    /** The representative held stack: the storage content, or a view of the overflow when the tank is empty. */
    public final S heldContent() {

        return contentOps.isEmpty(storedStack()) ? overflowBuffer.peek(1) : storedStack();
    }

    public final long heldAmountLong() {

        return contentOps.amount(storedStack()) + overflowBuffer.getAmount();
    }

    public final long overflowHeadroom() {

        return Long.MAX_VALUE - heldAmountLong();
    }

    public final OverflowBuffer<S> getOverflowBuffer() {

        return overflowBuffer;
    }

    public final void noteOverflowParked() {

        overflowWatchdog.notePark(world);
    }

    public final int getRenderAlpha() {

        return renderState.renderAlpha();
    }

    /**
     * Round-robin distribution of overflow into the grid's node endpoints. Returns the amount
     * accepted. Reentrancy is the caller's concern via {@link #isSendingContent}.
     */
    protected final long distributeOverflow(S resource, long overflow, boolean execute) {

        List<N> list = nodeList;
        if (list.size() != getNodes().size()) {
            list = List.copyOf(getNodes().values());
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.isEmpty()) {
            return 0;
        }
        int tempTracker = nodeTracker;
        long toSend = overflow;
        visitedTargets.clear();
        isSendingContent = true;
        try {
            for (int i = nodeTracker; i < list.size() && toSend > 0; ++i) {
                N node = list.get(i);
                if (!node.isLoaded()) {
                    continue;
                }
                toSend -= node.transmit(resource, toSend, execute, visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            for (int i = 0; i < list.size() && i < nodeTracker && toSend > 0; ++i) {
                N node = list.get(i);
                if (!node.isLoaded()) {
                    continue;
                }
                toSend -= node.transmit(resource, toSend, execute, visitedTargets);
                if (toSend == 0) {
                    nodeTracker = i + 1;
                }
            }
            if (toSend > 0) {
                ++nodeTracker;
            }
            if (nodeTracker >= list.size()) {
                nodeTracker = 0;
            }
            if (!execute) {
                nodeTracker = tempTracker;
            }
        } finally {
            isSendingContent = false;
            visitedTargets.clear();
        }
        return overflow - toSend;
    }

    protected final void recalculateCapacity() {

        setStorageCapacity(saturatingMultiply(getDuctCountLong(), ductCapacity()));
    }

    protected final long getDuctCountLong() {

        long count = nodeGraph.nodes().size();
        for (EndpointPair<N> edge : nodeGraph.edges()) {
            count = saturatingAdd(count, GridHelper.numBetween(edge.nodeU().getPos(), edge.nodeV().getPos()));
        }
        return count;
    }
    // endregion

    // region MATH
    protected static int saturatingInt(long value) {

        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    protected static long saturatingAdd(long first, long second) {

        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    protected static long saturatingMultiply(long first, long second) {

        return first == 0 || second == 0 || first <= Long.MAX_VALUE / second ? first * second : Long.MAX_VALUE;
    }
    // endregion

}
