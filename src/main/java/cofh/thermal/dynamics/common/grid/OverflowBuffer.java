package cofh.thermal.dynamics.common.grid;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Persistent, single-type overflow storage shared by fluid and chemical grids. */
public final class OverflowBuffer<S> {
    public interface Ops<S> {
        S empty(); boolean isEmpty(S stack); long amount(S stack); S withAmount(S stack, long amount);
        boolean sameType(S first, S second); long maxAmount();
        CompoundTag save(S stack, HolderLookup.Provider provider); S load(CompoundTag tag, HolderLookup.Provider provider);
    }
    private final Ops<S> ops;
    private S type;
    private long amount;
    public OverflowBuffer(Ops<S> ops) { this.ops = ops; this.type = ops.empty(); }
    public S type() { return type; }
    public long getAmount() { return amount; }
    public boolean isEmpty() { return amount <= 0; }
    public boolean compatibleWith(S candidate) { return isEmpty() || ops.sameType(type, candidate); }

    /** Remaining long-domain capacity of this buffer. Grid callers must use their
     * storage-aware headroom instead of this value. */
    long rawAbsorbable() { return Long.MAX_VALUE - amount; }
    public S peek(long request) { long n = Math.min(Math.min(Math.max(0, request), amount), ops.maxAmount()); return n <= 0 ? ops.empty() : ops.withAmount(type, n); }
    public long add(S stack) { return ops.isEmpty(stack) ? 0 : add(stack, ops.amount(stack)); }
    public long add(S stack, long incoming) {
        if (ops.isEmpty(stack) || incoming <= 0 || !compatibleWith(stack)) return 0;
        long accepted = Math.min(incoming, rawAbsorbable());
        if (accepted <= 0) return 0;
        if (isEmpty()) type = ops.withAmount(stack, 1);
        amount += accepted;
        return accepted;
    }

    /** Transfers as much as possible from another buffer of the same type. */
    public long absorb(OverflowBuffer<S> other) {

        if (other == null || other.isEmpty()) {
            return 0;
        }
        long pending = other.amount;
        long moved = add(other.type, pending);
        other.drain(moved);
        return pending - moved;
    }
    public long drain(long request) {
        long n = Math.min(Math.max(0, request), amount);
        amount -= n;
        if (amount == 0) type = ops.empty();
        return n;
    }
    public void clear() { type = ops.empty(); amount = 0; }
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (!isEmpty()) { tag.put("Type", ops.save(ops.withAmount(type, 1), provider)); tag.putLong("Amount", amount); }
        return tag;
    }
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        clear();
        if (!tag.contains("Type", CompoundTag.TAG_COMPOUND) || !tag.contains("Amount", CompoundTag.TAG_LONG)) return;
        try {
            S loaded = ops.load(tag.getCompound("Type"), provider); long n = tag.getLong("Amount");
            if (!ops.isEmpty(loaded) && n > 0) { type = ops.withAmount(loaded, 1); amount = n; }
        } catch (RuntimeException ignored) {
            clear();
        }
    }
}
