package me.vexmc.mental.v5.feature.feedback;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;

/**
 * The per-victim damage-WINDOW ledger — ONE indicator per victim per
 * invulnerability window, carrying the final ROLLED amount (the 2026-07-12
 * owner directive). It evolves the 2.5.3 same-tick merge book (which folded a
 * plugin's same-tick bonus damage into one stand): that fold survives here as a
 * single transition of a larger state machine that also HOLDS a fresh hit's
 * marker for {@code roll-hold-ticks} ticks so vanilla's mid-window UPGRADE
 * deltas fold into it — instead of each delta spawning its own crit-styled
 * ghost stand, the double-report the round fixes ({@link UpgradeWindow}).
 *
 * <h2>Contract</h2>
 * A FRESH hit (vanilla NOT on the upgrade branch — {@link UpgradeWindow#isDelta}
 * false) opens a window and holds its marker; the marker ships once the hold
 * elapses (or a newer fresh hit / the victim's death forces it). A DELTA hit
 * folds into the held window (sum the displayed amounts, OR the crit), or — if
 * the marker already shipped — BUMPS the live stand in place. A delta never
 * spawns its own stand: an untracked window (its opener never passed the cause
 * gate — environmental damage) ships the melee delta ONCE and remembers it so
 * further deltas bump. Only ONE window per victim: a victim is only ever inside
 * one invulnerability window.
 *
 * <h2>Threading — single writer per victim</h2>
 * Every mutating call rides the VICTIM's own region thread: the EDBEE
 * ({@link #onFresh}/{@link #onDelta}) fires on the victim's region, the roll
 * flush ({@link #due}) is the victim's session tick, and the death flush
 * ({@link #onDeath}) is the victim's {@code PlayerDeathEvent} — all one thread
 * per victim (thread-safety is ownership, per {@code SessionService}). Different
 * victims sit on different region threads under Folia, so the map is concurrent,
 * but each entry is touched by exactly one thread — no per-entry lock needed.
 * {@link #close} (scope teardown, the reconciler thread) only clears; the
 * drivers tear the stands down regardless.
 *
 * <h2>Pure by design</h2>
 * The book is Bukkit-free and PacketEvents-free: it takes frozen VALUES
 * (placement, damage, tick) and returns {@link Ship} commands the listener
 * executes. So the whole state machine is unit-pinned without a live server.
 */
final class IndicatorWindowBook {

    /**
     * A window ready to be DRAWN: the frozen geometry, the running total and
     * OR-ed crit, the attacker whose client the stand rides, and — for a bump —
     * the prior stand to replace (destroy+respawn in one bundle), or {@code -1}
     * for a first fresh spawn.
     */
    record Ship(
            UUID victimId, UUID attackerId, List<UUID> viewers, IndicatorPlacement.Spawn spawn,
            double groundY, double total, boolean crit, int priorEntityId) {}

    /** What a FRESH hit did to the book. */
    enum FreshKind {
        /** A new window opened and is held — nothing ships yet. */
        OPEN_HELD,
        /** A new window opened with a zero hold — {@link FreshResult#action} ships now. */
        OPEN_SHIP,
        /** A same-tick, same-attacker fresh hit folded into a still-held window. */
        FOLD_HELD,
        /** A same-tick, same-attacker fresh hit folded into an already-shipped window — {@code action} bumps it. */
        FOLD_BUMP
    }

    /** The plan for a fresh hit: a closed prior window to draw now (nullable), the kind, and its own draw (nullable). */
    record FreshResult(FreshKind kind, Ship priorShip, Ship action) {}

    /** What a DELTA hit did to the book. */
    enum DeltaKind {
        /** Folded into a still-held window — nothing ships yet. */
        FOLD_HELD,
        /** Folded into an already-shipped window — {@link DeltaResult#action} bumps the live stand. */
        BUMP,
        /** No live window to fold into — the caller ships the delta once (melee) or ignores it (a CUSTOM plugin event). */
        UNTRACKED
    }

    /** The plan for a delta hit: the kind and, for a bump, the replace draw. */
    record DeltaResult(DeltaKind kind, Ship action) {}

    /** One victim's live window. Mutated only by that victim's own region thread. */
    private static final class Window {
        UUID attacker; // last upgrader while held; frozen to the ship-attacker once drawn (null: no player dealt it)
        /** Frozen at open, exactly like the geometry — so a later ship performs no world reads. */
        final List<UUID> viewers;
        final long openedTick;
        final long holdDeadlineTick;
        final long expiryTick;
        double total;
        boolean crit;
        final IndicatorPlacement.Spawn spawn;
        final double groundY;
        boolean shipped;
        int entityId = -1;

        Window(
                UUID attacker, List<UUID> viewers, long openedTick, long holdDeadlineTick, long expiryTick,
                double total, boolean crit, IndicatorPlacement.Spawn spawn, double groundY) {
            this.attacker = attacker;
            this.viewers = viewers;
            this.openedTick = openedTick;
            this.holdDeadlineTick = holdDeadlineTick;
            this.expiryTick = expiryTick;
            this.total = total;
            this.crit = crit;
            this.spawn = spawn;
            this.groundY = groundY;
        }
    }

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    /**
     * A FRESH hit. A same-(attacker, victim)-same-tick hit folds into the one
     * window (the 2.5.3 plugin-bonus aggregation, exactly); any OTHER live window
     * is a now-closed prior (a fresh hit means {@code nd <= max/2}, so its
     * invulnerability window ended) — its held stand draws now and it is evicted,
     * then a new window opens (held, or shipping at once when {@code holdTicks}
     * is zero). Placement is provided by the caller (region-legal at EDBEE time);
     * a same-tick fold ignores it and reuses the first spawn's geometry.
     */
    FreshResult onFresh(
            UUID victimId, UUID attackerId, long tick, double displayed, boolean crit,
            int holdTicks, int expiryHorizon, IndicatorPlacement.Spawn spawn, double groundY,
            List<UUID> viewers) {
        Window existing = windows.get(victimId);
        if (existing != null && existing.openedTick == tick && Objects.equals(attackerId, existing.attacker)) {
            existing.total += displayed;
            existing.crit |= crit;
            if (existing.shipped && existing.entityId >= 0) {
                return new FreshResult(FreshKind.FOLD_BUMP, null, bump(victimId, existing));
            }
            return new FreshResult(FreshKind.FOLD_HELD, null, null);
        }
        Ship priorShip = (existing != null && !existing.shipped) ? fresh(victimId, existing) : null;
        Window opened = new Window(
                attackerId, viewers, tick, tick + holdTicks, tick + expiryHorizon, displayed, crit, spawn, groundY);
        windows.put(victimId, opened); // one window per victim — replaces the closed prior
        if (holdTicks <= 0) {
            return new FreshResult(FreshKind.OPEN_SHIP, priorShip, fresh(victimId, opened));
        }
        return new FreshResult(FreshKind.OPEN_HELD, priorShip, null);
    }

    /**
     * A DELTA hit. It folds into the live window (sum the displayed, OR the crit);
     * a still-held window keeps holding (the last upgrader becomes the attacker its
     * eventual ship draws on), an already-shipped one bumps its stand in place. No
     * live window (none, expired, or a shipped window whose stand never drew) reads
     * as UNTRACKED — the caller ships the delta once (melee) or drops it (CUSTOM).
     */
    DeltaResult onDelta(UUID victimId, UUID attackerId, long tick, double displayed, boolean crit) {
        Window existing = windows.get(victimId);
        if (existing == null || tick > existing.expiryTick
                || (existing.shipped && existing.entityId < 0)) {
            return new DeltaResult(DeltaKind.UNTRACKED, null);
        }
        existing.total += displayed;
        existing.crit |= crit;
        if (existing.shipped) {
            // The stand rides the ship-attacker's driver; keep it there even if THIS
            // delta came from another player (rare) — moving it would orphan the stand.
            return new DeltaResult(DeltaKind.BUMP, bump(victimId, existing));
        }
        existing.attacker = attackerId; // the last upgrader draws the eventual held ship
        return new DeltaResult(DeltaKind.FOLD_HELD, null);
    }

    /**
     * The roll-hold flush (the victim's session tick): a held window whose hold has
     * elapsed draws its stand now; an already-shipped window is pruned once past its
     * expiry horizon so a stale entry never lingers. Returns the draw, or {@code null}.
     */
    Ship due(UUID victimId, long now) {
        Window window = windows.get(victimId);
        if (window == null) {
            return null;
        }
        if (window.shipped) {
            if (now > window.expiryTick) {
                windows.remove(victimId, window);
            }
            return null;
        }
        if (now >= window.holdDeadlineTick) {
            return fresh(victimId, window);
        }
        return null;
    }

    /**
     * The death flush: a still-held window draws its (already overkill-clamped)
     * killing marker immediately and the entry is dropped, so the marker shows
     * before an instant respawn and no window survives into it. Returns the draw,
     * or {@code null} (nothing held).
     */
    Ship onDeath(UUID victimId) {
        Window window = windows.remove(victimId);
        if (window != null && !window.shipped) {
            return fresh(victimId, window);
        }
        return null;
    }

    /** Records the entity id a draw produced ({@code -1} when the one ship attempt failed) against the live window. */
    void shipped(UUID victimId, int entityId) {
        Window window = windows.get(victimId);
        if (window != null) {
            window.shipped = true;
            window.entityId = entityId;
        }
    }

    /**
     * Remembers an already-drawn UNTRACKED delta as this victim's shipped window,
     * so a further delta bumps the one stand instead of double-shipping. The hold
     * deadline is the open tick (it already shipped); the expiry horizon still
     * bounds how long a bump can target it.
     */
    void rememberUntracked(
            UUID victimId, UUID attackerId, long tick, double total, boolean crit,
            int expiryHorizon, IndicatorPlacement.Spawn spawn, double groundY, int entityId,
            List<UUID> viewers) {
        Window window = new Window(
                attackerId, viewers, tick, tick, tick + expiryHorizon, total, crit, spawn, groundY);
        window.shipped = true;
        window.entityId = entityId;
        windows.put(victimId, window);
    }

    /** Session forget (quit / retire): drop this player's window. */
    void forget(UUID victimId) {
        windows.remove(victimId);
    }

    /** Scope teardown: drop every pending window (the drivers tear the stands down regardless — zero-touch). */
    void close() {
        windows.clear();
    }

    private static Ship fresh(UUID victimId, Window window) {
        return new Ship(
                victimId, window.attacker, window.viewers, window.spawn,
                window.groundY, window.total, window.crit, -1);
    }

    private static Ship bump(UUID victimId, Window window) {
        return new Ship(
                victimId, window.attacker, window.viewers, window.spawn,
                window.groundY, window.total, window.crit, window.entityId);
    }
}
