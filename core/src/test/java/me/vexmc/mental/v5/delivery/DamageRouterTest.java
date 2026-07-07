package me.vexmc.mental.v5.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

        HitTransaction tx = router.mintVanilla(new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM);

        assertEquals(HitTransaction.State.REGISTERED, tx.state());
        assertEquals(new HitSource.Vanilla("ENTITY_ATTACK"), tx.context().source());
        assertEquals(ATTACKER, tx.context().attackerId());
        assertEquals(VICTIM, tx.context().victimId());
        assertEquals(new TickStamp(7), tx.context().registeredAt());

        HitTransaction second = router.mintVanilla(new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM);
        assertNotEquals(tx.context().id().value(), second.context().id().value(), "ids are monotonic");
    }
}
