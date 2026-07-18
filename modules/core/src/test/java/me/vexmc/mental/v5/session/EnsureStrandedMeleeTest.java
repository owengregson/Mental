package me.vexmc.mental.v5.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * The S3 era-silence gate on the stranded-packetless melee net
 * ({@link SessionService#ensureOrWithhold}). A full vanilla accept ships (its
 * {@code noDamageTicks} was reset to max at the hit, ≈ max−2 at the +2-tick net); a
 * mid-invulnerability difference hit (the OLD countdown, well below max−3) is
 * withheld and journaled {@code era-silent-difference}, never fed back through
 * {@code setVelocity} — the manufactured re-knock loop's head.
 */
class EnsureStrandedMeleeTest {

    private static final UUID ATTACKER = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();
    private static final int MAX = 20;

    @Test
    void aFullAcceptShipsAsBefore() {
        List<JournalEntry> journal = new ArrayList<>();
        DeliveryDesk desk = new DeliveryDesk(1, () -> new TickStamp(2), 16,
                (context, entry) -> journal.add(entry));
        HitContext ctx = stage(desk);

        KnockbackVector shipped = SessionService.ensureOrWithhold(desk, ctx, MAX - 2, MAX);

        assertNotNull(shipped, "a corroborated full-accept stranding still ships");
        assertEquals(new KnockbackVector(0.4, 0.3608, 0.4), shipped);
        assertEquals(1, journal.size());
        assertEquals("ensured", journal.get(0).capture().resolution());
        assertNotNull(journal.get(0).shipped(), "a shipped ensure journals the vector");
    }

    @Test
    void aDifferenceHitIsWithheldAndJournaled() {
        List<JournalEntry> journal = new ArrayList<>();
        DeliveryDesk desk = new DeliveryDesk(1, () -> new TickStamp(2), 16,
                (context, entry) -> journal.add(entry));
        HitContext ctx = stage(desk);

        // A mid-invuln difference hit left the OLD countdown (well below max−3): withheld.
        KnockbackVector shipped = SessionService.ensureOrWithhold(desk, ctx, MAX - 6, MAX);

        assertNull(shipped, "a difference hit ships nothing — the era is silent");
        assertEquals(1, journal.size());
        assertEquals("era-silent-difference", journal.get(0).suppressReason());
        assertNull(journal.get(0).shipped(), "a withheld hit journals a drop, not a ship");
        assertNull(desk.pendingContext(), "the withheld pending is removed from the desk");
    }

    /** A REGISTERED region-melee pending, vectored and armed for its velocity event. */
    private static HitContext stage(DeliveryDesk desk) {
        HitContext ctx = new HitContext(
                new HitId(1), new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM,
                new SprintVerdict(false, null, new TickStamp(0)), false, null, new TickStamp(0));
        HitTransaction tx = new HitTransaction(ctx);
        desk.submit(tx, new KnockbackVector(0.4, 0.3608, 0.4));
        desk.awaitVelocityEvent(tx);
        return ctx;
    }
}
