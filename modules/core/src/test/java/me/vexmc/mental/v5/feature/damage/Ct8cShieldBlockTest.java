package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * The Combat Test 8c shield decision pins (design spec §2.6). Drives the
 * pure, event-shaped seams of {@link Ct8cShieldUnit} directly — the 148° arc
 * test, the hardcoded 5-damage cap with passthrough, and the axe-disable ticks
 * — over the same proxy-entity event fixture the sword-block composition pin
 * uses. The arc/cap/disable NUMBERS live in the kernel {@code Ct8cShieldMath}
 * (unit-pinned there); these pins assert the UNIT drives that math correctly
 * over real geometry and a real damage event.
 */
class Ct8cShieldBlockTest {

    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /* ------------------------------------------------------------------ */
    /*  The 148° arc                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void headOnHitIsBlocked() {
        // The victim looks straight at the attacker: the view direction and the
        // attacker→victim push direction are opposite, so dot = −1, ×π = −π,
        // well below the −0.8726646 arc limit — inside the ≈148° cone (spec §2.6).
        Vector view = new Vector(0.0, 0.0, -1.0);          // victim looks toward −Z
        Vector attackerToVictim = new Vector(0.0, 0.0, 1.0); // attacker sits at −Z of the victim
        assertTrue(Ct8cShieldUnit.blocksHit(view, attackerToVictim),
                "a head-on hit (dot·π ≈ −π) is inside the 148° cone and blocked");
    }

    @Test
    void ninetyDegreeHitIsNotBlocked() {
        // The attacker is 90° off the victim's facing: dot = 0, ×π = 0, which is
        // NOT below the arc limit — outside the cone, so vanilla's wider 180°
        // block must be withdrawn (spec §2.6).
        Vector view = new Vector(0.0, 0.0, -1.0);
        Vector attackerToVictim = new Vector(1.0, 0.0, 0.0);
        assertFalse(Ct8cShieldUnit.blocksHit(view, attackerToVictim),
                "a 90° hit (dot·π = 0) is outside the 148° cone and not blocked");
    }

    @Test
    void theConeBoundaryFallsBetweenSeventyFourDegrees() {
        // The half-angle is arccos(0.8726646/π) ≈ 73.87° (full cone ≈ 148°). A hit
        // whose look/push dot is −0.28 (·π = −0.8796 < limit) is JUST inside; −0.27
        // (·π = −0.8482 > limit) is JUST outside. Bracket the limit from both sides.
        Vector view = new Vector(0.0, 0.0, -1.0);
        assertTrue(Ct8cShieldUnit.blocksHit(view, new Vector(0.95999, 0.0, 0.28)),
                "dot −0.28 (·π < −0.8726646) is just inside the arc");
        assertFalse(Ct8cShieldUnit.blocksHit(view, new Vector(0.96286, 0.0, 0.27)),
                "dot −0.27 (·π > −0.8726646) is just outside the arc");
    }

    @Test
    void theArcFlattensThePushToHorizontalLikeVanilla() {
        // Vanilla flattens the attacker→victim vector to the horizontal plane before
        // the dot (the shield is a yaw cone, pitch-blind). A steeply-vertical push
        // whose horizontal component still points behind the victim's gaze blocks.
        Vector view = new Vector(0.0, 0.0, -1.0);
        Vector steepPush = new Vector(0.0, 5.0, 1.0); // mostly vertical, horizontal = +Z
        assertTrue(Ct8cShieldUnit.blocksHit(view, steepPush),
                "the push flattens to horizontal (+Z) — head-on after the flatten, so blocked");
    }

    /* ------------------------------------------------------------------ */
    /*  The hardcoded 5-damage cap with passthrough                        */
    /* ------------------------------------------------------------------ */

    @Test
    void nativeBlockCapsFiveOfSevenAndPassesTwoThrough() {
        // A natively-blocking victim (vanilla applied a full BLOCKING negate) — the
        // cap rewrites BLOCKING to −5 so exactly 5 is absorbed and 2 lands (spec §2.6).
        EntityDamageByEntityEvent event = event(7.0, -7.0);
        double blocked = Ct8cShieldUnit.applyMeleeCap(event);

        assertEquals(5.0, blocked, 1.0e-9, "min(5.0, 7.0) = 5 blocked");
        assertEquals(2.0, event.getFinalDamage(), 1.0e-9, "the 2 excess passes through the shield");
    }

    @Test
    void crouchEmulationCapsFiveOfSevenWithNoVanillaBlock() {
        // The crouch-to-shield case: vanilla never treated the victim as blocking, so
        // there is no BLOCKING modifier — the cap reduces the final damage directly.
        EntityDamageByEntityEvent event = eventWithoutBlocking(7.0);
        double blocked = Ct8cShieldUnit.applyMeleeCap(event);

        assertEquals(5.0, blocked, 1.0e-9, "min(5.0, 7.0) = 5 blocked");
        assertEquals(2.0, event.getFinalDamage(), 1.0e-9, "5 removed from 7 leaves 2");
    }

    @Test
    void aHitUnderTheCapIsFullyBlocked() {
        // A 3-damage hit is below the 5 cap — fully absorbed, nothing passes.
        EntityDamageByEntityEvent event = event(3.0, -3.0);
        double blocked = Ct8cShieldUnit.applyMeleeCap(event);

        assertEquals(3.0, blocked, 1.0e-9, "min(5.0, 3.0) = 3 blocked");
        assertEquals(0.0, event.getFinalDamage(), 1.0e-9, "a sub-cap hit is fully blocked");
    }

    @Test
    void aCritDoesNotBypassTheCap() {
        // Crits do not bypass (spec §2.6): a 10.5 crit still only loses the 5 cap,
        // so 5.5 lands — the cap is applied to whatever the composed damage is.
        EntityDamageByEntityEvent event = event(10.5, -10.5);
        double blocked = Ct8cShieldUnit.applyMeleeCap(event);

        assertEquals(5.0, blocked, 1.0e-9, "the cap is hardcoded 5.0, crit or not");
        assertEquals(5.5, event.getFinalDamage(), 1.0e-9, "10.5 − 5 = 5.5 lands through the cap");
    }

    /* ------------------------------------------------------------------ */
    /*  Projectile full-block (no 5-cap — 8c blocks projectiles 100%)      */
    /* ------------------------------------------------------------------ */

    @Test
    void projectileBlockAbsorbsInFullPastTheMeleeCap() {
        // A 7-damage projectile is absorbed IN FULL (blocked = amount), where a melee
        // hit of the same size would leave 2 through the 5-cap (spec §2.6, hurt 969–978).
        EntityDamageByEntityEvent event = event(7.0, -7.0);
        double blocked = Ct8cShieldUnit.fullyBlock(event);

        assertEquals(7.0, blocked, 1.0e-9, "the whole 7 is absorbed, not min(5,7)");
        assertEquals(0.0, event.getFinalDamage(), 1.0e-9, "a blocked projectile deals nothing");
    }

    @Test
    void projectileCrouchBlockZeroesDamageWithNoVanillaModifier() {
        // Crouch-to-shield against a projectile: vanilla applied no BLOCKING modifier,
        // so the full block zeroes the final damage directly.
        EntityDamageByEntityEvent event = eventWithoutBlocking(7.0);
        double blocked = Ct8cShieldUnit.fullyBlock(event);

        assertEquals(7.0, blocked, 1.0e-9, "the whole 7 is absorbed");
        assertEquals(0.0, event.getFinalDamage(), 1.0e-9, "crouch-shielding fully stops the projectile");
    }

    /* ------------------------------------------------------------------ */
    /*  Axe disable ticks (32 + 10·Cleaving)                               */
    /* ------------------------------------------------------------------ */

    @Test
    void axeDisableTicksScaleWithCleaving() {
        assertEquals(32, Ct8cShieldUnit.shieldDisableTicks(0), "1.6s base with no Cleaving");
        assertEquals(42, Ct8cShieldUnit.shieldDisableTicks(1), "+0.5s per Cleaving level");
        assertEquals(52, Ct8cShieldUnit.shieldDisableTicks(2), "Cleaving II");
        assertEquals(62, Ct8cShieldUnit.shieldDisableTicks(3), "Cleaving III (max)");
    }

    /* ------------------------------------------------------------------ */
    /*  Fixture (the SwordBlockCompositionTest shapes)                     */
    /* ------------------------------------------------------------------ */

    private static EntityDamageByEntityEvent event(double base, double blocking) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        modifiers.put(DamageModifier.BLOCKING, blocking);
        return build(modifiers);
    }

    private static EntityDamageByEntityEvent eventWithoutBlocking(double base) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        return build(modifiers);
    }

    private static EntityDamageByEntityEvent build(Map<DamageModifier, Double> modifiers) {
        Map<DamageModifier, com.google.common.base.Function<? super Double, Double>> functions =
                new EnumMap<>(DamageModifier.class);
        for (DamageModifier modifier : modifiers.keySet()) {
            functions.put(modifier, value -> value); // identity — BLOCKING already carries its own value
        }
        @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                proxyPlayer(ATTACKER), proxyPlayer(VICTIM), DamageCause.ENTITY_ATTACK, modifiers, functions);
        return event;
    }

    private static Player proxyPlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Ct8cShieldBlockTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "toString" -> "player-stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> primitiveZero(method.getReturnType());
                });
    }

    private static Object primitiveZero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
