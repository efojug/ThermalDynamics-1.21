package cofh.thermal.dynamics.common.grid;

import cofh.thermal.dynamics.ThermalDynamics;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Logs when a grid's overflow buffer sits undrained for a prolonged period. */
public final class OverflowWatchdog {

    private static final long STUCK_BUFFER_WARN_INTERVAL = 600L;

    private final String contentName;
    private boolean hasParkedOverflow;
    private long lastOverflowParkTick;
    private boolean hasWarnedStuck;
    private long lastStuckWarnTick;

    public OverflowWatchdog(String contentName) {

        this.contentName = contentName;
    }

    /** Marks that overflow was parked now, granting a fresh grace window before warning. */
    public void notePark(Level world) {

        if (world != null) {
            hasParkedOverflow = true;
            lastOverflowParkTick = world.getGameTime();
        }
    }

    public void check(Level world, UUID gridId, OverflowBuffer<?> buffer) {

        if (buffer.isEmpty() || world == null) {
            hasParkedOverflow = false;
            hasWarnedStuck = false;
            return;
        }
        long now = world.getGameTime();
        if (hasParkedOverflow && now - lastOverflowParkTick < STUCK_BUFFER_WARN_INTERVAL) {
            return;
        }
        if (hasWarnedStuck && now - lastStuckWarnTick < STUCK_BUFFER_WARN_INTERVAL) {
            return;
        }
        hasWarnedStuck = true;
        lastStuckWarnTick = now;
        ThermalDynamics.LOG.warn("{} grid {} has held {} in overflow for over {} ticks", contentName, gridId, buffer.getAmount(), STUCK_BUFFER_WARN_INTERVAL);
    }

}
