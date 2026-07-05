package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.junit.jupiter.api.Test;

/**
 * The sword-block × era-armour composition pin (interaction audit: the software
 * reduction's {@code setDamage(double)} total setter shifted the era-written
 * defensive modifiers by MODERN-formula deltas — a hybrid neither era nor
 * vanilla). The audit's failure scenario: B=7.0 blocked in full iron (15 armour
 * points, era 60% reduction). Era order is block-then-armour ({@code
 * EntityHuman.damageEntity} halves before {@code applyArmorCalculations}):
 * {@code block(7.0) = 4.0}, then {@code 4.0 × (1 − 0.6) = 1.6} — where the
 * drifted composition landed at ~0.94.
 *
 * <p>Drives {@link SwordBlockingUnit#applyBlockedDamage} directly (the
 * package-private seam the HIGH listener delegates to after its decoration
 * gates), over the same proxy-entity event shape the crit×armour pin uses.</p>
 */
class SwordBlockCompositionTest {

    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** Era: block(7.0) = 4.0 pre-armour, then full iron (15 pts, 60%) → 1.6. */
    private static final double ERA_BLOCKED_IRON = 1.6;

    @Test
    void softwareBlockRecascadesTheEraArmourOverTheBlockedDamage() {
        // The armour cascade has already run at LOWEST (era ARMOR off the
        // unblocked 7.0 = −4.2); the block at HIGH must re-shape it.
        EntityDamageByEntityEvent event = event(7.0, 15.0, 0.0);
        ArmourStrengthUnit.applyEraCascade(event); // the LOWEST pass
        SwordBlockingUnit.applyBlockedDamage(event, /*eraArmourCascade=*/ true);

        assertEquals(ERA_BLOCKED_IRON, event.getFinalDamage(), 1.0e-9,
                "software block + era armour must compose block-then-armour: "
                        + "block(7.0)=4.0, iron 60% → 1.6 (the audit's drifted 0.94 is the bug)");
    }

    @Test
    void vanillaFullBlockedHitRecascadesToTheSameEraResult() {
        // The temp-shield frontal case (audit C1): vanilla arrived with a full
        // negate. The rewrite to the era value must land the same 1.6 under the
        // era cascade — one era number for a blocked iron hit, whatever the tier.
        EntityDamageByEntityEvent event = event(7.0, 15.0, -7.0);
        SwordBlockingUnit.applyBlockedDamage(event, /*eraArmourCascade=*/ true);

        assertEquals(ERA_BLOCKED_IRON, event.getFinalDamage(), 1.0e-9,
                "a vanilla-full-blocked hit must rewrite BLOCKING to the era value and "
                        + "re-cascade — half damage through era armour, never zero");
    }

    @Test
    void unarmouredEraBlockIsTheHalfDamageValue() {
        // No armour applicable: block(7.0) = 4.0 lands whole (the flagship number
        // BlockingSuite pins live). ARMOR is absent so the cascade anchor skips.
        EntityDamageByEntityEvent event = eventWithoutArmour(7.0, -7.0);
        SwordBlockingUnit.applyBlockedDamage(event, /*eraArmourCascade=*/ true);

        assertEquals(4.0, event.getFinalDamage(), 1.0e-9,
                "an unarmoured vanilla-full-blocked hit must land the era (7+1)/2 = 4.0");
    }

    /* ------------------------------------------------------------------ */
    /*  Fixture (the CritArmourCompositionTest shapes)                     */
    /* ------------------------------------------------------------------ */

    private static EntityDamageByEntityEvent event(double base, double armourPoints, double blocking) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        modifiers.put(DamageModifier.BLOCKING, blocking);
        modifiers.put(DamageModifier.ARMOR, 0.0);
        modifiers.put(DamageModifier.RESISTANCE, 0.0);
        modifiers.put(DamageModifier.MAGIC, 0.0);
        modifiers.put(DamageModifier.ABSORPTION, 0.0);
        return build(modifiers, victim(armourPoints));
    }

    /** An event whose victim carries no ARMOR modifier at all (the cascade anchor skips). */
    private static EntityDamageByEntityEvent eventWithoutArmour(double base, double blocking) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        modifiers.put(DamageModifier.BLOCKING, blocking);
        return build(modifiers, victim(0.0));
    }

    private static EntityDamageByEntityEvent build(
            Map<DamageModifier, Double> modifiers, Player victim) {
        Map<DamageModifier, com.google.common.base.Function<? super Double, Double>> functions =
                new EnumMap<>(DamageModifier.class);
        for (DamageModifier modifier : modifiers.keySet()) {
            functions.put(modifier, value -> 0.0);
        }
        @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker(), victim, DamageCause.ENTITY_ATTACK, modifiers, functions);
        return event;
    }

    private static Player attacker() {
        return (Player) Proxy.newProxyInstance(
                SwordBlockCompositionTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ATTACKER;
                    default -> defaultValue(method, proxy, args);
                });
    }

    private static Player victim(double armourPoints) {
        Object armour = Proxy.newProxyInstance(
                SwordBlockCompositionTest.class.getClassLoader(), new Class[]{AttributeInstance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue", "getBaseValue" -> armourPoints;
                    default -> defaultValue(method, proxy, args);
                });
        return (Player) Proxy.newProxyInstance(
                SwordBlockCompositionTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> VICTIM;
                    case "getAttribute" -> armour;
                    case "getActivePotionEffects" -> java.util.List.of();
                    case "getEquipment" -> null;
                    default -> defaultValue(method, proxy, args);
                });
    }

    /** Sensible defaults for un-stubbed proxy calls (Object identity + typed zeroes). */
    private static Object defaultValue(java.lang.reflect.Method method, Object proxy, Object[] args) {
        switch (method.getName()) {
            case "toString": return method.getDeclaringClass().getSimpleName() + "-stub";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return proxy == args[0];
            default:         return primitiveZero(method.getReturnType());
        }
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
