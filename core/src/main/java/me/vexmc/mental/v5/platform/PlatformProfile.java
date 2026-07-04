package me.vexmc.mental.v5.platform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Capabilities;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.platform.Recipes;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single boot-time resolution owner (spec §9, R10/B10). Built once at enable
 * from a manifest of typed {@link ManifestEntry}s, it folds every version-gated
 * probe Mental performs — attribute and enchantment handles, protocol
 * capabilities, and the item-component adapters — into one immutable value read
 * on the hot path as plain field access. It supersedes the ad-hoc
 * {@code PlatformProbe}: the three adapters are now manifest consumers, and the
 * bare-{@code @Nullable} name-probes on {@code Attributes}/{@code Enchantments}
 * are the resolution TECHNIQUE the entries call, never a call-site surface.
 *
 * <p>A {@link Required} entry that misses disables its owning {@link Feature}
 * (one loud log) or — engine-critical — fails the boot; an {@link OptionalSince}
 * entry that misses below its version is a quiet, typed fallback. One boot-report
 * line summarises the whole profile.</p>
 */
public final class PlatformProfile {

    /** The version the {@code max_damage} component ({@code Damageable} accessors) first appears. */
    private static final int MAX_DAMAGE_MAJOR = 1;
    private static final int MAX_DAMAGE_MINOR = 20;
    private static final int MAX_DAMAGE_PATCH = 5;

    private final List<ManifestEntry> entries;
    private final Set<Feature> disabledFeatures;

    private final SwordBlockAdapter swordBlock;
    private final WeaponTooltipAdapter weaponTooltip;
    private final AttackRangeAdapter attackRange;

    private final @Nullable Method hasMaxDamage;
    private final @Nullable Method getMaxDamage;

    private final boolean modernHurtProtocol;
    private final boolean projectileKnockbackRestored;

    private PlatformProfile(
            @NotNull List<ManifestEntry> entries, @NotNull Set<Feature> disabledFeatures,
            @NotNull SwordBlockAdapter swordBlock, @NotNull WeaponTooltipAdapter weaponTooltip,
            @NotNull AttackRangeAdapter attackRange, @Nullable Method hasMaxDamage, @Nullable Method getMaxDamage,
            boolean modernHurtProtocol, boolean projectileKnockbackRestored) {
        this.entries = List.copyOf(entries);
        this.disabledFeatures = Set.copyOf(disabledFeatures);
        this.swordBlock = swordBlock;
        this.weaponTooltip = weaponTooltip;
        this.attackRange = attackRange;
        this.hasMaxDamage = hasMaxDamage;
        this.getMaxDamage = getMaxDamage;
        this.modernHurtProtocol = modernHurtProtocol;
        this.projectileKnockbackRestored = projectileKnockbackRestored;
    }

    /**
     * Resolves the whole manifest once against the running server. Loud-logs a
     * mapping break for any Required miss (and any adapter/component that should
     * be present but is not), throws on an engine-critical miss, and returns an
     * immutable profile the plugin reads for the rest of its life.
     */
    public static @NotNull PlatformProfile resolve(
            @NotNull ServerEnvironment env, @NotNull Capabilities caps, @NotNull Consumer<String> log) {
        List<ManifestEntry> entries = new ArrayList<>();

        // --- Attribute handles (present on every supported server ⇒ Required) ---
        entries.add(Required.owned("attribute:attack_damage", Feature.HIT_REGISTRATION, Attributes::attackDamage));
        entries.add(Required.owned("attribute:attack_speed", Feature.ATTACK_COOLDOWN, Attributes::attackSpeed));
        entries.add(Required.owned(
                "attribute:knockback_resistance", Feature.KNOCKBACK, Attributes::knockbackResistance));
        entries.add(Required.owned("attribute:max_health", Feature.REGEN, Attributes::maxHealth));
        entries.add(Required.owned("attribute:armor", Feature.ARMOUR_STRENGTH, Attributes::armor));
        // Toughness is OptionalSince, NOT Required: the era 1.8 flat model has no toughness term
        // (kernel DefenceMath ignores it) and ArmourStrengthUnit never reads the attribute — it
        // reduces off ARMOR points alone. GENERIC_ARMOR_TOUGHNESS is absent below 1.11.2
        // (javap-verified on the 1.9.4/1.10.2 server jars), so a Required entry wrongly disabled
        // ARMOUR_STRENGTH on those two versions. As an OptionalSince(1.11.2) its absence there is a
        // typed, quiet outcome and the feature composes the SAME era armour values (from the kernel
        // pins, not this handle) on 1.9.4/1.10.2.
        entries.add(OptionalSince.resolve("attribute:armor_toughness", "1.11.2", null,
                "the era 1.8 flat model ignores toughness (no kernel toughness term)",
                Attributes::armorToughness));
        // --- Attribute handles gated at 1.20.5 ⇒ OptionalSince (caller uses a vanilla numeric below) ---
        entries.add(OptionalSince.resolve("attribute:gravity", "1.20.5", null,
                "vanilla gravity " + Decay.DEFAULT_GRAVITY, Attributes::gravity));
        entries.add(OptionalSince.resolve("attribute:entity_interaction_range", "1.20.5", null,
                "the classic 3.0 attack reach", Attributes::entityInteractionRange));

        // --- Enchantment handles (renamed but resolvable on every supported server ⇒ Required) ---
        entries.add(Required.owned("enchant:sharpness", Feature.HIT_REGISTRATION, Enchantments::sharpness));
        entries.add(Required.owned("enchant:punch", Feature.PROJECTILE_KNOCKBACK, Enchantments::punch));
        entries.add(Required.owned("enchant:knockback", Feature.KNOCKBACK, Enchantments::knockback));
        entries.add(Required.owned("enchant:protection", Feature.ARMOUR_STRENGTH, Enchantments::protection));
        entries.add(Required.owned("enchant:fire_protection", Feature.ARMOUR_STRENGTH, Enchantments::fireProtection));
        entries.add(Required.owned(
                "enchant:feather_falling", Feature.ARMOUR_STRENGTH, Enchantments::featherFalling));
        entries.add(Required.owned(
                "enchant:blast_protection", Feature.ARMOUR_STRENGTH, Enchantments::blastProtection));
        entries.add(Required.owned(
                "enchant:projectile_protection", Feature.ARMOUR_STRENGTH, Enchantments::projectileProtection));
        entries.add(Required.owned("enchant:unbreaking", Feature.TOOL_DURABILITY, Enchantments::unbreaking));

        // --- Protocol capabilities ⇒ OptionalSince (typed boolean, declared fallback) ---
        OptionalSince<Boolean> hurtProtocol = OptionalSince.resolve(
                "capability:hurt_animation_bundle", "1.19.4", Boolean.FALSE,
                "the entity-status-2 damage tick", () -> env.isAtLeast(1, 19, 4) ? Boolean.TRUE : null);
        entries.add(hurtProtocol);
        entries.add(OptionalSince.resolve("capability:knockback_event", "1.20.6", Boolean.FALSE,
                "no mid-pass knockback mirror", () -> caps.knockbackEvent() ? Boolean.TRUE : null));
        OptionalSince<Boolean> projectileRestored = OptionalSince.resolve(
                "flag:projectile_kb_restored", "1.21.2", Boolean.FALSE,
                "Mental substitutes projectile knockback", () -> env.isAtLeast(1, 21, 2) ? Boolean.TRUE : null);
        entries.add(projectileRestored);
        entries.add(OptionalSince.resolve("marker:join_protection_layout", "1.21.2", Boolean.FALSE,
                "the pre-1.21.2 join-invulnerability layout", () -> env.isAtLeast(1, 21, 2) ? Boolean.TRUE : null));
        // NamespacedKey (and with it the keyed ShapedRecipe ctor) lands at 1.12; below it the
        // golden-apples recipe rides the pre-keyed ctor + recipeIterator (platform Recipes resolver).
        entries.add(OptionalSince.resolve("capability:recipe_key", "1.12.0", Boolean.FALSE,
                "pre-keyed recipe ctor, lifecycle via recipeIterator",
                () -> Recipes.keyedRecipeCtor() ? Boolean.TRUE : null));

        // --- Item-component adapters (probed once) ⇒ OptionalSince, the components as manifest consumers ---
        SwordBlockAdapter swordBlock = SwordBlockAdapter.probe(env, log);
        WeaponTooltipAdapter weaponTooltip = WeaponTooltipAdapter.probe(env, log);
        AttackRangeAdapter attackRange = AttackRangeAdapter.probe(env, log);
        Method hasMaxDamage = probeDamageable("hasMaxDamage");
        Method getMaxDamage = probeDamageable("getMaxDamage");
        if (env.isAtLeast(MAX_DAMAGE_MAJOR, MAX_DAMAGE_MINOR, MAX_DAMAGE_PATCH)
                && (hasMaxDamage == null || getMaxDamage == null)) {
            log.accept("platform-probe: the max_damage component accessors (Damageable#hasMaxDamage/"
                    + "getMaxDamage) did not resolve on " + env.describe()
                    + " — a mapping break; tool durability falls back to the material max.");
        }
        final Method maxDamageHas = hasMaxDamage;
        final Method maxDamageGet = getMaxDamage;
        entries.add(OptionalSince.resolve("component:max_damage", "1.20.5", Boolean.FALSE,
                "the material max durability",
                () -> maxDamageHas != null && maxDamageGet != null ? Boolean.TRUE : null));
        entries.add(OptionalSince.resolve("component:sword_block", "1.21.0", SwordBlockAdapter.Tier.NONE,
                "the off-hand shield decoration", () -> swordBlock.supported() ? swordBlock.tier() : null));
        entries.add(OptionalSince.resolve("component:weapon_tooltip", "1.20.5", Boolean.FALSE,
                "the attack-speed tooltip line stays visible",
                () -> weaponTooltip.supported() ? Boolean.TRUE : null));
        entries.add(OptionalSince.resolve("component:attack_range", "1.21.5", Boolean.FALSE,
                "the interaction-range attribute alone", () -> attackRange.supported() ? Boolean.TRUE : null));

        Set<Feature> disabled = resolveDisabled(entries, log);
        return new PlatformProfile(entries, disabled, swordBlock, weaponTooltip, attackRange,
                hasMaxDamage, getMaxDamage, hurtProtocol.orFallback(), projectileRestored.orFallback());
    }

    /**
     * Scans a resolved manifest for Required misses: an owned miss disables its
     * owner (one loud log), an engine-critical miss throws (boot fail). Pure over
     * the entry list — the unit test drives it directly with a synthetic manifest.
     */
    static @NotNull Set<Feature> resolveDisabled(
            @NotNull List<ManifestEntry> entries, @NotNull Consumer<String> log) {
        Set<Feature> disabled = new LinkedHashSet<>();
        for (ManifestEntry entry : entries) {
            if (!(entry instanceof Required<?> required) || required.present()) {
                continue;
            }
            if (required.engineCritical()) {
                throw new IllegalStateException(
                        "platform: engine-critical handle " + required.name()
                                + " did not resolve on this server — cannot boot.");
            }
            Feature owner = required.owner();
            log.accept("platform: required handle " + required.name() + " did not resolve — disabling "
                    + owner + " on this version (a mapping break).");
            disabled.add(owner);
        }
        return disabled;
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors — presence-typed, never a bare @Nullable                 */
    /* ------------------------------------------------------------------ */

    /** The block-component adapter (sword-block tier detection + apply/strip/state). */
    public @NotNull SwordBlockAdapter swordBlock() {
        return swordBlock;
    }

    /** The weapon-tooltip adapter (attack-cooldown attack-speed line strip). */
    public @NotNull WeaponTooltipAdapter weaponTooltip() {
        return weaponTooltip;
    }

    /** The era {@code ATTACK_RANGE} item-component adapter (the loadout hitbox weapon lever, 1.21.5+). */
    public @NotNull AttackRangeAdapter attackRange() {
        return attackRange;
    }

    /** True on 1.19.4+ where HURT_ANIMATION + bundle delimiters carry the pre-send. */
    public boolean modernHurtProtocol() {
        return modernHurtProtocol;
    }

    /** True on 1.21.2+ where vanilla restored projectile knockback (the substitution path is a no-op). */
    public boolean projectileKnockbackRestored() {
        return projectileKnockbackRestored;
    }

    /** Features a Required mapping break disabled on this server — empty on every supported version. */
    public @NotNull Set<Feature> disabledFeatures() {
        return disabledFeatures;
    }

    /** The whole resolved manifest, for the boot report and invariant tests. */
    public @NotNull List<ManifestEntry> entries() {
        return entries;
    }

    /**
     * The item's effective maximum durability: the custom {@code max_damage}
     * component when the meta carries one (1.20.5+), else the material max. A
     * reflective slip after a successful boot probe degrades to the material max.
     */
    public int effectiveMaxDurability(@NotNull ItemStack weapon, @NotNull Damageable meta) {
        int materialMax = weapon.getType().getMaxDurability();
        if (hasMaxDamage == null || getMaxDamage == null) {
            return materialMax;
        }
        try {
            if (Boolean.TRUE.equals(hasMaxDamage.invoke(meta))) {
                int custom = ((Number) getMaxDamage.invoke(meta)).intValue();
                return custom > 0 ? custom : materialMax;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Probed present at boot; fall back to the material max on any slip.
        }
        return materialMax;
    }

    /** The single boot-report line summarising the resolved profile. */
    public @NotNull String bootReport() {
        long present = entries.stream().filter(ManifestEntry::present).count();
        return "platform profile — " + present + "/" + entries.size() + " handles resolved; "
                + "sword-block=" + swordBlock.tier()
                + " attack-range=" + (attackRange.supported() ? "component" : "attribute-only")
                + " max-damage=" + (hasMaxDamage != null ? "component" : "material")
                + " hurt-protocol=" + (modernHurtProtocol ? "modern" : "legacy")
                + "; features disabled: " + (disabledFeatures.isEmpty() ? "none" : disabledFeatures);
    }

    /**
     * Reflect a no-arg {@code Damageable} meta method (1.20.5+); {@code null} on an older platform.
     *
     * <p>Resolved by NAME rather than the {@code Damageable.class} literal: the {@code Damageable} meta
     * interface itself is absent below 1.13, where the class literal is a {@code NoClassDefFoundError} at
     * this always-on boot probe (the legacy backport reaches to 1.9.4). Class-not-found, method-not-found,
     * and any other linkage slip all degrade to {@code null} — tool durability then uses the material max.</p>
     */
    private static @Nullable Method probeDamageable(@NotNull String name) {
        try {
            Class<?> damageable = Class.forName("org.bukkit.inventory.meta.Damageable");
            return damageable.getMethod(name);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return null;
        }
    }
}
