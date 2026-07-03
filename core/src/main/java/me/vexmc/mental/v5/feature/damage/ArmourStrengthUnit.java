package me.vexmc.mental.v5.feature.damage;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import me.vexmc.mental.kernel.math.DefenceMath;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores the pre-1.9 flat armour damage reduction (the retired
 * {@code module.damage.ArmourStrengthModule} on the v5 seams), replacing
 * vanilla's modern toughness model. Listens {@link EntityDamageEvent}
 * <em>EntityDamageEvent-wide</em> (armour reduces every physical cause, not only
 * player attacks) at {@link EventPriority#LOWEST}, rewriting the defensive
 * modifiers (armour → resistance → enchant EPF → absorption) off the pure kernel
 * {@link DefenceMath} in the era order. Only modifiers
 * {@link EntityDamageEvent#isApplicable(DamageModifier)} reports present are ever
 * read or written, so the deprecated granular setter never throws (OCM's proven
 * guard). {@code EntityDamageEvent} fires on the victim's region thread, so every
 * read/write is inline; the 1.8 model needs no attacker state.
 *
 * <p><strong>Toughness is never read.</strong> The reduction is a function of the
 * {@code ARMOR} points alone ({@link #armourPoints}); the {@code ARMOR_TOUGHNESS}
 * attribute is neither read nor written anywhere in this unit, so the feature
 * composes byte-identically on servers where that attribute does not exist
 * (absent below 1.11.2 — 1.9.4/1.10.2). Its manifest entry is therefore an
 * {@code OptionalSince(1.11.2)}, not a {@code Required} handle whose absence would
 * disable the feature (see {@code PlatformProfile}).</p>
 */
public final class ArmourStrengthUnit implements FeatureUnit, Listener {

    /** Tolerance for "era result equals what vanilla already computed" skip checks. */
    private static final double WRITE_EPSILON = 1.0e-6;

    /** Damage causes that bypass armour entirely in 1.8 — the era NMS cause set. */
    private static final Set<DamageCause> ARMOUR_IGNORING_CAUSES = EnumSet.of(
            DamageCause.FIRE_TICK,
            DamageCause.SUFFOCATION,
            DamageCause.DROWNING,
            DamageCause.STARVATION,
            DamageCause.FALL,
            DamageCause.VOID,
            DamageCause.CUSTOM,
            DamageCause.MAGIC,
            DamageCause.WITHER,
            DamageCause.FLY_INTO_WALL,
            DamageCause.DRAGON_BREATH);

    static {
        addCauseIfPresent(ARMOUR_IGNORING_CAUSES, "CRAMMING");
        addCauseIfPresent(ARMOUR_IGNORING_CAUSES, "FREEZE");
    }

    private static final @Nullable Method GET_BY_KEY;
    private static final @Nullable PotionEffectType RESISTANCE;

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException | LinkageError ignored) {
            // Pre-1.20.5 — getByName fallback. LinkageError also catches the NamespacedKey.class literal
            // being absent below 1.12 (the backport's oldest targets), so this static init is safe.
        }
        GET_BY_KEY = m;
        RESISTANCE = resolveResistance();
    }

    @Override
    public Feature descriptor() {
        return Feature.ARMOUR_STRENGTH;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        // The ARMOR modifier is the anchor: no armour applied ⇒ nothing to override.
        if (!event.isApplicable(DamageModifier.ARMOR)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (ARMOUR_IGNORING_CAUSES.contains(cause)) {
            return;
        }

        // Damage entering the armour stage = vanilla's pre-armour total: BASE plus
        // the upstream HARD_HAT and BLOCKING reductions (negative). Those stages
        // are never touched — only the defensive ones below.
        double preArmour = event.getDamage(DamageModifier.BASE)
                + applicable(event, DamageModifier.HARD_HAT)
                + applicable(event, DamageModifier.BLOCKING);
        if (preArmour <= 0.0) {
            return;
        }

        int armourPoints = armourPoints(victim);
        // VOID & STARVATION bypass resistance in the era NMS.
        int resistanceLevel = (cause == DamageCause.VOID || cause == DamageCause.STARVATION)
                ? 0
                : resistanceLevel(victim);
        int epf = enchantEpf(victim, cause);
        double absorption = victim.getAbsorptionAmount();

        double afterArmour = DefenceMath.armourReduced(preArmour, armourPoints);
        setIfChanged(event, DamageModifier.ARMOR, afterArmour - preArmour);

        double afterResistance = DefenceMath.resistanceReduced(afterArmour, resistanceLevel);
        if (event.isApplicable(DamageModifier.RESISTANCE)) {
            setIfChanged(event, DamageModifier.RESISTANCE, afterResistance - afterArmour);
        }

        double afterEnchant = DefenceMath.enchantReduced(afterResistance, epf);
        if (event.isApplicable(DamageModifier.MAGIC)) {
            setIfChanged(event, DamageModifier.MAGIC, afterEnchant - afterResistance);
        }

        double afterAbsorption = DefenceMath.absorptionReduced(afterEnchant, absorption);
        if (event.isApplicable(DamageModifier.ABSORPTION)) {
            setIfChanged(event, DamageModifier.ABSORPTION, afterAbsorption - afterEnchant);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Victim state reads (victim's region thread)                        */
    /* ------------------------------------------------------------------ */

    private static int armourPoints(@NotNull LivingEntity victim) {
        double value = Attributes.valueOr(victim, Attributes.armor(), 0.0);
        return (int) Math.round(value);
    }

    private static int resistanceLevel(@NotNull LivingEntity victim) {
        if (RESISTANCE == null) {
            return 0;
        }
        PotionEffect effect = victim.getPotionEffect(RESISTANCE);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    private static int enchantEpf(@NotNull LivingEntity victim, @NotNull DamageCause cause) {
        EntityEquipment equipment = victim.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack piece : equipment.getArmorContents()) {
            if (piece == null || piece.getType().isAir()) {
                continue;
            }
            for (ArmourEnchant enchant : ArmourEnchant.values()) {
                if (!enchant.protectsAgainst(cause)) {
                    continue;
                }
                Enchantment bukkit = enchant.enchantment();
                if (bukkit == null) {
                    continue;
                }
                int level = piece.getEnchantmentLevel(bukkit);
                if (level > 0) {
                    total += DefenceMath.epf(level, enchant.typeModifier());
                }
            }
        }
        return DefenceMath.clampEpf(total);
    }

    /* ------------------------------------------------------------------ */
    /*  Event modifier helpers                                             */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("deprecation")
    private static double applicable(@NotNull EntityDamageEvent event, @NotNull DamageModifier modifier) {
        return event.isApplicable(modifier) ? event.getDamage(modifier) : 0.0;
    }

    @SuppressWarnings("deprecation")
    private static void setIfChanged(
            @NotNull EntityDamageEvent event, @NotNull DamageModifier modifier, double value) {
        if (Math.abs(event.getDamage(modifier) - value) > WRITE_EPSILON) {
            event.setDamage(modifier, value);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Resolution helpers                                                 */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType resolveResistance() {
        if (GET_BY_KEY != null) {
            try {
                Object result = GET_BY_KEY.invoke(null, NamespacedKey.minecraft("resistance"));
                if (result instanceof PotionEffectType type) {
                    return type;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to getByName.
            }
        }
        return PotionEffectType.getByName("DAMAGE_RESISTANCE");
    }

    private static void addCauseIfPresent(@NotNull Set<DamageCause> set, @NotNull String name) {
        try {
            set.add(DamageCause.valueOf(name));
        } catch (IllegalArgumentException absent) {
            // Cause does not exist on this API build — ignore.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Armour enchant table (type modifier + protected causes)            */
    /* ------------------------------------------------------------------ */

    private enum ArmourEnchant {
        PROTECTION(DefenceMath.PROTECTION_MODIFIER, Enchantments.protection(), generalCauses()),
        FIRE_PROTECTION(DefenceMath.FIRE_PROTECTION_MODIFIER, Enchantments.fireProtection(),
                causes(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, "HOT_FLOOR")),
        FEATHER_FALLING(DefenceMath.FEATHER_FALLING_MODIFIER, Enchantments.featherFalling(),
                causes(DamageCause.FALL)),
        BLAST_PROTECTION(DefenceMath.BLAST_PROTECTION_MODIFIER, Enchantments.blastProtection(),
                causes(DamageCause.ENTITY_EXPLOSION, DamageCause.BLOCK_EXPLOSION)),
        PROJECTILE_PROTECTION(DefenceMath.PROJECTILE_PROTECTION_MODIFIER, Enchantments.projectileProtection(),
                causes(DamageCause.PROJECTILE));

        private final double typeModifier;
        private final @Nullable Enchantment enchantment;
        private final Set<DamageCause> protectedCauses;

        ArmourEnchant(double typeModifier, @Nullable Enchantment enchantment, Set<DamageCause> protectedCauses) {
            this.typeModifier = typeModifier;
            this.enchantment = enchantment;
            this.protectedCauses = protectedCauses;
        }

        double typeModifier() {
            return typeModifier;
        }

        @Nullable Enchantment enchantment() {
            return enchantment;
        }

        boolean protectsAgainst(DamageCause cause) {
            return protectedCauses.contains(cause);
        }

        private static Set<DamageCause> generalCauses() {
            Set<DamageCause> set = causes(
                    DamageCause.CONTACT,
                    DamageCause.ENTITY_ATTACK,
                    DamageCause.PROJECTILE,
                    DamageCause.FALL,
                    DamageCause.FIRE,
                    DamageCause.LAVA,
                    DamageCause.BLOCK_EXPLOSION,
                    DamageCause.ENTITY_EXPLOSION,
                    DamageCause.LIGHTNING,
                    DamageCause.POISON,
                    DamageCause.MAGIC,
                    DamageCause.WITHER,
                    DamageCause.FALLING_BLOCK,
                    DamageCause.THORNS,
                    DamageCause.DRAGON_BREATH);
            addCauseByName(set, "HOT_FLOOR");
            addCauseByName(set, "ENTITY_SWEEP_ATTACK");
            return set;
        }

        private static Set<DamageCause> causes(Object... values) {
            Set<DamageCause> set = EnumSet.noneOf(DamageCause.class);
            for (Object value : values) {
                if (value instanceof DamageCause cause) {
                    set.add(cause);
                } else if (value instanceof String name) {
                    addCauseByName(set, name);
                }
            }
            return set;
        }

        private static void addCauseByName(Set<DamageCause> set, String name) {
            try {
                set.add(DamageCause.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException absent) {
                // Cause absent on this API build — ignore.
            }
        }
    }
}
