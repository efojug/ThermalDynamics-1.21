package cofh.thermal.dynamics.compat.mekanism.grid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.grid.GridNode;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import java.util.Set;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.dynamics.api.grid.IDuct.ConnectionType.DISABLED;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_HANDLER;

public class ChemicalGridNode extends GridNode<ChemicalGrid> implements ITickableGridNode {

    protected ChemicalConnection[] distArray = new ChemicalConnection[0];
    protected int distIndex;

    protected ChemicalGridNode(ChemicalGrid grid) {

        super(grid);
    }

    protected void cacheConnections() {

        Level world = getWorld();
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        connections.clear();
        for (Direction dir : DIRECTIONS) {
            BlockPos targetPos = pos.relative(dir);
            if (world.isLoaded(targetPos) && grid.canConnectOnSide(targetPos, dir.getOpposite())) {
                connections.add(dir);
            }
        }
        distArray = connections.stream().map(dir -> new ChemicalConnection(serverLevel, dir, pos.relative(dir))).toArray(ChemicalConnection[]::new);
        cached = true;
    }

    @Override
    public void attachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return;
        }
        for (Direction dir : DIRECTIONS) {
            IAttachment attachment = duct.getAttachment(dir);
            if (attachment.needsTick()) {
                attachment.tick();
            }
        }
    }

    public boolean needsAttachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return false;
        }
        for (Direction dir : DIRECTIONS) {
            if (duct.getAttachment(dir).needsTick()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void distributionTick() {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null || distArray.length == 0) {
            return;
        }
        ++distIndex;
        distIndex %= distArray.length;
        for (int i = distIndex; i < distArray.length; ++i) {
            tickDir(duct, distArray[i]);
        }
        for (int i = 0; i < distIndex; ++i) {
            tickDir(duct, distArray[i]);
        }
    }

    public long transmitChemical(ChemicalStack chemical, long amount, Action action, Set<BlockPos> visitedTargets) {

        if (!cached) {
            cacheConnections();
        }
        IDuct<?, ?> duct = gridHost();
        if (duct == null || distArray.length == 0 || chemical.isEmpty() || amount <= 0) {
            return 0;
        }
        long remaining = amount;
        int tempIndex = distIndex;
        ++distIndex;
        distIndex %= distArray.length;
        for (int i = distIndex; i < distArray.length && remaining > 0; ++i) {
            remaining -= fillDir(duct, distArray[i], chemical, remaining, action, visitedTargets);
        }
        for (int i = 0; i < distIndex && remaining > 0; ++i) {
            remaining -= fillDir(duct, distArray[i], chemical, remaining, action, visitedTargets);
        }
        if (action.simulate()) {
            distIndex = tempIndex;
        }
        return amount - remaining;
    }

    private void tickDir(IDuct<?, ?> duct, ChemicalConnection connection) {

        ChemicalStack chemical = grid.getChemical();
        long accepted = fillDir(duct, connection, chemical, chemical.getAmount(), Action.EXECUTE, null);
        if (accepted > 0) {
            grid.extractChemical(accepted, Action.EXECUTE);
        }
    }

    private long fillDir(IDuct<?, ?> duct, ChemicalConnection connection, ChemicalStack chemical, long amount, Action action, Set<BlockPos> visitedTargets) {

        Direction dir = connection.direction;
        if (duct.getConnectionType(dir) == DISABLED || chemical.isEmpty() || amount <= 0) {
            return 0;
        }
        if (visitedTargets != null && visitedTargets.contains(connection.targetPos)) {
            return 0;
        }
        IAttachment attachment = duct.getAttachment(dir);
        if (connection.consumeInvalidation()) {
            attachment.invalidate();
        }
        IChemicalHandler handler = attachment.wrapExternalCapability(CHEMICAL_HANDLER, connection.capabilityCache.getCapability());
        if (handler == null) {
            return 0;
        }
        long accepted = amount - handler.insertChemical(chemical.copyWithAmount(amount), action).getAmount();
        if (accepted > 0 && visitedTargets != null) {
            visitedTargets.add(connection.targetPos);
        }
        return accepted;
    }

    private final class ChemicalConnection {

        private final Direction direction;
        private final BlockPos targetPos;
        private final BlockCapabilityCache<IChemicalHandler, Direction> capabilityCache;
        private boolean invalidated;

        private ChemicalConnection(ServerLevel world, Direction direction, BlockPos targetPos) {

            this.direction = direction;
            this.targetPos = targetPos;
            capabilityCache = BlockCapabilityCache.create(CHEMICAL_HANDLER, world, targetPos, direction.getOpposite(),
                    () -> cached && isLoaded(), () -> invalidated = true);
        }

        private boolean consumeInvalidation() {

            boolean result = invalidated;
            invalidated = false;
            return result;
        }

    }

}
