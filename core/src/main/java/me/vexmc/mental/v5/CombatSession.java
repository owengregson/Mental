package me.vexmc.mental.v5;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
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
