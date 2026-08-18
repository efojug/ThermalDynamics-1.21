package cofh.thermal.dynamics.common.grid;

import cofh.lib.util.TimeTracker;
import net.minecraft.world.level.Level;

/**
 * Render-side bookkeeping shared by content-carrying grids (fluid, chemical): the representative
 * render stack shown inside duct windows, its alpha, and the debounced transition to empty.
 * <p>
 * Type access goes through the same {@link OverflowBuffer.Ops} type-class the overflow buffer
 * uses, so this class stays free of any concrete stack type (and of optional-dependency classes
 * such as Mekanism's ChemicalStack).
 */
public final class GridRenderState<S> {

    /**
     * Ticks an emptied grid keeps its last render content before hosts are told it is empty.
     * Debounces empty/refill oscillation without leaving a long stale-content residue.
     */
    public static final int RENDER_EMPTY_GRACE_TICKS = 10;

    private final OverflowBuffer.Ops<S> ops;
    private final long renderAmount;
    private final TimeTracker timeTracker = new TimeTracker();
    private S renderStack;
    private int renderAlpha = 0xFF;
    private boolean wasFilled;
    private boolean needsUpdate;

    public GridRenderState(OverflowBuffer.Ops<S> ops, long renderAmount) {

        this.ops = ops;
        this.renderAmount = renderAmount;
        this.renderStack = ops.empty();
    }

    /**
     * Quantizes an alpha value to 8 levels. Content amounts change every tick while flowing;
     * without quantization every tick's alpha delta forces a host update packet per windowed duct.
     * Callers apply their minimum-visibility clamp after quantizing.
     */
    public static int quantizeAlpha(int alpha) {

        int step = Math.round(alpha * 7.0F / 255.0F);
        return Math.round(step * 255.0F / 7.0F);
    }

    public S renderStack() {

        return renderStack;
    }

    public int renderAlpha() {

        return renderAlpha;
    }

    public boolean hasContent() {

        return !ops.isEmpty(renderStack);
    }

    /** Forces a host update on the next tick regardless of change detection (topology changes etc.). */
    public void requestUpdate() {

        needsUpdate = true;
    }

    /**
     * Advances one tick and reports whether hosts must be updated now. The transition to empty is
     * deferred by {@link #RENDER_EMPTY_GRACE_TICKS}; a refill with the same content inside the
     * grace window cancels the pending update, so brief drain/refill oscillation never strobes the
     * duct fill texture.
     */
    public boolean tick(Level world, S held, int alpha) {

        boolean contentChanged = !ops.sameType(renderStack, held);
        boolean alphaChanged = renderAlpha != alpha;
        if (contentChanged) {
            renderStack = ops.isEmpty(held) ? ops.empty() : ops.withAmount(held, renderAmount);
        }
        renderAlpha = alpha;
        if (contentChanged || alphaChanged || wasFilled && timeTracker.hasDelayPassed(world, RENDER_EMPTY_GRACE_TICKS) || needsUpdate) {
            if (!wasFilled && ops.isEmpty(renderStack)) {
                timeTracker.markTime(world);
                wasFilled = true;
                return false;
            }
            wasFilled = false;
            needsUpdate = false;
            return true;
        }
        return false;
    }

}
