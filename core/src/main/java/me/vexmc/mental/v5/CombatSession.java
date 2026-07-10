package me.vexmc.mental.v5;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.combo.ComboRules;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.delivery.JournalObserver;
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

    /**
     * The combo detector (combo-hold §3.1) — present ONLY while the combo-hold
     * module is enabled, so a module-off session carries no tracker and pays zero
     * cost (zero-touch). Mutated only by the owning thread. Published into the
     * view via {@link #comboAttackerId()}; fed by the delivery fold, the victim's
     * own landed hits, and the per-tick session sweep.
     */
    private ComboTracker comboTracker;

    /**
     * Consecutive grounded ticks the victim has held (combo-hold §3.2b) — advanced
     * once per session tick, reset to 0 the moment the victim leaves the ground.
     * Published as a {@code PlayerView} component the pocket-servo precision solve
     * reads. Distinct from the {@link ComboTracker}'s own grounded-run counter,
     * which resets on a fresh KNOCK (its touchdown-end condition) rather than on
     * leaving the ground; this is the "how long has the victim been standing" signal
     * the predictor needs. Owning thread only.
     */
    private int groundedTicks;

    /**
     * A small per-tick yaw-slew window (target-v2 repair #4) — the source for the
     * dynamic target's continuous turn term ({@code yawRateHat}). Mirrors the
     * measured-velocity pattern: each session tick pushes {@code |Δyaw|} (shortest-
     * arc, wrap-corrected) and {@link #advanceYawRate} publishes the mean over the
     * last {@value #YAW_WINDOW} ticks; {@link Double#NaN} until the first delta, so
     * the kernel degrades to its conservative 30°/tick floor. Owning thread only.
     */
    private static final int YAW_WINDOW = 3;
    private float lastYaw;
    private boolean hasYaw;
    private final double[] yawDeltas = new double[YAW_WINDOW];
    private int yawDeltaCount;
    private int yawDeltaNext;

    /** The pre-F9 arity: no journal observer (delegates to {@link JournalObserver#NONE}). Tests construct it. */
    public CombatSession(double gravity, int entityId, TickClock clock, int journalCapacity) {
        this(gravity, entityId, clock, journalCapacity, JournalObserver.NONE);
    }

    public CombatSession(double gravity, int entityId, TickClock clock, int journalCapacity,
                         JournalObserver journalObserver) {
        this.ledger = new MotionLedger(gravity);
        this.desk = new DeliveryDesk(entityId, clock, journalCapacity, journalObserver);
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

    /* ------------------------------ combo hold ------------------------------ */

    /** The combo detector, or null when the combo-hold module is disabled; owning thread only. */
    public ComboTracker comboTracker() {
        return comboTracker;
    }

    /** Installs a fresh detector with {@code rules} (module enable); returns it. Owning thread only. */
    public ComboTracker installComboTracker(ComboRules rules) {
        this.comboTracker = new ComboTracker(rules);
        return this.comboTracker;
    }

    /** Drops the detector (module disable / retire) — a module-off session carries none. Owning thread only. */
    public void clearComboTracker() {
        this.comboTracker = null;
    }

    /**
     * The attacker holding an ACTIVE combo against this player, or null (no
     * tracker, or no active combo) — the value published into the {@code
     * PlayerView} for the servo-application gate. Owning thread only.
     */
    public UUID comboAttackerId() {
        return comboTracker == null ? null : comboTracker.activeAttacker();
    }

    /** The consecutive grounded-tick count published into the view (combo-hold §3.2b). Owning thread only. */
    public int groundedTicks() {
        return groundedTicks;
    }

    /**
     * Advances the consecutive grounded-tick count from this tick's ground state
     * (increment while grounded, reset to 0 on leaving the ground) and returns the
     * new value. Called once per session tick from the view build. Owning thread only.
     */
    public int advanceGroundedTicks(boolean grounded) {
        groundedTicks = grounded ? groundedTicks + 1 : 0;
        return groundedTicks;
    }

    /**
     * Pushes this tick's yaw into the slew window and returns the victim's measured
     * yaw rate — the mean {@code |Δyaw|} (shortest-arc) over the last {@value
     * #YAW_WINDOW} ticks (target-v2 repair #4), or {@link Double#NaN} before the
     * first delta. Called once per session tick from the view build. Owning thread only.
     */
    public double advanceYawRate(float yaw) {
        if (hasYaw) {
            yawDeltas[yawDeltaNext] = Math.abs(wrapDegrees(yaw - lastYaw));
            yawDeltaNext = (yawDeltaNext + 1) % yawDeltas.length;
            if (yawDeltaCount < yawDeltas.length) {
                yawDeltaCount++;
            }
        }
        lastYaw = yaw;
        hasYaw = true;
        if (yawDeltaCount == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < yawDeltaCount; i++) {
            sum += yawDeltas[i];
        }
        return sum / yawDeltaCount;
    }

    /** Shortest-arc yaw delta in degrees, folded into [−180, 180) — a spin never reads as a huge rate. */
    private static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** The pre-connected-gate arity: treats the victim as packetless (the pin surface). */
    public void tickStep(PlayerView freshlyBuilt) {
        tickStep(freshlyBuilt, false);
    }

    /**
     * One session tick: drain the inbox (events applied BEFORE the decay), tick
     * the ledger, publish the freshly-built view (AFTER), then sweep the desk —
     * all on the owning thread, using {@code freshlyBuilt.at()} as the tick.
     *
     * <p>{@code victimConnected} rides into the sweep so a CONNECTED victim's still-
     * awaiting melee is never time-dropped (its genuine, possibly region-late
     * PlayerVelocityEvent is the sole delivery authority — the 2.4.6 vanilla-knockback
     * leak fix); a packetless victim is dropped as before.</p>
     */
    public void tickStep(PlayerView freshlyBuilt, boolean victimConnected) {
        LedgerEvent event;
        while ((event = inbox.poll()) != null) {
            apply(event);
        }
        TickStamp now = freshlyBuilt.at();
        ledger.tick(now);
        published.set(freshlyBuilt);
        desk.sweep(now, victimConnected);
    }

    /**
     * The decay-only fallback (2.5.2 tick armor) — drains the inbox and decays the
     * ledger WITHOUT publishing a view or sweeping the desk. Used only when the view
     * build itself threw this tick: there is no fresh view to publish, but ledger
     * decay must NEVER freeze — a residual that never decays is the monotone stacking
     * ramp (2026-07-10-downward-kb-and-stacking-diagnoses.md report 2). The stale
     * published view keeps its own staleness gate ({@code PlayerView.fresh}), so the
     * netty realm degrades safely; the next clean tick republishes. Owning thread only.
     */
    public void decayOnly(TickStamp now) {
        LedgerEvent event;
        while ((event = inbox.poll()) != null) {
            apply(event);
        }
        ledger.tick(now);
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
