package me.vexmc.mental.v5;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * The D2 session scaffold (spec §2) — one player's owning-thread combat state:
 * the {@link MotionLedger}, the {@link DeliveryDesk}, the MPSC inbox of
 * {@link LedgerEvent}s from D1, and the published {@link PlayerView}. Unwired in
 * Phase 2 (no Bukkit scheduling — Phase 4 drives {@link #tickStep} from a
 * {@code repeatOn} task); the constructor takes plain values.
 */
public final class CombatSession {

    private final MotionLedger ledger;
    private final DeliveryDesk desk;
    private final Queue<LedgerEvent> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicReference<PlayerView> published = new AtomicReference<>();

    /**
     * The authoritative-pass hand-off slot (spec §3.4): the fast-path damage task
     * sets it to its transaction around {@code victim.damage(...)} and clears it on
     * exit — the typed, per-victim replacement for the old melee reentry guard. A
     * plain field mutated only by the owning thread. Read by the {@code DamageRouter}
     * at EDBEE entry; null when no fast-path/synthetic hit is in flight (a Vanilla
     * source). Set by the fast path in 4A2.
     */
    private HitTransaction activeInbound;

    /**
     * The transaction the {@code DamageRouter} established for the currently
     * dispatching {@code EntityDamageByEntityEvent} (spec §3.4 "read-or-mint"):
     * the {@link #activeInbound} slot when a fast-path/synthetic hit is in flight,
     * else a freshly-minted {@code Vanilla} transaction. Set at the LOWEST damage
     * listener and read by the knockback unit at MONITOR — the shared per-event
     * hand-off. Overwritten by every player-victim damage event, so a read is
     * always this event's; owning thread only.
     */
    private HitTransaction eventTransaction;

    public CombatSession(double gravity, int entityId, TickClock clock, int journalCapacity) {
        this.ledger = new MotionLedger(gravity);
        this.desk = new DeliveryDesk(entityId, clock, journalCapacity);
    }

    public MotionLedger ledger() {
        return ledger;
    }

    public DeliveryDesk desk() {
        return desk;
    }

    /** The last published view (the only thing D1/netty may read), or null before the first tick. */
    public PlayerView view() {
        return published.get();
    }

    /** D1 enqueues an immutable ledger event into the MPSC inbox. */
    public void enqueue(LedgerEvent event) {
        inbox.add(event);
    }

    /** The in-flight fast-path transaction, or null (a Vanilla source) — owning thread only. */
    public HitTransaction activeInbound() {
        return activeInbound;
    }

    /** The damage task sets the slot around {@code victim.damage(...)}; owning thread only. */
    public void activeInbound(HitTransaction transaction) {
        this.activeInbound = transaction;
    }

    /** Clear the slot on damage-task exit (asserts one-in-one-out by usage). */
    public void clearActiveInbound() {
        this.activeInbound = null;
    }

    /** The DamageRouter establishes this event's transaction (LOWEST); owning thread only. */
    public void beginEvent(HitTransaction transaction) {
        this.eventTransaction = transaction;
    }

    /** The knockback unit reads this event's transaction (MONITOR), or null; owning thread only. */
    public HitTransaction currentEventTransaction() {
        return eventTransaction;
    }

    /**
     * One session tick: drain the inbox (events applied BEFORE the decay), tick
     * the ledger, publish the freshly-built view (AFTER), then sweep the desk —
     * all on the owning thread, using {@code freshlyBuilt.at()} as the tick.
     */
    public void tickStep(PlayerView freshlyBuilt) {
        LedgerEvent event;
        while ((event = inbox.poll()) != null) {
            apply(event);
        }
        TickStamp now = freshlyBuilt.at();
        ledger.tick(now);
        published.set(freshlyBuilt);
        desk.sweep(now);
    }

    private void apply(LedgerEvent event) {
        if (event instanceof LedgerEvent.Liftoff liftoff) {
            ledger.recordLiftoff(liftoff.jumpVy(), liftoff.pushX(), liftoff.pushZ(), liftoff.tick());
        } else if (event instanceof LedgerEvent.Landing landing) {
            ledger.recordLanding(landing.tick());
        } else if (event instanceof LedgerEvent.Reset reset) {
            // The ledger has no reset op; a resync re-grounds at equilibrium.
            ledger.recordLanding(reset.tick());
        }
    }
}
