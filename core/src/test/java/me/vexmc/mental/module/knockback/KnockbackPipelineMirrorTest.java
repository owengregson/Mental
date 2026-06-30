package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure decision the {@code EntityKnockbackEvent} mirror makes so it can
 * never diverge from what {@code onPlayerVelocity} actually ships.
 *
 * <p>The mirror peeks the FIFO head and asserts the same value the velocity
 * event will: a pre-delivered/pinned pending ships its {@code preDelivered}
 * value verbatim (the reported SimpleBoxer-on-Folia case), a null-vector pending
 * means the resistance roll will suppress (the mirror cancels the event), and no
 * pending means a non-Mental hit the mirror must leave to vanilla.</p>
 */
class KnockbackPipelineMirrorTest {

    private static Pending pending(KnockbackVector vector, KnockbackVector preDelivered) {
        return new Pending(vector, preDelivered, preDelivered != null, null,
                KnockbackPipeline.Cause.MELEE, true, 0L);
    }

    @Test
    void preDeliveredWinsForAPinnedPendingTheMirrorPeeks() {
        KnockbackVector vector = new KnockbackVector(1.0, 0.25, 0.0);
        KnockbackVector shipped = new KnockbackVector(0.9, 0.25, 0.0);
        Pending pinned = pending(vector, shipped);
        // The mirror peeks with the pending's own vector as the (no-API) velocity,
        // so a pre-delivered/pinned knock ships its preDelivered value verbatim —
        // exactly what onPlayerVelocity does. This is the airborne 2nd-hit case.
        assertTrue(KnockbackPipeline.preDeliveredWins(pinned, vector.toBukkit()));
    }

    @Test
    void preDeliveredDoesNotWinWithoutAPreDeliveredValue() {
        // A plain submit() pending (projectile/arrow/rod, or a non-pre-sent melee)
        // has no preDelivered value; the mirror must fall through to deliveryAdjusted.
        Pending plain = pending(new KnockbackVector(1.0, 0.4, 0.0), null);
        assertFalse(KnockbackPipeline.preDeliveredWins(plain, new Vector(1.0, 0.4, 0.0)));
    }

    @Test
    void preDeliveredDoesNotWinWhenAnApiListenerChangedTheVelocity() {
        KnockbackVector vector = new KnockbackVector(1.0, 0.25, 0.0);
        Pending pre = pending(vector, new KnockbackVector(0.9, 0.25, 0.0));
        // onPlayerVelocity only ships preDelivered when the API event left the
        // vector untouched; a modified velocity falls through to a corrective send.
        assertFalse(KnockbackPipeline.preDeliveredWins(pre, new Vector(2.0, 0.4, 0.0)));
    }

    @Test
    void mirrorKindClassifiesTheHead() {
        assertEquals(KnockbackPipeline.MirrorKind.NONE, KnockbackPipeline.mirrorKind(null),
                "no live head -> leave vanilla's value (non-Mental hit / OCM-owned)");
        assertEquals(KnockbackPipeline.MirrorKind.CANCEL,
                KnockbackPipeline.mirrorKind(pending(null, null)),
                "null vector -> the resistance roll will suppress; cancel the event");
        assertEquals(KnockbackPipeline.MirrorKind.SHIP,
                KnockbackPipeline.mirrorKind(pending(new KnockbackVector(1.0, 0.4, 0.0), null)),
                "a real vector -> mirror the shipped value");
    }
}
