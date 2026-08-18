package cofh.thermal.dynamics.client;

import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-world UUID ownership index used to reconcile prediction with server revisions. */
public final class ClientTravelingItemIndex {

    private static final int MAX_TOMBSTONES = 4096;
    private static final long TOMBSTONE_EXPIRY_TICKS = 6000L;

    private static Level currentLevel;
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
    private static final LinkedHashMap<UUID, Long> TOMBSTONE_ORDER = new LinkedHashMap<>();
    private static long clientTicks;

    /**
     * Client-local monotonic tick counter for prediction bookkeeping. Unlike getGameTime(), it
     * never jumps: the level game time is hard-corrected by server time-sync packets (and commands),
     * which broke tick-suppression arithmetic that assumed a strict +1 per tick.
     * <p>
     * Incremented at ClientTickEvent.Post, so the value observed while packets are applied between
     * level ticks equals the value the next level tick's block entities observe — stamping the
     * current value therefore suppresses exactly the imminent tick.
     */
    public static long currentTick() {
        return clientTicks;
    }

    private record Entry(@Nullable ItemDuctBlockEntity owner, @Nullable TravelingItem item,
            long revision, boolean terminal, long createdTick) { }

    private static void select(Level level) {
        if (currentLevel != level) {
            currentLevel = level;
            ENTRIES.clear();
            TOMBSTONE_ORDER.clear();
        }
    }

    private static void putEntry(Level level, UUID id, Entry entry) {
        ENTRIES.put(id, entry);
        TOMBSTONE_ORDER.remove(id);
        if (entry.owner() == null) {
            TOMBSTONE_ORDER.put(id, entry.createdTick());
        }
        purgeTombstones(level);
    }

    private static void purgeTombstones(Level level) {
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> iterator = TOMBSTONE_ORDER.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, Long> ordered = iterator.next();
            UUID id = ordered.getKey();
            long createdTick = ordered.getValue();
            Entry current = ENTRIES.get(id);
            if (current == null || current.owner() != null || current.createdTick() != createdTick) {
                iterator.remove();
                continue;
            }
            boolean expired = now - createdTick > TOMBSTONE_EXPIRY_TICKS;
            boolean overLimit = TOMBSTONE_ORDER.size() > MAX_TOMBSTONES;
            if (!expired && !overLimit) {
                break;
            }
            iterator.remove();
            ENTRIES.remove(id, current);
        }
    }

    private static boolean isStale(Entry previous, long revision) {
        return previous != null && (revision < previous.revision()
                || revision == previous.revision() && previous.terminal());
    }

    public static void applyUpdate(Level level, ItemDuctBlockEntity newOwner, TravelingItem serverItem, long revision) {
        select(level);
        UUID id = serverItem.id();
        Entry previous = ENTRIES.get(id);
        if (isStale(previous, revision)) {
            return;
        }
        TravelingItem held = previous != null ? previous.item() : null;
        ItemDuctBlockEntity seat = previous != null ? previous.owner() : null;
        if (held != null) {
            TravelingItem.Reconciliation result = held.reconcileServerState(serverItem);
            if (result != TravelingItem.Reconciliation.RESYNC && seat != null && !seat.isRemoved()) {
                // The snapshot only differs from the local prediction by tick phase. Keep the
                // predicted seat and position; just record the newer revision.
                putEntry(level, id, new Entry(seat, held, revision, false, level.getGameTime()));
                return;
            }
        } else {
            held = serverItem;
        }
        // Authoritative correction: new item, route/state change, or divergence beyond one hop.
        // Seat at the server's duct and render that state for one full tick before predicting onward.
        held.deferClientTick();
        if (seat != null) {
            seat.removeClientTravelingItemLocal(id);
        }
        newOwner.addClientTravelingItemLocal(held);
        putEntry(level, id, new Entry(newOwner, held, revision, false, level.getGameTime()));
    }

    /**
     * Merges a lightweight positional heartbeat into the local copy. Carries no route data — the
     * locally known path plus the authoritative route index fully determine the position; route or
     * state changes always arrive as full snapshots via {@link #applyUpdate} instead.
     */
    public static void applyMove(Level level, ItemDuctBlockEntity newOwner, UUID id, long revision, int routeIndex) {
        select(level);
        Entry previous = ENTRIES.get(id);
        if (isStale(previous, revision)) {
            return;
        }
        TravelingItem held = previous != null ? previous.item() : null;
        ItemDuctBlockEntity seat = previous != null ? previous.owner() : null;
        if (held == null || seat == null || seat.isRemoved()) {
            // No live local copy to advance. Keep the entry shape but record the newer revision so
            // ordering stays monotonic; chunk data or the periodic full refresh restores the item.
            if (previous != null) {
                putEntry(level, id, new Entry(previous.owner(), previous.item(), revision, previous.terminal(), previous.createdTick()));
            }
            return;
        }
        TravelingItem.Reconciliation result = held.reconcileServerMove(routeIndex);
        if (result != TravelingItem.Reconciliation.RESYNC) {
            putEntry(level, id, new Entry(seat, held, revision, false, level.getGameTime()));
            return;
        }
        // Position rebuilt from the known path: seat at the server's duct and render that position
        // for one full client tick before prediction advances it again.
        held.deferClientTick();
        if (seat != newOwner) {
            seat.removeClientTravelingItemLocal(id);
            newOwner.addClientTravelingItemLocal(held);
        }
        putEntry(level, id, new Entry(newOwner, held, revision, false, level.getGameTime()));
    }

    public static void applyRemoval(Level level, UUID id, long revision) {
        select(level);
        Entry previous = ENTRIES.get(id);
        if (previous != null && revision <= previous.revision()) {
            return;
        }
        TravelingItem held = previous != null ? previous.item() : null;
        ItemDuctBlockEntity seat = previous != null ? previous.owner() : null;
        if (held != null && seat != null && !seat.isRemoved() && held.inFinalStretch()) {
            // The server already delivered the item while the local copy is still sweeping its
            // final duct. Let the prediction finish the approach — it self-removes within a tick —
            // and record the terminal revision so stale updates cannot revive the id.
            putEntry(level, id, new Entry(seat, held, revision, true, level.getGameTime()));
            return;
        }
        if (seat != null) {
            seat.removeClientTravelingItemLocal(id);
        }
        putEntry(level, id, new Entry(null, null, revision, true, level.getGameTime()));
    }

    public static void movePredicted(Level level, ItemDuctBlockEntity oldOwner, ItemDuctBlockEntity newOwner, TravelingItem item) {
        select(level);
        Entry current = ENTRIES.get(item.id());
        long revision = current == null ? item.syncRevision() : current.revision();
        oldOwner.removeClientTravelingItemLocal(item.id());
        newOwner.addClientTravelingItemLocal(item);
        putEntry(level, item.id(), new Entry(newOwner, item, revision, false, level.getGameTime()));
    }

    public static void removePredicted(Level level, ItemDuctBlockEntity owner, TravelingItem item) {
        select(level);
        Entry current = ENTRIES.get(item.id());
        long revision = current == null ? item.syncRevision() : current.revision();
        owner.removeClientTravelingItemLocal(item.id());
        putEntry(level, item.id(), new Entry(null, null, revision, true, level.getGameTime()));
    }

    public static void registerSnapshot(Level level, ItemDuctBlockEntity owner, TravelingItem item) {
        select(level);
        if (isStale(ENTRIES.get(item.id()), item.syncRevision())) {
            return;
        }
        applyUpdate(level, owner, item, item.syncRevision());
    }

    public static void unregisterOwner(Level level, ItemDuctBlockEntity owner) {
        select(level);
        long now = level.getGameTime();
        List<UUID> ownedIds = new ArrayList<>();
        for (Map.Entry<UUID, Entry> indexed : ENTRIES.entrySet()) {
            if (indexed.getValue().owner() == owner) {
                ownedIds.add(indexed.getKey());
            }
        }
        for (UUID id : ownedIds) {
            Entry current = ENTRIES.get(id);
            if (current != null && current.owner() == owner) {
                putEntry(level, id, new Entry(null, current.item(), current.revision(), false, now));
            }
        }
    }

    private static void clear() {
        ENTRIES.clear();
        TOMBSTONE_ORDER.clear();
        currentLevel = null;
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> tickItems());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (levelRunning()) {
                ++clientTicks;
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    private static boolean levelRunning() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && !minecraft.isPaused();
    }

    /**
     * Central client tick for all tracked traveling items, replacing per-block-entity tickers:
     * empty ducts cost nothing, and only ducts that actually own items are visited. Runs at
     * ClientTickEvent.Pre so items observe the same counter value as the packet-apply window,
     * keeping the defer arithmetic exact.
     */
    private static void tickItems() {
        if (ENTRIES.isEmpty() || !levelRunning()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (currentLevel != minecraft.level) {
            return; // entries belong to another level; the next applied packet resets the index
        }
        for (Entry entry : List.copyOf(ENTRIES.values())) {
            ItemDuctBlockEntity owner = entry.owner();
            TravelingItem item = entry.item();
            if (owner == null || item == null || owner.isRemoved() || owner.getLevel() != minecraft.level) {
                continue;
            }
            item.clientTick(owner);
        }
    }

    private ClientTravelingItemIndex() { }
}
