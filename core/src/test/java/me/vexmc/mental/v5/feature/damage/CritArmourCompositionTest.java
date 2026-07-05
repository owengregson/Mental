package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.junit.jupiter.api.Test;

/**
 * The intra-LOWEST composition pin (interaction audit: CRIT_FALLBACK ×
 * ARMOUR_STRENGTH). Both units write the same event at {@code LOWEST}; Bukkit
 * runs a priority bucket in REGISTRATION order, which the reconciler derives
 * from Feature declaration order on first boot and a GUI toggle silently
 * re-orders (unregister + re-append at the bucket tail). The era rule is crit
 * BEFORE armour — 1.8's {@code EntityHuman.attack} multiplies ×1.5 before
 * {@code damageEntity} runs {@code applyArmorCalculations} — so the audit's
 * failure scenario (sprint-crit B=7.0 into 20 armour points) must come out at
 * the era {@code 10.5 × 0.2 = 2.1} in BOTH listener orders, not the ~4.9 the
 * stale-cascade order produced (armour sized against the un-critted 7.0),
 * and not differently across reload histories.
 *
 * <p>The victim/attacker are dynamic proxies (the project ships no
 * Mockito/MockBukkit): the victim carries a 20-point armour attribute, no
 * equipment and no effects; the attacker is airborne with fall distance (the
 * legacy-crit posture) and zero attack charge. The event is the deprecated but
 * constructible modifier-map form — the same shape CraftBukkit hands the
 * listeners.</p>
 */
class CritArmourCompositionTest {

    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    /** Era expectation: crit 7.0 → 10.5, then 20 armour points reduce 80% → 2.1. */
    private static final double ERA_FINAL = 2.1;

    @Test
    void armourFirstThenCritComposesToTheEraResult() throws Exception {
        EntityDamageByEntityEvent event = event(7.0, 20.0);
        ArmourStrengthUnit armour = new ArmourStrengthUnit();
        CritFallbackUnit crit = crit();

        armour.onEntityDamage(event); // first-boot declaration order
        crit.onDamage(event);

        assertEquals(ERA_FINAL, event.getFinalDamage(), 1.0e-9,
                "armour-then-crit must still produce the era crit-before-armour result "
                        + "(the crit fold re-runs the cascade over the raised BASE)");
    }

    @Test
    void critFirstThenArmourComposesToTheSameEraResult() throws Exception {
        EntityDamageByEntityEvent event = event(7.0, 20.0);
        ArmourStrengthUnit armour = new ArmourStrengthUnit();
        CritFallbackUnit crit = crit();

        crit.onDamage(event); // the order a GUI re-enable of old-armour-strength leaves behind
        armour.onEntityDamage(event);

        assertEquals(ERA_FINAL, event.getFinalDamage(), 1.0e-9,
                "crit-then-armour must produce the identical era result — damage math must "
                        + "not depend on the admin's toggle history");
    }

    @Test
    void doubleCascadeIsIdempotent() throws Exception {
        EntityDamageByEntityEvent event = event(7.0, 20.0);
        ArmourStrengthUnit armour = new ArmourStrengthUnit();
        CritFallbackUnit crit = crit();

        armour.onEntityDamage(event);
        crit.onDamage(event);
        armour.onEntityDamage(event); // a second cascade over the settled state

        assertEquals(ERA_FINAL, event.getFinalDamage(), 1.0e-9,
                "re-running the cascade over the settled state must change nothing");
    }

    /* ------------------------------------------------------------------ */
    /*  Fixture                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * A crit unit whose snapshot enables ARMOUR_STRENGTH with the fast path OFF
     * (the audit scenario's scope) and whose ownership always answers "Mental".
     */
    private static CritFallbackUnit crit() throws Exception {
        Snapshot snapshot = parse("""
                modules:
                  old-armour-strength: true
                  old-critical-hits: true
                """, """
                hit-registration:
                  fast-path:
                    enabled: false
                """);
        return new CritFallbackUnit(new DamageOwnership((token, id) -> true), () -> snapshot);
    }

    private static Snapshot parse(String main, String hitReg) throws Exception {
        YamlConfiguration mainYaml = new YamlConfiguration();
        mainYaml.loadFromString(main);
        YamlConfiguration hitRegYaml = new YamlConfiguration();
        hitRegYaml.loadFromString(hitReg);
        return SnapshotParser.parse(
                mainYaml, new YamlConfiguration(), hitRegYaml, new YamlConfiguration(), Map.of())
                .snapshot();
    }

    /**
     * The audit's event shape: BASE 7.0 with a vanilla-computed ARMOR modifier the
     * cascade will overwrite, all four defensive modifiers applicable. The
     * modifier FUNCTIONS only matter to the full {@code setDamage(double)} setter,
     * which nothing in this composition calls — identity keeps them inert.
     */
    private static EntityDamageByEntityEvent event(double base, double armourPoints) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        modifiers.put(DamageModifier.BLOCKING, 0.0);
        modifiers.put(DamageModifier.ARMOR, -base * 0.4); // a vanilla-ish stale value to overwrite
        modifiers.put(DamageModifier.RESISTANCE, 0.0);
        modifiers.put(DamageModifier.MAGIC, 0.0);
        modifiers.put(DamageModifier.ABSORPTION, 0.0);
        // The 1.17.1 floor constructor takes Guava functions; only the full
        // setDamage(double) setter ever consults them, which this pass never calls.
        Map<DamageModifier, com.google.common.base.Function<? super Double, Double>> functions =
                new EnumMap<>(DamageModifier.class);
        for (DamageModifier modifier : modifiers.keySet()) {
            functions.put(modifier, value -> 0.0);
        }
        @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker(), victim(armourPoints), DamageCause.ENTITY_ATTACK, modifiers, functions);
        return event;
    }

    /** A sprint-crit-posture attacker: airborne with fall distance, zero charge. */
    private static Player attacker() {
        return (Player) Proxy.newProxyInstance(
                CritArmourCompositionTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ATTACKER;
                    case "getFallDistance" -> 1.5f;
                    case "isOnGround" -> false;
                    case "isSprinting" -> true; // era crits have NO sprint exclusion
                    case "hasPotionEffect" -> false;
                    case "getVehicle" -> null;
                    default -> defaultValue(method, proxy, args);
                });
    }

    /** A victim holding {@code armourPoints} on the armour attribute, nothing else. */
    private static Player victim(double armourPoints) {
        Object armour = Proxy.newProxyInstance(
                CritArmourCompositionTest.class.getClassLoader(), new Class[]{AttributeInstance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue", "getBaseValue" -> armourPoints;
                    default -> defaultValue(method, proxy, args);
                });
        return (Player) Proxy.newProxyInstance(
                CritArmourCompositionTest.class.getClassLoader(), new Class[]{Player.class},
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
