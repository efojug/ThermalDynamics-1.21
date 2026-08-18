package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.block.entity.duct.DuctBlockEntity;
import cofh.thermal.dynamics.common.grid.Grid;
import com.google.common.graph.EndpointPair;
import cofh.lib.util.TimeTracker;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_GRID;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;

/**
 * Chemical equivalent of the superconducting Fluiduct grid.
 */
public class ChemicalGrid extends Grid<ChemicalGrid, ChemicalGridNode> implements IChemicalHandler {

    private static final long DUCT_CAPACITY = 5_000L;
    private static final String TAG_STORAGE = "Storage";
    private static final long RENDER_AMOUNT = 1L;
    private static final int MIN_RENDER_ALPHA = Math.round(0.2F * 255.0F);

    private final ChemicalGridStorage storage = new ChemicalGridStorage(0);
    private ChemicalStack renderChemical = ChemicalStack.EMPTY;
    private int renderAlpha = 0xFF;
    private final TimeTracker timeTracker = new TimeTracker();
    private boolean wasFilled;
    private boolean needsUpdate;
    private ChemicalGridNode[] distArray = new ChemicalGridNode[0];
    private int distIndex;
    private ChemicalGridNode[] attachmentNodeList = new ChemicalGridNode[0];
    private boolean attachmentNodesDirty = true;
    private ChemicalGridNode[] nodeList = new ChemicalGridNode[0];
    private int nodeTracker;
    private boolean isSendingChemical;
    private final ObjectOpenHashSet<BlockPos> visitedTargets = new ObjectOpenHashSet<>();

    public ChemicalGrid(UUID id, Level world) {

        super(CHEMICAL_GRID.get(), id, world);
    }

    @Override
    public ChemicalGridNode newNode() {

        return new ChemicalGridNode(this);
    }

    @Override
    public void tick() {

        if (distArray.length != getNodes().size()) {
            distArray = getNodes().values().toArray(new ChemicalGridNode[0]);
        }
        if (attachmentNodesDirty) {
            rebuildAttachmentNodeList();
        }
        int curIndex = distIndex;
        if (distIndex >= distArray.length) {
            distIndex = 0;
        }
        for (ChemicalGridNode node : attachmentNodeList) {
            if (node.isLoaded()) {
                node.attachmentTick();
            }
        }
        renderUpdate();
        for (int i = distIndex; i < distArray.length; ++i) {
            if (roundRobinDistributionTick(curIndex, i)) {
                return;
            }
        }
        for (int i = 0; i < distIndex; ++i) {
            if (roundRobinDistributionTick(curIndex, i)) {
                return;
            }
        }
        ++distIndex;
    }

    private boolean roundRobinDistributionTick(int curIndex, int index) {

        ChemicalGridNode node = distArray[index];
        if (!node.isLoaded()) {
            return false;
        }
        node.distributionTick();
        if (getChemical().isEmpty()) {
            distIndex = index + 1;
            if (curIndex == distIndex) {
                --distIndex;
            }
            return true;
        }
        return false;
    }

    @Override
    public void onModified() {

        distArray = new ChemicalGridNode[0];
        nodeList = new ChemicalGridNode[0];
        attachmentNodeList = new ChemicalGridNode[0];
        attachmentNodesDirty = true;
        needsUpdate = true;
        recalculateCapacity();
        super.onModified();
    }

    @Override
    public void onAttachmentsChanged() {

        attachmentNodesDirty = true;
    }

    @Override
    public boolean canMerge(ChemicalGrid from) {

        ChemicalStack chemical = getChemical();
        ChemicalStack fromChemical = from.getChemical();
        return chemical.isEmpty() || fromChemical.isEmpty() || ChemicalStack.isSameChemical(chemical, fromChemical);
    }

    @Override
    public void onMerge(ChemicalGrid from) {

        ChemicalStack source = storage.getChemical().isEmpty() ? from.storage.getChemical() : storage.getChemical();
        long amount = saturatingAdd(storage.getChemical().getAmount(), from.storage.getChemical().getAmount());
        recalculateCapacity();
        storage.setChemical(source.isEmpty() ? ChemicalStack.EMPTY : source.copyWithAmount(Math.min(amount, storage.getCapacity())));
        needsUpdate = true;
        refreshCapabilities();
        from.refreshCapabilities();
    }

    @Override
    public void onSplit(List<ChemicalGrid> others) {

        long totalDucts = 0;
        for (ChemicalGrid grid : others) {
            totalDucts = saturatingAdd(totalDucts, grid.getDuctCount());
            grid.recalculateCapacity();
            grid.needsUpdate = true;
            grid.refreshCapabilities();
        }
        refreshCapabilities();
        needsUpdate = true;
        if (getChemical().isEmpty() || totalDucts == 0) {
            return;
        }
        long perDuct = getChemical().getAmount() / totalDucts;
        long remainder = getChemical().getAmount() % totalDucts;
        for (ChemicalGrid grid : others) {
            grid.setChemical(getChemical().copyWithAmount(Math.min(grid.getCapacity(), saturatingMultiply(perDuct, grid.getDuctCount()))));
        }
        for (ChemicalGrid grid : others) {
            long available = grid.getCapacity() - grid.getChemical().getAmount();
            if (available <= 0) {
                continue;
            }
            long toAdd = Math.min(available, remainder);
            grid.setChemical(getChemical().copyWithAmount(grid.getChemical().getAmount() + toAdd));
            remainder -= toAdd;
            if (remainder == 0) {
                break;
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        CompoundTag tag = super.serializeNBT(provider);
        tag.put(TAG_STORAGE, storage.serializeNBT(provider));
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

        super.deserializeNBT(provider, nbt);
        recalculateCapacity();
        storage.deserializeNBT(provider, nbt.getCompound(TAG_STORAGE));
    }

    @Override
    public boolean canConnectOnSide(BlockEntity tile, @Nullable Direction dir) {

        return GridHelper.getGridHost(tile) == null && dir != null && tile.getLevel() != null &&
                tile.getLevel().getCapability(CHEMICAL_HANDLER, tile.getBlockPos(), tile.getBlockState(), tile, dir) != null;
    }

    @Nullable
    @SuppressWarnings ("unchecked")
    public <T, C> T getCapability(BlockCapability<T, C> capability) {

        return capability == CHEMICAL_HANDLER ? (T) this : null;
    }

    @Override
    public void refreshCapabilities() {

        for (var entry : getNodes().entrySet()) {
            if (!entry.getValue().isLoaded()) {
                continue;
            }
            if (getLevel().getBlockEntity(entry.getKey()) instanceof DuctBlockEntity<?, ?> duct) {
                duct.invalidateAttachments();
            }
            getLevel().invalidateCapabilities(entry.getKey());
        }
    }

    public long getCapacity() {

        return storage.getCapacity();
    }

    public ChemicalStack getChemical() {

        return storage.getChemical();
    }

    public ChemicalStack getRenderChemical() {

        return renderChemical;
    }

    public int getRenderAlpha() {

        return renderAlpha;
    }

    public void setChemical(ChemicalStack chemical) {

        storage.setChemical(chemical);
    }

    public ChemicalStack extractChemical(long amount, Action action) {

        return storage.extract(amount, action);
    }

    @Override
    public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {

        if (tank != 0) {
            return stack;
        }
        return insertChemical(stack, action);
    }

    @Override
    public ChemicalStack insertChemical(ChemicalStack resource, Action action) {

        if (resource.isEmpty() || isSendingChemical || !storage.getChemical().isEmpty() && !ChemicalStack.isSameChemical(storage.getChemical(), resource)) {
            return resource;
        }
        long added = storage.insert(resource, action);
        long overflow = resource.getAmount() - added;
        if (overflow <= 0) {
            return ChemicalStack.EMPTY;
        }
        ChemicalGridNode[] list = nodeList;
        if (list.length != getNodes().size()) {
            list = getNodes().values().toArray(new ChemicalGridNode[0]);
            nodeList = list;
            nodeTracker = 0;
        }
        if (list.length == 0) {
            return resource.copyWithAmount(overflow);
        }
        int tempTracker = nodeTracker;
        long remaining = overflow;
        visitedTargets.clear();
        isSendingChemical = true;
        try {
            for (int i = nodeTracker; i < list.length && remaining > 0; ++i) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                remaining -= list[i].transmitChemical(resource, remaining, action, visitedTargets);
                if (remaining == 0) {
                    nodeTracker = i + 1;
                }
            }
            for (int i = 0; i < list.length && i < nodeTracker && remaining > 0; ++i) {
                if (!list[i].isLoaded()) {
                    continue;
                }
                remaining -= list[i].transmitChemical(resource, remaining, action, visitedTargets);
                if (remaining == 0) {
                    nodeTracker = i + 1;
                }
            }
            if (remaining > 0) {
                ++nodeTracker;
            }
            if (nodeTracker >= list.length) {
                nodeTracker = 0;
            }
            if (action.simulate()) {
                nodeTracker = tempTracker;
            }
        } finally {
            isSendingChemical = false;
            visitedTargets.clear();
        }
        return remaining == 0 ? ChemicalStack.EMPTY : resource.copyWithAmount(remaining);
    }

    @Override
    public ChemicalStack extractChemical(int tank, long amount, Action action) {

        return tank == 0 ? storage.extract(amount, action) : ChemicalStack.EMPTY;
    }

    @Override
    public int getChemicalTanks() {

        return storage.getChemicalTanks();
    }

    @Override
    public ChemicalStack getChemicalInTank(int tank) {

        return storage.getChemicalInTank(tank);
    }

    @Override
    public void setChemicalInTank(int tank, ChemicalStack stack) {

        storage.setChemicalInTank(tank, stack);
    }

    @Override
    public long getChemicalTankCapacity(int tank) {

        return storage.getChemicalTankCapacity(tank);
    }

    @Override
    public boolean isValid(int tank, ChemicalStack stack) {

        return storage.isValid(tank, stack);
    }

    private void rebuildAttachmentNodeList() {

        List<ChemicalGridNode> nodes = new ArrayList<>();
        for (ChemicalGridNode node : getNodes().values()) {
            if (node.needsAttachmentTick()) {
                nodes.add(node);
            }
        }
        attachmentNodeList = nodes.toArray(new ChemicalGridNode[0]);
        attachmentNodesDirty = false;
    }

    private void recalculateCapacity() {

        storage.setCapacity(saturatingMultiply(getDuctCount(), DUCT_CAPACITY));
    }

    private void renderUpdate() {

        ChemicalStack chemical = getChemical();
        boolean renderChemicalChanged = !ChemicalStack.isSameChemical(renderChemical, chemical);
        int updatedRenderAlpha = getRenderAlpha(chemical);
        boolean renderAlphaChanged = renderAlpha != updatedRenderAlpha;
        if (renderChemicalChanged) {
            renderChemical = chemical.isEmpty() ? ChemicalStack.EMPTY : chemical.copyWithAmount(RENDER_AMOUNT);
        }
        renderAlpha = updatedRenderAlpha;
        if (renderChemicalChanged || renderAlphaChanged || wasFilled && timeTracker.hasDelayPassed(world, 40) || needsUpdate) {
            if (!wasFilled && renderChemical.isEmpty()) {
                timeTracker.markTime(world);
                wasFilled = true;
                return;
            }
            updateHosts();
            wasFilled = false;
            needsUpdate = false;
        }
    }

    private int getRenderAlpha(ChemicalStack chemical) {

        if (chemical.isEmpty() || storage.getCapacity() <= 0) {
            return 0xFF;
        }
        float scale = Math.min(1.0F, chemical.getAmount() / (float) storage.getCapacity());

        // Matches Mekanism's pressurized tube opacity while avoiding imperceptible state updates.
        return Math.max(MIN_RENDER_ALPHA, Math.round(scale * 255.0F));
    }

    private long getDuctCount() {

        long count = nodeGraph.nodes().size();
        for (EndpointPair<ChemicalGridNode> edge : nodeGraph.edges()) {
            count = saturatingAdd(count, GridHelper.numBetween(edge.nodeU().getPos(), edge.nodeV().getPos()));
        }
        return count;
    }

    private static long saturatingAdd(long first, long second) {

        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private static long saturatingMultiply(long first, long second) {

        return first == 0 || second == 0 || first <= Long.MAX_VALUE / second ? first * second : Long.MAX_VALUE;
    }

}
