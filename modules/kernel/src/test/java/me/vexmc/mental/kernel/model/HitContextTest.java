package me.vexmc.mental.kernel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The registration-yaw stamp on {@link HitContext}: the 8-arg (pre-stamp) arity
 * defaults it to null (Vanilla-source mints, projectile/rod contexts, packetless
 * attackers), while the 9-arg carries the frozen click-flush yaw the recompute
 * consumes.
 */
class HitContextTest {

    @Test
    void preYawArityDefaultsTheRegistrationYawToNull() {
        HitId id = new HitId(7);
        HitSource source = new HitSource.Melee();
        UUID attacker = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        SprintVerdict sprint = new SprintVerdict(true, Boolean.TRUE, new TickStamp(5));
        TickStamp registeredAt = new TickStamp(9);

        HitContext preStamp = new HitContext(
                id, source, attacker, victim, sprint, true, 0.25, registeredAt);
        assertNull(preStamp.attackerYaw());

        HitContext stamped = new HitContext(
                id, source, attacker, victim, sprint, true, 0.25, registeredAt, 33.5f);
        assertEquals(33.5f, stamped.attackerYaw());
        // The other eight components are carried 1:1.
        assertEquals(id, stamped.id());
        assertEquals(source, stamped.source());
        assertEquals(attacker, stamped.attackerId());
        assertEquals(victim, stamped.victimId());
        assertEquals(sprint, stamped.sprint());
        assertEquals(true, stamped.victimHasWire());
        assertEquals(0.25, stamped.compensationY());
        assertEquals(registeredAt, stamped.registeredAt());
    }
}
