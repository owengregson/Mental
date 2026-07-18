package me.vexmc.mental.kernel.harness;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.kernel.wire.InputLedger;

/**
 * A deterministic interleaving harness for the delivery core (spec §12.2). It
 * runs the four conceptual "threads" (connection-A, connection-V, session-A,
 * session-V) as plain sequential steps the TEST interleaves explicitly —
 * determinism comes from the script, not from real threads. Assertions read the
 * JOURNAL only (B7).
 *
 * <p>It owns a settable {@link TickClock}, per-player inboxes, one
 * {@link GroundFsm}/{@link InputLedger} per connection, and one
 * {@link MotionLedger}/{@link DeliveryDesk}/published {@link PlayerView} per
 * session. {@link #sessionTick} is the canonical step: drain inbox → ledger.tick
 * → publish view → desk.sweep. The view is published AFTER the decay, so a hit
 * registering on a later tick reads the state as of the end of the previous tick
 * — the mandate §4.3 boundary read by construction.</p>
 */
final class SimulatedThreads {

    static final double GRAVITY = 0.08;

    /** The one settable clock all four domains share. */
    static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    final Clock clock = new Clock();
    private final AtomicLong ids = new AtomicLong();

    final Session victim;
    final Session attacker;

    SimulatedThreads(int journalCapacity) {
        this.victim = new Session(1, clock, journalCapacity);
        this.attacker = new Session(2, clock, journalCapacity);
        publish(victim);
        publish(attacker);
    }

    /** One player's two domains: connection (GroundFsm/InputLedger) + session (ledger/desk/view). */
    static final class Session {
        final int entityId;
        final UUID id = UUID.randomUUID();
        final MotionLedger ledger = new MotionLedger(GRAVITY);
        final DeliveryDesk desk;
        final GroundFsm groundFsm;
        final InputLedger inputLedger;
        final ArrayDeque<LedgerEvent> inbox = new ArrayDeque<>();
        PlayerView published;
        double distanceToGround;

        Session(int entityId, TickClock clock, int journalCapacity) {
            this.entityId = entityId;
            this.desk = new DeliveryDesk(entityId, clock, journalCapacity);
            this.groundFsm = new GroundFsm(clock);
            this.inputLedger = new InputLedger(clock);
        }
    }

    TickStamp now() {
        return clock.current();
    }

    void setTick(int t) {
        clock.tick = t;
    }

    /** Connection domain: feed one movement packet; route any emitted event to the inbox. */
    void movement(Session s, boolean onGround, boolean hasPosition, double y) {
        GroundFsm.ViewSlice slice =
                new GroundFsm.ViewSlice(Decay.JUMP_IMPULSE, -1, false, 0.0f, GRAVITY);
        LedgerEvent event = s.groundFsm.onMovement(onGround, hasPosition, y, slice);
        if (event != null) {
            s.inbox.add(event);
        }
    }

    /** Session domain: drain inbox → ledger.tick → publish → desk.sweep. */
    void sessionTick(Session s) {
        drainInbox(s);
        s.ledger.tick(now());
        publish(s);
        s.desk.sweep(now());
    }

    /** Advance the clock and run one session tick, {@code count} times. */
    void runSessionTicks(Session s, int count) {
        for (int i = 0; i < count; i++) {
            clock.tick++;
            sessionTick(s);
        }
    }

    private void drainInbox(Session s) {
        LedgerEvent event;
        while ((event = s.inbox.poll()) != null) {
            if (event instanceof LedgerEvent.Liftoff liftoff) {
                s.ledger.recordLiftoff(liftoff.jumpVy(), liftoff.pushX(), liftoff.pushZ(), liftoff.tick());
            } else if (event instanceof LedgerEvent.Landing landing) {
                s.ledger.recordLanding(landing.tick());
            } else if (event instanceof LedgerEvent.Reset reset) {
                // The ledger has no reset op; a resync re-grounds at equilibrium.
                s.ledger.recordLanding(reset.tick());
            }
        }
    }

    void publish(Session s) {
        Decay.Motion motion = s.ledger.current();
        boolean grounded = s.ledger.groundedView();
        KinematicState kinematics = new KinematicState(0.0, s.distanceToGround, grounded);
        s.published = new PlayerView(
                s.id, s.entityId, now(), motion, grounded, Decay.DEFAULT_SLIPPERINESS,
                GRAVITY, Decay.JUMP_IMPULSE, -1, false, false, true, 0, 20, 0.0,
                KnockbackProfile.LEGACY_17, 0, kinematics);
    }

    HitId nextId() {
        return new HitId(ids.incrementAndGet());
    }

    HitContext context(HitSource source) {
        return new HitContext(nextId(), source, attacker.id, victim.id,
                new SprintVerdict(false, Boolean.FALSE, now()), true, null, now());
    }

    /**
     * The fast-path registration + full desk resolution for a melee hit on the
     * victim, reading the victim's PUBLISHED view (attacker at the origin, victim
     * 4 blocks down +z). Returns the shipped vector, or null when the view is
     * stale (the fast path declines — scenario 4). Records the shipped value into
     * the victim ledger (spec §3.5 step 3).
     */
    KnockbackVector registerMelee(boolean sprinting, int enchant) {
        PlayerView view = victim.published;
        if (view == null || !view.fresh(now())) {
            return null; // stale view: the fast path declines (NO_TICK degradation)
        }
        EntityState attackerState =
                new EntityState(0, 0, 0, 0.0f, 0, 0, 0, true, sprinting, enchant, 0);
        EntityState victimState = new EntityState(
                0, 0, 4, 0.0f, view.motion().vx(), view.motion().vy(), view.motion().vz(),
                view.grounded(), false, 0, view.knockbackResistance());
        KnockbackVector vector = KnockbackEngine.compute(attackerState, victimState, view.profile(), null);

        HitTransaction tx = new HitTransaction(context(new HitSource.Melee()));
        tx.planned();
        tx.preSent(vector);
        victim.desk.submit(tx, vector);
        victim.desk.awaitVelocityEvent(tx);
        Directive directive = victim.desk.resolve(vector.x(), vector.y(), vector.z());
        if (directive.ship() != null) {
            KnockbackVector shipped = directive.ship();
            victim.ledger.record(shipped.x(), shipped.y(), shipped.z(),
                    victim.ledger.groundedView(), Decay.DEFAULT_SLIPPERINESS, now());
        }
        return directive.ship();
    }
}
