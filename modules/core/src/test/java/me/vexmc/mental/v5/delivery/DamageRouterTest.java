package me.vexmc.mental.v5.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

/**
 * The DamageRouter's decision mapping: an in-flight transaction keeps its typed
 * source (a RodPull is never re-derived as melee — B6), an absent slot mints a
 * fresh Vanilla transaction stamped from the clock.
 */
class DamageRouterTest {

    private static final UUID ATTACKER = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();

    @Test
    void activeTransactionKeepsItsTypedSourceRegardlessOfCause() {
        HitContext context = new HitContext(
                new HitId(1), new HitSource.RodPull(), ATTACKER, VICTIM,
                new SprintVerdict(false, null, TickStamp.NO_TICK), false, null, TickStamp.NO_TICK);
        HitTransaction active = new HitTransaction(context);

        // Even though the damage cause is a melee ENTITY_ATTACK, the typed source wins.
        assertEquals(new HitSource.RodPull(),
                DamageRouter.sourceFor(active, DamageCause.ENTITY_ATTACK));
    }

    @Test
    void absentSlotMapsToVanillaWithTheDamageCause() {
        assertEquals(new HitSource.Vanilla("ENTITY_ATTACK"),
                DamageRouter.sourceFor(null, DamageCause.ENTITY_ATTACK));
        assertEquals(new HitSource.Vanilla("FALL"),
                DamageRouter.sourceFor(null, DamageCause.FALL));
    }

    @Test
    void mintVanillaBuildsARegisteredTransactionStampedFromTheClock() {
        DamageRouter router = new DamageRouter(null, () -> new TickStamp(7), new HitIds());

        HitTransaction tx = router.mintVanilla(
                new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM, false);

        assertEquals(HitTransaction.State.REGISTERED, tx.state());
        assertEquals(new HitSource.Vanilla("ENTITY_ATTACK"), tx.context().source());
        assertEquals(ATTACKER, tx.context().attackerId());
        assertEquals(VICTIM, tx.context().victimId());
        assertEquals(new TickStamp(7), tx.context().registeredAt());

        HitTransaction second = router.mintVanilla(
                new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM, false);
        assertNotEquals(tx.context().id().value(), second.context().id().value(), "ids are monotonic");
    }

    @Test
    void mintVanillaCarriesTheLiveSprintValueForTheJournal() {
        DamageRouter router = new DamageRouter(null, () -> new TickStamp(3), new HitIds());

        // A sprinting vanilla attacker mints a SPRINTING verdict so the journal reads
        // sprint=t (the engine reads live isSprinting() — the mint must match it, S4).
        HitTransaction sprinting = router.mintVanilla(
                new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM, true);
        assertTrue(sprinting.context().sprint().sprinting(),
                "a sprinting vanilla attacker mints a sprinting verdict (journal honesty)");
        assertNull(sprinting.context().sprint().fresh(),
                "fresh stays null — no wire view exists for a Vanilla mint");

        HitTransaction notSprinting = router.mintVanilla(
                new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM, false);
        assertFalse(notSprinting.context().sprint().sprinting());
    }
}
