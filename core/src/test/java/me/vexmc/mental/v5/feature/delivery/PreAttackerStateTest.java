package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * The pre-send attacker capture's enchant parity (the {@code HitRegistrationPacingTest}
 * sibling). The pure static freezes the view's per-tick Knockback level, so an
 * adopted PRE_SENT/PINNED knock ships the enchant extra — the same value the region
 * path reads live. A view built on the pre-freeze arity ships enchant-blind (the
 * documented fallback = pre-fix behavior).
 */
class PreAttackerStateTest {

    private static final KinematicState KINEMATICS = new KinematicState(64.0, 0.0, true);

    @Test
    void preSendAttackerStateFreezesTheViewEnchant() {
        PlayerView view = new PlayerView(
                UUID.randomUUID(), 1, new TickStamp(3), Decay.Motion.ZERO, true, 0.6, 0.08,
                0.42, -1, false, false, true, 0, 20,
                0.25, KnockbackProfile.LEGACY_17, 0, KINEMATICS, 0.13, null,
                0.0, 0.0, 0.0f, 1.62, 0, Double.NaN, 2);
        SprintVerdict verdict = new SprintVerdict(true, Boolean.TRUE, new TickStamp(5));

        EntityState state = HitRegistrationUnit.preAttackerState(1.0, 65.0, -3.0, 47.5f, view, verdict);

        assertEquals(1.0, state.x());
        assertEquals(65.0, state.y());
        assertEquals(-3.0, state.z());
        assertEquals(47.5f, state.yaw());
        assertEquals(0.0, state.vx());
        assertEquals(0.0, state.vy());
        assertEquals(0.0, state.vz());
        assertEquals(true, state.grounded());
        assertEquals(true, state.sprinting());   // the stamped verdict
        assertEquals(2, state.knockbackEnchantLevel()); // the view's frozen enchant
        assertEquals(0.25, state.knockbackResistance());
        assertEquals(0.13, state.moveSpeedAttr());
    }

    @Test
    void preEnchantViewShipsEnchantBlind() {
        // The pre-freeze view arity ⇒ kbEnchantLevel defaults to 0 (the fallback =
        // the historical enchant-blind pre-send).
        PlayerView view = new PlayerView(
                UUID.randomUUID(), 1, new TickStamp(3), Decay.Motion.ZERO, true, 0.6, 0.08,
                0.42, -1, false, false, true, 0, 20,
                0.25, KnockbackProfile.LEGACY_17, 0, KINEMATICS, 0.13, null,
                0.0, 0.0, 0.0f, 1.62, 0, Double.NaN);
        SprintVerdict verdict = new SprintVerdict(true, Boolean.TRUE, new TickStamp(5));

        EntityState state = HitRegistrationUnit.preAttackerState(1.0, 65.0, -3.0, 47.5f, view, verdict);
        assertEquals(0, state.knockbackEnchantLevel());
    }
}
