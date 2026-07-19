package me.vexmc.mental.kernel.timing;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The live registry of temporary per-(victim, attacker) window re-pricings (the
 * public {@code HitTimingOverrides} API). Pure kernel state: the current tick is
 * always passed in — the table never consults a clock itself — so it is unit-
 * pinned against hand-fed {@link TickStamp}s and behaves identically on Paper's
 * authoritative tick and Folia's global counter.
 *
 * <h2>Concurrency</h2>
 * <p>A {@link ConcurrentHashMap} because the pair's factor is READ from two
 * realms — the netty fast path (the frozen admission gate) and the victim's
 * owning region thread (the ct8c per-hit window write) — while registration and
 * hygiene WRITE from the region thread. Every operation is a single atomic map
 * action; there is no compound read-modify-write that could tear.
 *
 * <h2>Semantics (each names the spec clause)</h2>
 * <ul>
 *   <li><b>S3 — expiry in server ticks.</b> A pair registered at tick {@code t}
 *       for {@code d} ticks is live for {@code now} in {@code [t, t + d)}; the
 *       {@link TickStamp} delta compare is wrap-safe exactly like the rest of the
 *       delivery core. Expired entries are evicted lazily at read
 *       ({@link #factorFor}/{@link #isActive}) and in bulk by {@link #sweep}.</li>
 *   <li><b>S4 — refresh-not-stack.</b> {@link #register} REPLACES a pair's
 *       factor AND clock; two DIFFERENT attackers on one victim are independent
 *       keys.</li>
 *   <li><b>S5 — death/quit hygiene.</b> {@link #clearVictim} drops every pair
 *       touching a victim; {@link #clear} drops one pair (a consumer's own
 *       cancel).</li>
 * </ul>
 */
public final class OverrideTable {

    /** One directed (victim, attacker) pair — the override key. */
    private record Pair(UUID victim, UUID attacker) {}

    /** A live override: the clamped factor and the tick it lapses on. */
    private record Entry(double factor, int expiryTick) {}

    private final ConcurrentHashMap<Pair, Entry> table = new ConcurrentHashMap<>();

    /**
     * Registers or refreshes (S4 — never stacks) an override: hits from
     * {@code attacker} on {@code victim} price at {@code factor} (clamped to
     * {@code [0.25, 1.0]}) for {@code durationTicks} ticks from {@code now}.
     * A non-positive duration registers an already-lapsed entry (a no-op window);
     * {@code now} must be a known stamp (the caller registers from a live damage
     * event, so the clock is running).
     */
    public void register(UUID victim, UUID attacker, double factor, int durationTicks, TickStamp now) {
        if (!now.known()) {
            return; // no tick frame to bound the window — decline rather than register an un-expirable entry
        }
        double clamped = WindowPricing.clampFactor(factor);
        int expiry = now.value() + Math.max(0, durationTicks);
        table.put(new Pair(victim, attacker), new Entry(clamped, expiry));
    }

    /**
     * The window factor for this pair at {@code now}: the clamped stored factor
     * while live, else {@link WindowPricing#MAX_FACTOR} (1.0 — the era-exact
     * no-op) when no override is in force. An expired entry is evicted here (S3
     * lazy eviction) and reads as 1.0. This is the ONE value every admission
     * seam multiplies its effective window by.
     */
    public double factorFor(UUID victim, UUID attacker, TickStamp now) {
        Pair key = new Pair(victim, attacker);
        Entry entry = table.get(key);
        if (entry == null) {
            return WindowPricing.MAX_FACTOR;
        }
        if (expired(entry, now)) {
            table.remove(key, entry);
            return WindowPricing.MAX_FACTOR;
        }
        return entry.factor();
    }

    /**
     * Whether an override is live for the pair right now. Distinct from
     * {@link #factorFor} reading 1.0: a pair registered AT the 1.0 ceiling is
     * still active (the consumer asked for a no-op window), where an absent pair
     * is not.
     */
    public boolean isActive(UUID victim, UUID attacker, TickStamp now) {
        Pair key = new Pair(victim, attacker);
        Entry entry = table.get(key);
        if (entry == null) {
            return false;
        }
        if (expired(entry, now)) {
            table.remove(key, entry);
            return false;
        }
        return true;
    }

    /** Drops one pair's override early (S5 — a consumer's own cancel path). */
    public void clear(UUID victim, UUID attacker) {
        table.remove(new Pair(victim, attacker));
    }

    /** Drops every override touching {@code victim} (S5 — death/quit hygiene). */
    public void clearVictim(UUID victim) {
        table.keySet().removeIf(pair -> pair.victim().equals(victim));
    }

    /** Evicts every lapsed entry (S3 — the bulk sweep, run beside the combo-state sweep). */
    public void sweep(TickStamp now) {
        table.values().removeIf(entry -> expired(entry, now));
    }

    /** The count of tracked pairs (live or not-yet-swept) — a test/diagnostic seam. */
    public int size() {
        return table.size();
    }

    /**
     * Live while {@code now} is before the lapse tick. An unknown {@code now}
     * (a stalled Folia counter before it starts) never evicts — the entry is
     * held until a real frame can judge it, mirroring the {@link TickStamp}
     * NO_TICK degradation elsewhere in the core.
     */
    private static boolean expired(Entry entry, TickStamp now) {
        return now.known() && now.value() - entry.expiryTick() >= 0;
    }
}
