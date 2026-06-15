package me.vexmc.mental.module.damage;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
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
 * Restores the pre-1.9 (flat) armour damage reduction, replacing vanilla's
 * modern toughness-based model.
 *
 * <h2>What this overrides</h2>
 * <p>Modern Minecraft reduces damage with
 * {@code effArmor = clamp(armor - dmg/(2+tough/4), armor*0.2, 20)}
 * (decomp-1.21.11 {@code CombatRules.java}). The 1.8 model is flat: 4% per
 * armour point, with no toughness term. The full era pipeline (order: armour →
 * resistance → enchant EPF → absorption) lives in {@link DefenceMath}; this
 * class is the thin Bukkit adapter that reads the victim's worn armour /
 * resistance / enchants / absorption and applies that math to the damage event.</p>
 *
 * <h2>Why {@link EntityDamageEvent} (not ...ByEntity)</h2>
 * <p>Armour reduces <em>all</em> physical damage — mob melee, projectiles,
 * explosions, lava, fire, contact — not only player attacks. The listener is on
 * the general event so every armour-respecting cause is covered. Armour-ignoring
 * causes (fall, drowning, starvation, void, magic, wither, …) are excluded by
 * {@link #ARMOUR_IGNORING_CAUSES}, matching the 1.8 NMS cause set.</p>
 *
 * <h2>The cross-version override mechanism (verified)</h2>
 * <p>This mirrors OCM's <strong>proven</strong> approach: the granular
 * {@link DamageModifier} model. Vanilla still populates {@code ARMOR},
 * {@code RESISTANCE}, {@code MAGIC} (enchant) and {@code ABSORPTION} modifiers
 * on the event on every supported version — confirmed by reading
 * {@code CraftEventFactory.handleLivingEntityDamageEvent} on the 1.21.11 paper
 * jar (it still puts all four modifier keys + functions). The deprecated
 * {@code setDamage(DamageModifier, double)} setter throws
 * {@code UnsupportedOperationException} <em>only when the modifier is absent</em>
 * from the event's map — verified by disassembling the setter on the
 * 1.17.1 / 1.20.6 / 1.21.11 / 26.1.2 Bukkit APIs (identical
 * {@code if (!modifiers.containsKey(m)) throw} guard on all four). We therefore
 * only ever write modifiers that {@link EntityDamageEvent#isApplicable(DamageModifier)}
 * reports present — exactly OCM's guard — so the setter never throws. This leaves
 * BASE / HARD_HAT / BLOCKING (the upstream stages) untouched and composes
 * cleanly with other plugins.</p>
 *
 * <h2>Priority</h2>
 * <p>{@link EventPriority#LOWEST} (matching OCM): we read the raw vanilla
 * modifier values and rewrite the defensive ones before any higher-priority
 * handler runs. {@code ignoreCancelled = true}.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>{@link EntityDamageEvent} fires on the <em>victim's</em> region thread, so
 * reading the victim's equipment / potion effects / absorption and writing the
 * modifiers back are all safe inline — no scheduling hop. The 1.8 model needs
 * <strong>no attacker state</strong> (no attacker enchants), so we never touch
 * the damager.</p>
 *
 * <h2>Zero-touch</h2>
 * <p>When disabled (the default), this module registers no listeners and leaves
 * vanilla's armour reduction completely untouched.</p>
 */
public final class ArmourStrengthModule extends CombatModule implements Listener {

    /** Tolerance for "era result equals what vanilla already computed" skip checks. */
    private static final double WRITE_EPSILON = 1.0e-6;

    /**
     * Damage causes that bypass armour entirely in 1.8 — the era NMS cause set.
     * Mirrors OCM's {@code ARMOUR_IGNORING_CAUSES}. Some entries are absent on
     * older API builds and added by name where present.
     */
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
            // From 1.9 — harmless to list on older builds (resolved by enum name).
            DamageCause.FLY_INTO_WALL,
            DamageCause.DRAGON_BREATH);

    static {
        // 1.14+ causes — added defensively so older API builds don't NoSuchField.
        addCauseIfPresent(ARMOUR_IGNORING_CAUSES, "CRAMMING");
        addCauseIfPresent(ARMOUR_IGNORING_CAUSES, "FREEZE");
    }

    /**
     * Reflected {@code PotionEffectType.getByKey(NamespacedKey)} (Paper 1.20.5+),
     * mirroring {@code GoldenAppleModule}. {@code null} on older builds → the
     * {@code getByName} fallback resolves Resistance via its legacy Bukkit name.
     */
    private static final @Nullable Method GET_BY_KEY;

    /** Cached Resistance effect type, resolved once at class load. */
    private static final @Nullable PotionEffectType RESISTANCE;

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 — getByName fallback.
        }
        GET_BY_KEY = m;
        RESISTANCE = resolveResistance();
    }

    public ArmourStrengthModule(@NotNull MentalServices services) {
        super(services,
                "old-armour-strength",
                "Old Armour Strength",
                "Restores 1.8 flat armour reduction (4% per point, no toughness), "
                        + "including resistance, armour-enchant EPF and absorption, in the era order.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().armourStrength().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Zero-touch: listeners are unregistered by CombatModule; vanilla armour
        // reduction resumes immediately.
    }

    /* ------------------------------------------------------------------ */
    /*  The override                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Recomputes the defensive damage modifiers (armour, resistance, enchant,
     * absorption) using the 1.8 model and writes them back to the event.
     *
     * <p>Only modifiers that {@link EntityDamageEvent#isApplicable(DamageModifier)}
     * reports present are read or written, so the deprecated granular setter never
     * throws {@code UnsupportedOperationException} (see the class doc).</p>
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        // The ARMOR modifier is the anchor: if vanilla didn't apply armour to this
        // event there is nothing to override (e.g. an armour-ignoring cause).
        if (!event.isApplicable(DamageModifier.ARMOR)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (ARMOUR_IGNORING_CAUSES.contains(cause)) {
            return;
        }

        // Damage entering the armour stage = vanilla's pre-armour total: BASE plus
        // the upstream HARD_HAT and BLOCKING reductions (negative values). We do
        // NOT touch those stages — only the defensive ones below.
        double preArmour = event.getDamage(DamageModifier.BASE)
                + applicable(event, DamageModifier.HARD_HAT)
                + applicable(event, DamageModifier.BLOCKING);
        if (preArmour <= 0.0) {
            return; // Nothing for armour to reduce.
        }

        int armourPoints = armourPoints(victim);
        // VOID & STARVATION bypass resistance in the era NMS — drop the level so
        // the resistance stage is a no-op (×1) for those causes.
        int resistanceLevel = (cause == DamageCause.VOID || cause == DamageCause.STARVATION)
                ? 0
                : resistanceLevel(victim);
        int epf = enchantEpf(victim, cause);
        double absorption = victim.getAbsorptionAmount();

        // Each defensive stage's modifier value is REWRITTEN off the era cascade
        // (not vanilla's toughness cascade). getFinalDamage() is the sum of all
        // modifier values, so writing each applicable defensive modifier to its
        // era reduction fully replaces vanilla's reduction; modifiers we leave
        // untouched (BASE/HARD_HAT/BLOCKING) keep their upstream values. We always
        // overwrite the applicable defensive modifiers — even to 0 — so no stale
        // vanilla value (computed off the larger toughness intermediate) can leak
        // into the sum. This mirrors OCM, which unconditionally re-puts all four.

        // --- Armour ---
        double afterArmour = DefenceMath.armourReduced(preArmour, armourPoints);
        setIfChanged(event, DamageModifier.ARMOR, afterArmour - preArmour);

        // --- Resistance ---
        double afterResistance = DefenceMath.resistanceReduced(afterArmour, resistanceLevel);
        if (event.isApplicable(DamageModifier.RESISTANCE)) {
            setIfChanged(event, DamageModifier.RESISTANCE, afterResistance - afterArmour);
        }

        // --- Enchant EPF --- (era skips the enchant stage once damage hits 0;
        // enchantReduced is monotone and clamps ≥ 0, so applying it to 0 is a
        // no-op — equivalent to the era short-circuit.)
        double afterEnchant = DefenceMath.enchantReduced(afterResistance, epf);
        if (event.isApplicable(DamageModifier.MAGIC)) {
            setIfChanged(event, DamageModifier.MAGIC, afterEnchant - afterResistance);
        }

        // --- Absorption --- (applied last; soaks up to its amount)
        double afterAbsorption = DefenceMath.absorptionReduced(afterEnchant, absorption);
        if (event.isApplicable(DamageModifier.ABSORPTION)) {
            // The ABSORPTION modifier is the negative amount soaked.
            setIfChanged(event, DamageModifier.ABSORPTION, afterAbsorption - afterEnchant);
        }

        debug.log(() -> "old-armour-strength " + cause + " on " + victim.getType()
                + ": pre=" + preArmour + " armour=" + armourPoints
                + " resist=" + resistanceLevel + " epf=" + epf
                + " abs=" + victim.getAbsorptionAmount()
                + " → final=" + sumModifiers(event));
    }

    /* ------------------------------------------------------------------ */
    /*  Victim state reads (all on the victim's region thread)             */
    /* ------------------------------------------------------------------ */

    /**
     * The victim's worn armour defence points, read from the ARMOR attribute
     * (era-equal vanilla values). Toughness is deliberately ignored.
     */
    private static int armourPoints(@NotNull LivingEntity victim) {
        double value = Attributes.valueOr(victim, Attributes.armor(), 0.0);
        return (int) Math.round(value);
    }

    /** The victim's resistance level (amplifier + 1), or 0 if not present. */
    private static int resistanceLevel(@NotNull LivingEntity victim) {
        if (RESISTANCE == null) {
            return 0;
        }
        PotionEffect effect = victim.getPotionEffect(RESISTANCE);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    /**
     * The clamped enchantment protection factor over all worn armour pieces for
     * this damage cause. Sums per-piece {@code floor((6+level²)/3 * typeModifier)}
     * across every protection enchant that protects against {@code cause}, then
     * clamps to 20 (the era's visible cap).
     */
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

    /**
     * Writes {@code value} to {@code modifier} only when it differs meaningfully
     * from the current value (avoids needless writes when the era result already
     * matches vanilla). The caller has already verified the modifier is applicable.
     */
    @SuppressWarnings("deprecation")
    private static void setIfChanged(
            @NotNull EntityDamageEvent event, @NotNull DamageModifier modifier, double value) {
        if (Math.abs(event.getDamage(modifier) - value) > WRITE_EPSILON) {
            event.setDamage(modifier, value);
        }
    }

    @SuppressWarnings("deprecation")
    private static double sumModifiers(@NotNull EntityDamageEvent event) {
        double sum = 0.0;
        for (DamageModifier modifier : DamageModifier.values()) {
            if (event.isApplicable(modifier)) {
                sum += event.getDamage(modifier);
            }
        }
        return sum;
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
        // Pre-1.9 Bukkit name; modern Paper still accepts it as an alias.
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

    /**
     * The five armour protection enchantments with their 1.8 type modifiers and
     * the damage causes they protect against (mirrors OCM's {@code EnchantmentType}).
     * Cross-version enchant constants come from {@link Enchantments}; causes that
     * may be absent on older API builds are resolved by name.
     */
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

        /** Protection covers the broad set of physical/environmental causes. */
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
