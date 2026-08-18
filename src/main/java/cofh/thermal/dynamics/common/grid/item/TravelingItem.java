package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.client.ClientTravelingItemIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A single item stack travelling through an item duct network. */
public class TravelingItem {

    public static final int DUCT_LENGTH = 100;
    public static final int IMPULSE_SPEED = DUCT_LENGTH;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int RETRY_INTERVAL = 2;
    /** Ticks a predicted client copy may stall at a missing neighbor duct before it is dropped. */
    private static final int CLIENT_STALL_TIMEOUT = 40;

    private enum State { ROUTING, RETURNING, WAITING }

    private ItemStack stack = ItemStack.EMPTY;
    private final List<Direction> path = new ArrayList<>();
    private int routeIndex;
    private int progress;
    private int age;
    private final int speed = IMPULSE_SPEED;
    private BlockPos source = BlockPos.ZERO;
    private Direction sourceSide = Direction.DOWN;
    private BlockPos destination = BlockPos.ZERO;
    private Direction destinationSide = Direction.DOWN;
    private Direction incomingDirection = Direction.UP;
    private State state = State.ROUTING;
    private int retryTicks;
    private UUID id = UUID.randomUUID();
    private long syncRevision;
    private long lastServerTick = Long.MIN_VALUE;
    private long lastClientTick = Long.MIN_VALUE;
    private boolean waitForFirstTick;
    // Client-only, never serialized: phase convergence and ghost cleanup for the predicted copy.
    private boolean holdOneClientTick;
    private int clientStallTicks;
    // Server-only, never serialized: the external target this item currently reserves capacity at.
    @org.jetbrains.annotations.Nullable
    private BlockPos reservedTarget;

    public TravelingItem(ItemStack stack, BlockPos source, Direction sourceSide, ItemRoute route) {
        this.stack = stack.copy();
        this.source = source.immutable();
        this.sourceSide = sourceSide;
        this.incomingDirection = sourceSide.getOpposite();
        this.waitForFirstTick = true;
        setRoute(route);
    }

    private TravelingItem() { }

    public ItemStack stack() { return stack; }
    public int progress() { return progress; }
    public int speed() { return speed; }
    public int routeIndex() { return routeIndex; }
    public int age() { return age; }
    public Direction direction() { return routeIndex < path.size() ? path.get(routeIndex) : destinationSide; }
    public Direction incomingDirection() { return incomingDirection; }
    public UUID id() { return id; }
    public long syncRevision() { return syncRevision; }
    public long bumpSyncRevision() { return ++syncRevision; }
    public boolean waiting() { return state == State.WAITING; }
    public boolean returning() { return state == State.RETURNING; }

    // region RESERVATION BOOKKEEPING (server side, maintained by ItemGrid)
    @org.jetbrains.annotations.Nullable
    BlockPos reservedTarget() { return reservedTarget; }

    void clearReservedTarget() { reservedTarget = null; }

    /** Recomputes and records the reserved external target; null while waiting. */
    @org.jetbrains.annotations.Nullable
    BlockPos updateReservedTarget() {
        reservedTarget = state == State.WAITING ? null : destination.relative(destinationSide);
        return reservedTarget;
    }
    // endregion

    /**
     * Prevents the client tick that immediately follows a freshly applied authoritative snapshot
     * from advancing it again. Keyed on {@link ClientTravelingItemIndex#currentTick()}: the counter
     * value observed while packets are applied between level ticks equals the value the next level
     * tick observes, so stamping the current value suppresses exactly the imminent tick — immune to
     * the server-synced game time jumping.
     */
    public void deferClientTick() {

        lastClientTick = ClientTravelingItemIndex.currentTick();
    }

    /**
     * Whether the renderer may extrapolate this copy by partialTick in the current interval.
     * False while the upcoming client tick is suppressed (freshly applied snapshot or phase hold):
     * a suppressed tick means the item will not move at the next boundary, so extrapolating would
     * render a sweep that replays once the real advancing tick runs.
     */
    public boolean clientAnimating() {

        return !holdOneClientTick && lastClientTick < ClientTravelingItemIndex.currentTick();
    }

    /** Returns whether this in-flight stack reserves capacity at the given external target. */
    public boolean reservesTarget(BlockPos target) {

        return state != State.WAITING && destination.relative(destinationSide).equals(target);
    }

    public void tick(ItemDuctBlockEntity duct) {
        if (stack.isEmpty()) { duct.removeTravelingItem(this); return; }
        if (state == State.WAITING) { tickWaiting(duct); return; }
        long gameTime = duct.getLevel().getGameTime();
        if (lastServerTick == gameTime) return;
        lastServerTick = gameTime;
        ++age;
        if (waitForFirstTick) { waitForFirstTick = false; return; }
        if (routeIndex >= path.size()) {
            progress += speed;
            if (progress >= DUCT_LENGTH) { progress %= DUCT_LENGTH; finishAtEndpoint(duct); }
            return;
        }
        progress += speed;
        if (progress < DUCT_LENGTH) return;
        Direction direction = path.get(routeIndex);
        ItemDuctBlockEntity next = duct.getConnectedDuct(direction);
        if (next == null) { reroute(duct); return; }
        // At full-duct speed any progress remainder is pure render phase (hop cadence is one duct
        // per tick regardless); drop it so items resumed mid-duct render smoothly from here on.
        progress = speed >= DUCT_LENGTH ? 0 : progress % DUCT_LENGTH;
        incomingDirection = direction;
        ++routeIndex;
        duct.moveTravelingItemTo(next, this);
    }

    public void clientTick(ItemDuctBlockEntity duct) {
        if (waiting()) return;
        if (stack.isEmpty()) {
            ClientTravelingItemIndex.removePredicted(duct.getLevel(), duct, this);
            return;
        }
        long now = ClientTravelingItemIndex.currentTick();
        if (lastClientTick >= now) return;
        lastClientTick = now;
        if (holdOneClientTick) {
            // A server snapshot showed this copy one hop ahead; dwell one tick so the hop counters
            // converge without a visible position change.
            holdOneClientTick = false;
            return;
        }
        if (routeIndex >= path.size()) {
            if (progress >= DUCT_LENGTH) {
                // Parked a full tick at the endpoint face with no server verdict; give up the copy.
                ClientTravelingItemIndex.removePredicted(duct.getLevel(), duct, this);
            } else if (progress + speed >= DUCT_LENGTH) {
                // Park at the endpoint face and await the server's verdict: a removal (delivered)
                // or a route update (bounced). Guessing removal here left a tombstone that the
                // bounce update then raced against, eating the turn-around animation.
                progress = DUCT_LENGTH;
            } else {
                progress += speed;
            }
            return;
        }
        progress += speed;
        if (progress < DUCT_LENGTH) return;
        ItemDuctBlockEntity next = duct.getAdjacentItemDuct(path.get(routeIndex));
        if (next == null) {
            progress = DUCT_LENGTH - 1;
            // The route continues into a duct this client cannot see. Server hops no longer send
            // removals, so a copy stranded at the loaded-area border must clean itself up; any
            // newer server update for the item revives it past the tombstone.
            if (++clientStallTicks > CLIENT_STALL_TIMEOUT) {
                ClientTravelingItemIndex.removePredicted(duct.getLevel(), duct, this);
            }
            return;
        }
        clientStallTicks = 0;
        progress = speed >= DUCT_LENGTH ? 0 : progress % DUCT_LENGTH;
        incomingDirection = path.get(routeIndex++);
        ClientTravelingItemIndex.movePredicted(duct.getLevel(), duct, next, this);
    }

    private void finishAtEndpoint(ItemDuctBlockEntity duct) {
        if (state == State.RETURNING && duct.getBlockPos().equals(source)) {
            stack = duct.insertIntoSourceEndpoint(sourceSide, stack);
            if (stack.isEmpty()) { duct.removeTravelingItem(this); return; }
            enterWaiting(duct);
            return;
        }
        if (state == State.ROUTING) {
            stack = duct.insertIntoEndpoint(destinationSide, stack);
            if (stack.isEmpty()) { duct.removeTravelingItem(this); return; }
            beginReturn(duct);
        } else {
            reroute(duct);
        }
    }

    private void beginReturn(ItemDuctBlockEntity duct) {
        state = State.RETURNING;
        ItemRoute route = duct.getGrid().findRouteToDuct(duct.getBlockPos(), source, sourceSide);
        if (route == null) { enterWaiting(duct); return; }
        setRoute(route);
        // Full-length traversal from the face the item bounced at: with incoming set to the new
        // travel direction the render sweep starts on the opposite face, so the turn-around is
        // continuous and later hops carry no half-duct phase that would stall at every boundary.
        progress = 0;
        incomingDirection = direction();
        duct.markTravelingItemUpdated(this);
    }

    private void enterWaiting(ItemDuctBlockEntity duct) {
        boolean wasWaiting = state == State.WAITING;
        state = State.WAITING;
        retryTicks = 0;
        progress = 0;
        if (!wasWaiting && duct.getGrid() != null) duct.getGrid().setItemWaiting(true, false);
        duct.markTravelingItemUpdated(this);
    }

    private void tickWaiting(ItemDuctBlockEntity duct) {
        if (++retryTicks < RETRY_INTERVAL) return;
        retryTicks = 0;

        // Normal outputs have priority. The original source is tried only after no other output
        // is available, and is never exposed as a generic target for other input attachments.
        ItemRoute route = duct.getGrid().findRoute(duct.getBlockPos(), Set.of(source.relative(sourceSide)), stack, null, null);
        if (route != null) {
            state = State.ROUTING;
            if (duct.getGrid() != null) duct.getGrid().setItemWaiting(false, true);
            setRoute(route);
            progress = DUCT_LENGTH / 2;
            duct.markTravelingItemUpdated(this);
            return;
        }

        if (duct.getBlockPos().equals(source)) {
            stack = duct.insertIntoSourceEndpoint(sourceSide, stack);
            if (stack.isEmpty()) {
                duct.removeTravelingItem(this);
            }
            return;
        }
        ItemRoute sourceRoute = duct.getGrid().findRouteToDuct(duct.getBlockPos(), source, sourceSide);
        if (sourceRoute == null) return;
        state = State.RETURNING;
        if (duct.getGrid() != null) duct.getGrid().setItemWaiting(false, true);
        setRoute(sourceRoute);
        progress = DUCT_LENGTH / 2;
        duct.markTravelingItemUpdated(this);
    }

    private void reroute(ItemDuctBlockEntity duct) {
        if (state == State.RETURNING) { enterWaiting(duct); return; }
        beginReturn(duct);
    }

    private void setRoute(ItemRoute route) {
        path.clear(); path.addAll(route.steps()); routeIndex = 0;
        destination = route.destination(); destinationSide = route.destinationSide();
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put("Stack", stack.save(provider, new CompoundTag()));
        byte[] sides = new byte[path.size()];
        for (int i = 0; i < path.size(); ++i) sides[i] = (byte) path.get(i).ordinal();
        tag.putByteArray("Path", sides);
        tag.putInt("RouteIndex", routeIndex); tag.putInt("Progress", progress); tag.putInt("Age", age);
        tag.putLong("Source", source.asLong()); tag.putByte("SourceSide", (byte) sourceSide.ordinal());
        tag.putLong("Destination", destination.asLong()); tag.putByte("DestinationSide", (byte) destinationSide.ordinal());
        tag.putByte("IncomingDirection", (byte) incomingDirection.ordinal());
        tag.putByte("State", (byte) state.ordinal()); tag.putInt("RetryTicks", retryTicks);
        tag.putUUID("Id", id); tag.putLong("SyncRevision", syncRevision);
        return tag;
    }

    public static TravelingItem load(CompoundTag tag, HolderLookup.Provider provider) {
        TravelingItem item = new TravelingItem();
        item.stack = ItemStack.parseOptional(provider, tag.getCompound("Stack"));
        byte[] rawPath = tag.getByteArray("Path");
        if (rawPath.length > MAX_PATH_LENGTH) throw new IllegalArgumentException("Traveling-item path exceeds " + MAX_PATH_LENGTH);
        for (byte raw : rawPath) {
            if (raw < 0 || raw >= Direction.values().length) throw new IllegalArgumentException("Invalid traveling-item direction: " + raw);
            item.path.add(Direction.values()[raw]);
        }
        item.routeIndex = Math.max(0, Math.min(tag.getInt("RouteIndex"), item.path.size()));
        item.progress = Math.max(0, tag.getInt("Progress")); item.age = Math.max(0, tag.getInt("Age"));
        item.source = BlockPos.of(tag.getLong("Source")); item.sourceSide = getDirection(tag.getByte("SourceSide"));
        item.destination = BlockPos.of(tag.getLong("Destination")); item.destinationSide = getDirection(tag.getByte("DestinationSide"));
        item.incomingDirection = getDirection(tag.getByte("IncomingDirection"));
        if (tag.contains("State", Tag.TAG_BYTE)) {
            int rawState = tag.getByte("State");
            item.state = rawState >= 0 && rawState < State.values().length ? State.values()[rawState] : State.ROUTING;
        } else if (tag.getBoolean("GoingToStuff")) {
            item.state = State.RETURNING;
        }
        item.retryTicks = Math.max(0, tag.getInt("RetryTicks"));
        if (tag.hasUUID("Id")) item.id = tag.getUUID("Id");
        item.syncRevision = Math.max(0L, tag.getLong("SyncRevision"));
        return item;
    }

    public record LoadResult(TravelingItem item, boolean repaired) { }

    public static LoadResult loadValidated(CompoundTag tag, HolderLookup.Provider provider) {
        boolean repaired = !tag.hasUUID("Id");
        if (!tag.contains("State", Tag.TAG_BYTE)) {
            repaired = true;
        } else {
            int rawState = tag.getByte("State");
            repaired |= rawState < 0 || rawState >= State.values().length;
        }
        int rawRouteIndex = tag.getInt("RouteIndex");
        int rawProgress = tag.getInt("Progress");
        int rawAge = tag.getInt("Age");
        int rawRetryTicks = tag.getInt("RetryTicks");
        repaired |= rawRouteIndex < 0 || rawProgress < 0 || rawAge < 0 || rawRetryTicks < 0;
        for (String key : List.of("SourceSide", "DestinationSide", "IncomingDirection")) {
            int rawDirection = tag.getByte(key);
            repaired |= rawDirection < 0 || rawDirection >= Direction.values().length;
        }
        repaired |= tag.getLong("SyncRevision") < 0;
        TravelingItem item = load(tag, provider);
        repaired |= rawRouteIndex != item.routeIndex;
        return new LoadResult(item, repaired);
    }

    /** How a server snapshot related to the local predicted state when it was merged. */
    public enum Reconciliation { IN_SYNC, PHASE_TOLERATED, RESYNC }

    /**
     * Merges an authoritative server snapshot into this client-side copy.
     * <p>
     * Server and client both advance items one duct per tick on unsynchronized clocks, so a
     * mid-flight snapshot routinely differs from the local prediction by exactly one hop of tick
     * phase. Re-seating the item to the server's duct in that situation is what used to make items
     * visibly teleport a full duct forward or backward at node transitions. Snapshots on the same
     * route and state within one hop of the prediction therefore keep the predicted position and
     * only merge non-positional data; anything further apart is a genuine divergence and is applied
     * verbatim.
     */
    public Reconciliation reconcileServerState(TravelingItem server) {
        if (server == null || !id.equals(server.id)) return Reconciliation.RESYNC;
        boolean sameEpoch = state == server.state && path.equals(server.path)
                && destination.equals(server.destination) && destinationSide == server.destinationSide
                && source.equals(server.source) && sourceSide == server.sourceSide;
        int hopDelta = routeIndex - server.routeIndex;
        stack = server.stack.copy();
        age = server.age;
        retryTicks = server.retryTicks;
        syncRevision = server.syncRevision;
        if (sameEpoch && state != State.WAITING && Math.abs(hopDelta) <= 1) {
            if (hopDelta != 0) {
                holdOneClientTick = hopDelta > 0;
                return Reconciliation.PHASE_TOLERATED;
            }
            holdOneClientTick = false;
            return Reconciliation.IN_SYNC;
        }
        path.clear(); path.addAll(server.path); routeIndex = server.routeIndex;
        progress = server.progress;
        source = server.source; sourceSide = server.sourceSide;
        destination = server.destination; destinationSide = server.destinationSide;
        incomingDirection = server.incomingDirection; state = server.state;
        holdOneClientTick = false;
        clientStallTicks = 0;
        return Reconciliation.RESYNC;
    }

    /** Whether this copy is traversing its final duct and will finish (and self-remove) within a tick. */
    public boolean inFinalStretch() {
        return state != State.WAITING && routeIndex >= path.size();
    }

    /**
     * Merges a lightweight positional heartbeat (just the server's route index) into this client
     * copy. Same contract as {@link #reconcileServerState}: within one hop of the prediction the
     * predicted position wins; beyond that the position is rebuilt from the locally known path —
     * a heartbeat can only arrive while the route itself is unchanged (route changes always ship a
     * full snapshot first).
     */
    public Reconciliation reconcileServerMove(int serverRouteIndex) {
        if (state == State.WAITING) return Reconciliation.IN_SYNC;
        int clamped = Math.max(0, Math.min(serverRouteIndex, path.size()));
        int hopDelta = routeIndex - clamped;
        if (Math.abs(hopDelta) <= 1) {
            if (hopDelta != 0) {
                holdOneClientTick = hopDelta > 0;
                return Reconciliation.PHASE_TOLERATED;
            }
            holdOneClientTick = false;
            return Reconciliation.IN_SYNC;
        }
        routeIndex = clamped;
        progress = 0;
        if (clamped > 0 && clamped - 1 < path.size()) {
            incomingDirection = path.get(clamped - 1);
        }
        holdOneClientTick = false;
        clientStallTicks = 0;
        return Reconciliation.RESYNC;
    }

    private static Direction getDirection(byte ordinal) {
        return ordinal >= 0 && ordinal < Direction.values().length ? Direction.values()[ordinal] : Direction.DOWN;
    }
}
