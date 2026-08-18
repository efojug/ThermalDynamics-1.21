package cofh.thermal.dynamics.common.grid;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.helper.GridHelper;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import java.util.EnumSet;

import static cofh.lib.util.Constants.DIRECTIONS;

/**
 * Represents a Node on a {@link Grid} at a given position.
 * <p>
 *
 * @author covers1624
 */
public abstract class GridNode<G extends Grid<G, ?>> implements INBTSerializable<CompoundTag> {

    protected final EnumSet<Direction> connections = EnumSet.noneOf(Direction.class);
    protected G grid;
    protected BlockPos pos = BlockPos.ZERO;
    protected boolean loaded;
    protected boolean cached;

    protected GridNode(G grid) {

        this.grid = grid;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {

        return new CompoundTag();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {

    }

    public void onGridChange(G oldGrid) {

    }

    public final void clearConnections() {

        connections.clear();
        cached = false;
    }

    /**
     * Gets the grid which this node belongs to.
     *
     * @return The grid.
     */
    public final G getGrid() {

        return grid;
    }

    /**
     * Gets the position in world this {@link GridNode} exists in.
     *
     * @return The node's position.
     */
    public final BlockPos getPos() {

        return pos;
    }

    /**
     * Flag returning if the node is loaded.
     *
     * @return The node's loaded state.
     */
    public final boolean isLoaded() {

        return loaded;
    }

    /**
     * The external connections this Node has.
     *
     * @return The directions this node has external connections to.
     */
    public final EnumSet<Direction> getConnections() {

        return connections;
    }

    @Nullable
    protected IDuct<?, ?> gridHost() {

        return GridHelper.getGridHost(getWorld(), getPos());
    }

    /** Default attachment tick shared by node types: tick every attachment that requests it. */
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

    /** Whether any attachment on this node's host currently requests ticking. */
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

    //@formatter:off
    public final Level getWorld() { return grid.getLevel(); }
    public void setPos(BlockPos pos) { this.pos = pos; }
    public void setGrid(G grid) { this.grid = grid; }
    public void setLoaded(boolean loaded) { this.loaded = loaded; }
    //@formatter:on
}
