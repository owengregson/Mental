package me.vexmc.mental.v5.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * The freshness contract: {@link ViewBuilder} stamps {@code at} from the clock
 * and carries every ingredient into the {@link PlayerView} 1:1 — it invents
 * nothing but the tick.
 */
class ViewBuilderTest {

    @Test
    void carriesIngredientsOneToOneAndStampsTheClock() {
        TickStamp stamp = new TickStamp(1234);
        ViewBuilder builder = new ViewBuilder(() -> stamp);

        UUID id = UUID.randomUUID();
        Decay.Motion motion = new Decay.Motion(0.9, 0.4608, -0.1);
        KnockbackProfile profile = KnockbackProfile.LEGACY_17;
        KinematicState kinematics = new KinematicState(72.5, 1.25, false);

        PlayerView view = builder.build(
                id, 42, motion, false, 0.98,
                0.081, 0.42, 3, true, false, true,
                7, 20, 0.25, profile, 55, kinematics, 0.13);

        assertEquals(id, view.id());
        assertEquals(42, view.entityId());
        assertEquals(stamp, view.at(), "at is the clock's stamp — the freshness contract");
        assertSame(motion, view.motion());
        assertEquals(false, view.grounded());
        assertEquals(0.98, view.slipperiness());
        assertEquals(0.081, view.gravity());
        assertEquals(0.42, view.jumpImpulse());
        assertEquals(3, view.jumpBoostAmplifier());
        assertTrue(view.sprinting());
        assertEquals(false, view.creative());
        assertTrue(view.pvpAllowed());
        assertEquals(7, view.noDamageTicks());
        assertEquals(20, view.maxNoDamageTicks());
        assertEquals(0.25, view.knockbackResistance());
        assertSame(profile, view.profile());
        assertEquals(55, view.pingMillis());
        assertSame(kinematics, view.kinematics());
        assertEquals(0.13, view.moveSpeedAttr()); // the attacker's movement-speed attribute, carried 1:1
    }

    @Test
    void enchantOverloadCarriesTheLevelAndOldOverloadsDefaultZero() {
        ViewBuilder builder = new ViewBuilder(() -> new TickStamp(1));
        UUID id = UUID.randomUUID();
        Decay.Motion motion = Decay.Motion.ZERO;
        KnockbackProfile profile = KnockbackProfile.LEGACY_17;
        KinematicState kinematics = new KinematicState(64.0, 0.0, true);

        // The full enchant-freeze overload carries the level.
        PlayerView enchanted = builder.build(
                id, 1, motion, true, 0.6, 0.08, 0.42, -1, false, false, true,
                0, 20, 0.0, profile, 0, kinematics, 0.1, null,
                0.0, 0.0, 0.0f, 1.62, 0, Double.NaN, 2);
        assertEquals(2, enchanted.kbEnchantLevel());

        // The 18-arg overload defaults it to 0.
        PlayerView base = builder.build(
                id, 1, motion, true, 0.6, 0.08, 0.42, -1, false, false, true,
                0, 20, 0.0, profile, 0, kinematics, 0.1);
        assertEquals(0, base.kbEnchantLevel());

        // The precision (pre-freeze) overload defaults it to 0.
        PlayerView precision = builder.build(
                id, 1, motion, true, 0.6, 0.08, 0.42, -1, false, false, true,
                0, 20, 0.0, profile, 0, kinematics, 0.1, null,
                0.0, 0.0, 0.0f, 1.62, 0, Double.NaN);
        assertEquals(0, precision.kbEnchantLevel());
    }

    @Test
    void everyBuildRestampsFromTheCurrentClock() {
        int[] tick = {0};
        ViewBuilder builder = new ViewBuilder(() -> new TickStamp(tick[0]));
        KinematicState kinematics = new KinematicState(0.0, 0.0, true);

        tick[0] = 5;
        PlayerView first = builder.build(UUID.randomUUID(), 1, Decay.Motion.ZERO, true, 0.6,
                0.08, 0.42, -1, false, false, true, 0, 20, 0.0,
                KnockbackProfile.LEGACY_17, 0, kinematics, 0.1);
        tick[0] = 6;
        PlayerView second = builder.build(UUID.randomUUID(), 1, Decay.Motion.ZERO, true, 0.6,
                0.08, 0.42, -1, false, false, true, 0, 20, 0.0,
                KnockbackProfile.LEGACY_17, 0, kinematics, 0.1);

        assertEquals(new TickStamp(5), first.at());
        assertEquals(new TickStamp(6), second.at());
    }
}
