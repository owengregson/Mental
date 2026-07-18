package me.vexmc.mental.v5.feature;

import me.vexmc.mental.v5.config.settings.ChargedAttackSettings;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.NoSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;

/**
 * The single enumerable feature registry (spec §7). One constant per feature,
 * carrying its complete identity: the {@code yamlKey} (the exact {@code modules.*}
 * string that is the operator contract — copied from the retired
 * {@code MentalConfig.reload}; {@code null} for the always-on infrastructure
 * descriptor), the dashboard {@link Family}, display metadata (blurb + icon
 * copied from {@code gui/menu/Catalog}; the concise display name authored here,
 * as Catalog carries none), the era-default enablement, its {@link Facets}
 * declaration, and its typed {@link SettingsKey}.
 *
 * <p>Zero silent gaps by construction (B5): every non-infrastructure feature
 * declares all four facets and a settings key; a registry test enumerates the
 * constants and fails on any omission. The seven engine features default ON;
 * every ported rule defaults OFF (era-exact no-op), matching the retired
 * per-record defaults.</p>
 */
public enum Feature {

    /* ------------------------------- DELIVERY ------------------------------- */

    HIT_REGISTRATION("hit-registration", Family.DELIVERY, "Hit Registration",
            "Async hit registration — attacks validated on the netty thread, damage on the victim's region.",
            "IRON_SWORD", true,
            new Facets(
                    Facets.none("async delivery pipeline, not a Bukkit rule"),
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("shapes only the fast-path hits it registers")),
            new SettingsKey<>("hit-registration", HitRegSettings.class)),

    WTAP_REGISTRATION("wtap-registration", Family.DELIVERY, "W-Tap Registration",
            "Reads the attacker's sprint in packet-arrival order, so a w-tap registers however fast the tap.",
            "FEATHER", true,
            new Facets(
                    Facets.none("arrival-order sprint read feeding the knockback engine"),
                    Facets.none("emits no packets of its own"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("wtap-registration", NoSettings.class)),

    ANTICHEAT_COMPAT(null, Family.DELIVERY, "Anticheat Coexistence",
            "Always-on infrastructure — disables netty-thread pre-send while a prediction anticheat is present.",
            "IRON_BARS", true, null,
            new SettingsKey<>("anticheat-compat", NoSettings.class)),

    /* ------------------------------- KNOCKBACK ------------------------------ */

    KNOCKBACK("knockback", Family.KNOCKBACK, "Knockback",
            "Melee knockback through the velocity pipeline, shaped by the active profile.",
            "PISTON", true,
            new Facets(
                    Facets.none("velocity pipeline, not a rule"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("knockback", NoSettings.class)),

    LATENCY_COMPENSATION("latency-compensation", Family.KNOCKBACK, "Latency Compensation",
            "Ping-aware vertical-knockback correction.",
            "CLOCK", true,
            new Facets(
                    Facets.none("vertical correction rides the knockback pipeline"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("latency-compensation", CompensationSettings.class)),

    FISHING_KNOCKBACK("fishing-knockback", Family.KNOCKBACK, "Fishing Knockback",
            "1.7 rod combat — hooks are real zero-damage hits that knock.",
            "FISHING_ROD", true,
            new Facets(
                    Facets.handled(),
                    Facets.none("emits no packets of its own"),
                    Facets.none("hooks are zero-damage"),
                    Facets.none("hooks are zero-damage")),
            new SettingsKey<>("fishing-knockback", FishingKnockbackSettings.class)),

    ROD_VELOCITY("rod-velocity", Family.KNOCKBACK, "Rod Velocity",
            "1.7 rod cast feel — launch speed, spread, and flight gravity.",
            "TRIPWIRE_HOOK", true,
            new Facets(
                    Facets.handled(),
                    Facets.none("server-side cast velocity only"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("rod-velocity", NoSettings.class)),

    PROJECTILE_KNOCKBACK("projectile-knockback", Family.KNOCKBACK, "Projectile Knockback",
            "1.7 projectile knockback — snowballs, eggs, pearls, and arrows.",
            "SNOWBALL", true,
            new Facets(
                    Facets.handled(),
                    Facets.none("knockback ships through the velocity pipeline"),
                    Facets.none("no damage contribution"),
                    Facets.none("thrown damage is nulled, not shaped")),
            new SettingsKey<>("projectile-knockback", ProjectileKnockbackSettings.class)),

    CT8C_PROJECTILES("ct8c-projectiles", Family.KNOCKBACK, "CT8c Projectiles",
            "Combat Test 8c ranged rules — snowballs/eggs deal 0 damage but full 0.4 knock, a 4-tick throw gate, bow fatigue after 3s, aim-direction-only momentum.",
            "SNOWBALL", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("knockback ships through the velocity pipeline; the 2-tick render suppression is client-only"),
                    Facets.none("no fast-path damage contribution"),
                    Facets.none("thrown damage is nulled, not shaped")),
            new SettingsKey<>("ct8c-projectiles", NoSettings.class)),

    /* -------------------------------- DAMAGE -------------------------------- */

    ARMOUR_STRENGTH("old-armour-strength", Family.DAMAGE, "Old Armour Strength",
            "1.8 flat armour reduction — 4% per point, no toughness.",
            "IRON_CHESTPLATE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("recomputes on the EDBEE vanilla path"),
                    Facets.handled()),
            new SettingsKey<>("old-armour-strength", NoSettings.class)),

    ARMOUR_DURABILITY("old-armour-durability", Family.DAMAGE, "Old Armour Durability",
            "1.8 armour-durability Unbreaking skip.",
            "DIAMOND_CHESTPLATE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("durability wear, not a damage amount"),
                    Facets.none("durability wear, not a damage amount")),
            new SettingsKey<>("old-armour-durability", NoSettings.class)),

    CRIT_FALLBACK("old-critical-hits", Family.DAMAGE, "Old Critical Hits",
            "1.8 crit rule — x1.5 with no sprint/cooldown gate (fast-path-off hits).",
            "NETHER_STAR", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("the fast path already applies era crits"),
                    Facets.handled()),
            new SettingsKey<>("old-critical-hits", NoSettings.class)),

    TOOL_DURABILITY("old-tool-durability", Family.DAMAGE, "Old Tool Durability",
            "Restore weapon durability loss on fast-path melee hits.",
            "IRON_PICKAXE", false,
            new Facets(
                    Facets.none("applied inline on the fast-path hit"),
                    Facets.none("no client-visible change"),
                    Facets.none("durability wear, not a damage amount"),
                    Facets.none("durability wear, not a damage amount")),
            new SettingsKey<>("old-tool-durability", NoSettings.class)),

    SWORD_BLOCKING("sword-blocking", Family.DAMAGE, "Sword Blocking",
            "Restore 1.7/1.8 right-click sword blocking — a blocked hit takes (1 + dmg) x 0.5.",
            "STONE_SWORD", false,
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("blocking reduces on the vanilla path"),
                    Facets.handled()),
            new SettingsKey<>("sword-blocking", NoSettings.class)),

    CT8C_DAMAGE("ct8c-damage", Family.DAMAGE, "CT8c Damage",
            "Combat Test 8c weapon damage tables — player base 2.0 + weapon + tier (one lower than vanilla), hoe/trident flats.",
            "DIAMOND_AXE", false,
            new Facets(
                    Facets.none("a pure weapon-damage composition through the DamageShaper seam, not a Bukkit rule"),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.handled()),
            new SettingsKey<>("ct8c-damage", NoSettings.class)),

    CT8C_CRITS("ct8c-crits", Family.DAMAGE, "CT8c Critical Hits",
            "Combat Test 8c crit policy — flat x1.5, sprinting allowed, enchant damage folded into the base BEFORE the multiplier.",
            "NETHER_STAR", false,
            new Facets(
                    Facets.none("a crit multiplier composed into the damage amount, not a Bukkit rule"),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.handled()),
            new SettingsKey<>("ct8c-crits", NoSettings.class)),

    CT8C_IFRAMES("ct8c-iframes", Family.DAMAGE, "CT8c Invulnerability",
            "Combat Test 8c i-frames — min(attacker attack-delay, 10) ticks for melee, 0 for every projectile, through the spawn-invuln-safe seam.",
            "TOTEM_OF_UNDYING", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("gates subsequent hits, not a damage amount"),
                    Facets.none("gates subsequent hits, not a damage amount")),
            new SettingsKey<>("ct8c-iframes", NoSettings.class)),

    CT8C_SHIELDS("ct8c-shields", Family.DAMAGE, "CT8c Shields",
            "Combat Test 8c shields — 148 arc, a 5-damage cap with passthrough, instant + crouch blocking, 0.5 KB resist, axe disable.",
            "SHIELD", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("the crouch-raised shield pose is a client asset and cannot be reproduced server-side"),
                    Facets.none("no fast-path damage contribution"),
                    Facets.handled()),
            new SettingsKey<>("ct8c-shields", NoSettings.class)),

    CLEAVING("cleaving", Family.DAMAGE, "Cleaving",
            "The Combat Test 8c axe-only Cleaving enchant — +1+level damage (folded through CT8c Damage) and +10t/level shield disable.",
            "NETHERITE_AXE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.handled()),
            new SettingsKey<>("cleaving", NoSettings.class)),

    /* ------------------------------- CADENCE -------------------------------- */

    ATTACK_COOLDOWN("attack-cooldown", Family.CADENCE, "Attack Cooldown",
            "Remove the 1.9 attack cooldown — full-charge damage on every swing.",
            "NETHERITE_SWORD", false,
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("no damage-amount contribution"),
                    Facets.none("no damage-amount contribution")),
            new SettingsKey<>("attack-cooldown", NoSettings.class)),

    ATTACK_SOUNDS("disable-attack-sounds", Family.CADENCE, "Disable Attack Sounds",
            "Suppress the 1.9 swing-result sounds — 1.7/1.8 combat was silent.",
            "NOTE_BLOCK", false,
            new Facets(
                    Facets.none("no behavior change, cosmetic only"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-attack-sounds", NoSettings.class)),

    SWEEP("disable-sword-sweep", Family.CADENCE, "Disable Sword Sweep",
            "Disable the 1.9 sweep attack — swords hit a single target, no sweep particle.",
            "IRON_SWORD", false,
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("no fast-path contribution"),
                    Facets.handled()),
            new SettingsKey<>("disable-sword-sweep", NoSettings.class)),

    WEAPON_ATTACK_SPEEDS("weapon-attack-speeds", Family.CADENCE, "CT8c Weapon Speeds",
            "Combat Test 8c melee cadence — recompute the held item's ATTACK_SPEED attribute from the CT8c attacks-per-second table.",
            "DIAMOND_SWORD", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("the attribute is server-authoritative and client-synced; no packet spoof"),
                    Facets.none("attack cadence, not a damage amount"),
                    Facets.none("attack cadence, not a damage amount")),
            new SettingsKey<>("weapon-attack-speeds", WeaponSpeedSettings.class)),

    CHARGED_ATTACKS("charged-attacks", Family.CADENCE, "CT8c Charged Attacks",
            "Combat Test 8c charge gate — reject a hit below full charge (bar the 4-tick miss lane), grant reach above 195% charge.",
            "CLOCK", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("the charge meter is client-authoritative and cannot be rendered server-side"),
                    Facets.none("gates whether the hit lands, not its amount"),
                    Facets.none("gates whether the hit lands, not its amount")),
            new SettingsKey<>("charged-attacks", ChargedAttackSettings.class)),

    CT8C_SWEEP("ct8c-sweep", Family.CADENCE, "CT8c Sweep",
            "Combat Test 8c sweep rules — Sweeping Edge required AND >=195% charge (plain swords no longer sweep); axes accept the enchant.",
            "IRON_SWORD", false,
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("sweep secondary damage rides the vanilla sweep path"),
                    Facets.handled()),
            new SettingsKey<>("ct8c-sweep", NoSettings.class)),

    /* ------------------------------- SUSTAIN -------------------------------- */

    GOLDEN_APPLES("old-golden-apples", Family.SUSTAIN, "Old Golden Apples",
            "1.8.9 golden-apple effects and the notch-apple recipe.",
            "ENCHANTED_GOLDEN_APPLE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-golden-apples", NoSettings.class)),

    ENDER_PEARL_COOLDOWN("disable-enderpearl-cooldown", Family.SUSTAIN, "Disable Ender-Pearl Cooldown",
            "Remove the 1.9 ender-pearl throw cooldown.",
            "ENDER_PEARL", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-enderpearl-cooldown", NoSettings.class)),

    REGEN("old-player-regen", Family.SUSTAIN, "Old Player Regen",
            "1.8 natural regen — heal 1 HP every 4s at food level 18+.",
            "GOLDEN_APPLE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-player-regen", NoSettings.class)),

    POTION_DURATIONS("old-potion-durations", Family.SUSTAIN, "Old Potion Durations",
            "Restore the pre-1.9 potion durations.",
            "POTION", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-potion-durations", NoSettings.class)),

    POTION_VALUES("old-potion-values", Family.SUSTAIN, "Old Potion Values",
            "Restore the pre-1.9 Strength/Weakness damage values.",
            "SPLASH_POTION", false,
            new Facets(
                    Facets.none("applied to fast-path melee only"),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.none("mob/vanilla hits keep vanilla values (scope trade-off)")),
            new SettingsKey<>("old-potion-values", NoSettings.class)),

    CT8C_REGEN("ct8c-regen", Family.SUSTAIN, "CT8c Regen",
            "Combat Test 8c food/regen — heal 1 HP every 40t while food > 6 (50% drain), sprint gated on food > 6, eat-interrupt on a hit.",
            "GOLDEN_APPLE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("ct8c-regen", NoSettings.class)),

    CT8C_CONSUMABLES("ct8c-consumables", Family.SUSTAIN, "CT8c Consumables",
            "Combat Test 8c consumables — 20-tick drinks, potions stacking to 16 and snowballs to 64 (1.20.5+ item components, loud degrade below).",
            "MUSHROOM_STEW", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("the stack size / drink duration ride normal item-component sync, not a spoof"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("ct8c-consumables", NoSettings.class)),

    CT8C_POTIONS("ct8c-potions", Family.SUSTAIN, "CT8c Potions",
            "Combat Test 8c potion values — Instant Health/Damage 6*2^amp, Strength/Weakness +/-20% per level, tipped-arrow effects x1/8.",
            "SPLASH_POTION", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.none("mob/vanilla-path hits keep vanilla potion damage (scope trade-off, mirrors old-potion-values)")),
            new SettingsKey<>("ct8c-potions", NoSettings.class)),

    /* ------------------------------- LOADOUT -------------------------------- */

    CRAFTING("disable-crafting", Family.LOADOUT, "Disable Crafting",
            "Make the configured items uncraftable (shield by default).",
            "CRAFTING_TABLE", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-crafting", CraftingSettings.class)),

    OFFHAND("disable-offhand", Family.LOADOUT, "Disable Off-Hand",
            "Block items from the off-hand slot — the slot did not exist pre-1.9.",
            "SHIELD", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-offhand", OffhandSettings.class)),

    HITBOX("old-hitboxes", Family.LOADOUT, "Old Hitboxes",
            "1.7/1.8 melee reach (3.0) and hitbox margin, where the server allows.",
            "TARGET", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-hitboxes", NoSettings.class)),

    CT8C_REACH("ct8c-reach", Family.LOADOUT, "CT8c Reach",
            "Combat Test 8c melee reach — base 2.5 / sword 3.0 / hoe+trident 3.5 (+ the charged bonus), plus the 0.9 hitbox inflation and"
                    + " through-non-solid targeting, all inside Mental's own reach validation. 1.20.5+ for 3.5; loud degrade below.",
            "TARGET", false,
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("ct8c-reach", NoSettings.class)),

    /* -------------------------------- COMBO --------------------------------- */

    COMBO_HOLD("combo-hold", Family.COMBO, "Solve Horizontal KB",
            "Solve the fresh horizontal melee knock to land the victim in the un-retaliatable pocket, holding a sweet-spot combo.",
            "ITEM_FRAME", false,
            new Facets(
                    Facets.none("scales the fresh melee knock through the velocity pipeline, not a Bukkit rule"),
                    Facets.none("emits no packets of its own — the shaped knock rides the existing pipeline"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("combo-hold", ComboSettings.class)),

    COMBO_REACH_HANDICAP("combo-reach-handicap", Family.COMBO, "Scale Reach",
            "Shorten a juggled victim's reach while their combo is held, so a launched victim can't"
                    + " answer. Pairs with Solve Horizontal KB under Combo Solver; 1.20.5+.",
            "REPEATER", false,
            new Facets(
                    // The attribute lever IS a server-side rule (applied on combo transitions).
                    Facets.handled(),
                    // The entity-interaction-range attribute is client-synced from 1.20.5 — the
                    // client's own raycast shortens, which is precisely the mechanic (no phantoms).
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("combo-reach-handicap", ReachHandicapSettings.class)),

    /* --------------------------------- POTS --------------------------------- */

    POT_FILL("pot-fill", Family.POTS, "Pot Fill",
            "The /potfill command — fill empty inventory slots with splash Instant Health II.",
            "SPLASH_POTION", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change — inventory is filled server-side"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("pot-fill", PotFillSettings.class)),

    FAST_POTS("fast-pots", Family.POTS, "Fast Pots",
            "Redirect a steeply-thrown splash potion at the thrower's own feet at a multiplied speed.",
            "SPLASH_POTION", false,
            new Facets(
                    Facets.handled(),
                    Facets.none("velocity is re-aimed server-side; the client sees a normal throw"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("fast-pots", FastPotsSettings.class)),

    /* ------------------------------- FEEDBACK ------------------------------- */

    HIT_FEEDBACK("hit-feedback", Family.FEEDBACK, "Hit Effects",
            "Replace the vanilla hit sound with your own layered sounds and particles.",
            "NOTE_BLOCK", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("hit-feedback", HitFeedbackSettings.class)),

    DAMAGE_INDICATORS("damage-indicators", Family.FEEDBACK, "Damage Indicators",
            "Pop a damage number off the victim on the attacker's screen.",
            "ARMOR_STAND", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("damage-indicators", DamageIndicatorsSettings.class)),

    DEATH_EFFECTS("death-effects", Family.FEEDBACK, "Death Effects",
            "A cosmetic strike on player death — lightning, sound, and a burst.",
            "FIREWORK_ROCKET", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("death-effects", DeathEffectsSettings.class)),

    /* --------------------------------- LOOT --------------------------------- */

    DROP_PROTECTION("drop-protection", Family.LOOT, "Drop Protection",
            "Lock a slain player's drops to their killer for a few seconds — gold-glowing to the killer only.",
            "CHEST", false,
            new Facets(
                    // A pure Bukkit rule: capture the drops on death, gate pickup on the event.
                    Facets.handled(),
                    // The gold glow is a per-connection cosmetic sent to the killer alone.
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("drop-protection", DropProtectionSettings.class));

    private final String yamlKey;
    private final Family family;
    private final String displayName;
    private final String blurb;
    private final String iconName;
    private final boolean defaultEnabled;
    private final Facets facets;
    private final SettingsKey<?> settingsKey;

    Feature(
            String yamlKey,
            Family family,
            String displayName,
            String blurb,
            String iconName,
            boolean defaultEnabled,
            Facets facets,
            SettingsKey<?> settingsKey) {
        this.yamlKey = yamlKey;
        this.family = family;
        this.displayName = displayName;
        this.blurb = blurb;
        this.iconName = iconName;
        this.defaultEnabled = defaultEnabled;
        this.facets = facets;
        this.settingsKey = settingsKey;
    }

    /**
     * The feature whose {@code modules.*} toggle string equals {@code moduleId},
     * or empty for an unknown id or an always-on infrastructure descriptor (which
     * carries no yaml toggle). The public API's {@code moduleEnabled(String)} and
     * the runtime toggle seam resolve module ids through here.
     */
    public static java.util.Optional<Feature> byModuleId(String moduleId) {
        if (moduleId == null) {
            return java.util.Optional.empty();
        }
        for (Feature feature : values()) {
            if (moduleId.equals(feature.yamlKey)) {
                return java.util.Optional.of(feature);
            }
        }
        return java.util.Optional.empty();
    }

    /** The {@code modules.*} config string, or {@code null} for always-on infrastructure. */
    public String yamlKey() {
        return yamlKey;
    }

    /** Always-on infrastructure descriptors carry no yaml toggle and no facets. */
    public boolean infrastructure() {
        return yamlKey == null;
    }

    public Family family() {
        return family;
    }

    public String displayName() {
        return displayName;
    }

    public String blurb() {
        return blurb;
    }

    public String iconName() {
        return iconName;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** The four-surface declaration, or {@code null} for infrastructure. */
    public Facets facets() {
        return facets;
    }

    public SettingsKey<?> settingsKey() {
        return settingsKey;
    }
}
