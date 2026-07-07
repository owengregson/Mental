package me.vexmc.mental.v5.feature;

import java.util.Set;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.NoSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;

/**
 * The single enumerable feature registry (spec §7). One constant per feature,
 * carrying its complete identity: the {@code yamlKey} (the exact {@code modules.*}
 * string that is the operator contract — copied from the retired
 * {@code MentalConfig.reload}; {@code null} for the two always-on infrastructure
 * descriptors), the dashboard {@link Family}, display metadata (blurb + icon
 * copied from {@code gui/menu/Catalog}; the concise display name authored here,
 * as Catalog carries none), the era-default enablement, the {@link MechanicToken}s
 * it restores, its {@link Facets} declaration, and its typed {@link SettingsKey}.
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
            "IRON_SWORD", true, Set.of(MechanicToken.TOOL_DAMAGE),
            new Facets(
                    Facets.none("async delivery pipeline, not a Bukkit rule"),
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("shapes only the fast-path hits it registers")),
            new SettingsKey<>("hit-registration", HitRegSettings.class)),

    WTAP_REGISTRATION("wtap-registration", Family.DELIVERY, "W-Tap Registration",
            "Reads the attacker's sprint in packet-arrival order, so a w-tap registers however fast the tap.",
            "FEATHER", true, Set.of(),
            new Facets(
                    Facets.none("arrival-order sprint read feeding the knockback engine"),
                    Facets.none("emits no packets of its own"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("wtap-registration", NoSettings.class)),

    ANTICHEAT_COMPAT(null, Family.DELIVERY, "Anticheat Coexistence",
            "Always-on infrastructure — disables netty-thread pre-send while a prediction anticheat is present.",
            "IRON_BARS", true, Set.of(), null,
            new SettingsKey<>("anticheat-compat", NoSettings.class)),

    OCM_COMPAT(null, Family.DELIVERY, "OldCombatMechanics Coexistence",
            "Always-on infrastructure — binds the arbiter and derives the coexistence startup warnings.",
            "DIAMOND_SWORD", true, Set.of(), null,
            new SettingsKey<>("ocm-compat", NoSettings.class)),

    /* ------------------------------- KNOCKBACK ------------------------------ */

    KNOCKBACK("knockback", Family.KNOCKBACK, "Knockback",
            "Melee knockback through the velocity pipeline, shaped by the active profile.",
            "PISTON", true, Set.of(MechanicToken.MELEE_KNOCKBACK, MechanicToken.ARROW_KNOCKBACK),
            new Facets(
                    Facets.none("velocity pipeline, not a rule"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("knockback", NoSettings.class)),

    LATENCY_COMPENSATION("latency-compensation", Family.KNOCKBACK, "Latency Compensation",
            "Ping-aware vertical-knockback correction.",
            "CLOCK", true, Set.of(),
            new Facets(
                    Facets.none("vertical correction rides the knockback pipeline"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("latency-compensation", CompensationSettings.class)),

    FISHING_KNOCKBACK("fishing-knockback", Family.KNOCKBACK, "Fishing Knockback",
            "1.7 rod combat — hooks are real zero-damage hits that knock.",
            "FISHING_ROD", true, Set.of(MechanicToken.FISHING_KNOCKBACK),
            new Facets(
                    Facets.handled(),
                    Facets.none("emits no packets of its own"),
                    Facets.none("hooks are zero-damage"),
                    Facets.none("hooks are zero-damage")),
            new SettingsKey<>("fishing-knockback", FishingKnockbackSettings.class)),

    ROD_VELOCITY("rod-velocity", Family.KNOCKBACK, "Rod Velocity",
            "1.7 rod cast feel — launch speed, spread, and flight gravity.",
            "TRIPWIRE_HOOK", true, Set.of(MechanicToken.FISHING_ROD_VELOCITY),
            new Facets(
                    Facets.handled(),
                    Facets.none("server-side cast velocity only"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("rod-velocity", NoSettings.class)),

    PROJECTILE_KNOCKBACK("projectile-knockback", Family.KNOCKBACK, "Projectile Knockback",
            "1.7 projectile knockback — snowballs, eggs, pearls, and arrows.",
            "SNOWBALL", true, Set.of(MechanicToken.PROJECTILE_KNOCKBACK),
            new Facets(
                    Facets.handled(),
                    Facets.none("knockback ships through the velocity pipeline"),
                    Facets.none("no damage contribution"),
                    Facets.none("thrown damage is nulled, not shaped")),
            new SettingsKey<>("projectile-knockback", ProjectileKnockbackSettings.class)),

    /* -------------------------------- DAMAGE -------------------------------- */

    ARMOUR_STRENGTH("old-armour-strength", Family.DAMAGE, "Old Armour Strength",
            "1.8 flat armour reduction — 4% per point, no toughness.",
            "IRON_CHESTPLATE", false, Set.of(MechanicToken.ARMOUR_STRENGTH),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("recomputes on the EDBEE vanilla path"),
                    Facets.handled()),
            new SettingsKey<>("old-armour-strength", NoSettings.class)),

    ARMOUR_DURABILITY("old-armour-durability", Family.DAMAGE, "Old Armour Durability",
            "1.8 armour-durability Unbreaking skip.",
            "DIAMOND_CHESTPLATE", false, Set.of(MechanicToken.ARMOUR_DURABILITY),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("durability wear, not a damage amount"),
                    Facets.none("durability wear, not a damage amount")),
            new SettingsKey<>("old-armour-durability", NoSettings.class)),

    CRIT_FALLBACK("old-critical-hits", Family.DAMAGE, "Old Critical Hits",
            "1.8 crit rule — x1.5 with no sprint/cooldown gate (fast-path-off hits).",
            "NETHER_STAR", false, Set.of(MechanicToken.CRITICAL_HITS),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("the fast path already applies era crits"),
                    Facets.handled()),
            new SettingsKey<>("old-critical-hits", NoSettings.class)),

    TOOL_DURABILITY("old-tool-durability", Family.DAMAGE, "Old Tool Durability",
            "Restore weapon durability loss on fast-path melee hits.",
            "IRON_PICKAXE", false, Set.of(MechanicToken.TOOL_DURABILITY),
            new Facets(
                    Facets.none("applied inline on the fast-path hit"),
                    Facets.none("no client-visible change"),
                    Facets.none("durability wear, not a damage amount"),
                    Facets.none("durability wear, not a damage amount")),
            new SettingsKey<>("old-tool-durability", NoSettings.class)),

    SWORD_BLOCKING("sword-blocking", Family.DAMAGE, "Sword Blocking",
            "Restore 1.7/1.8 right-click sword blocking — a blocked hit takes (1 + dmg) x 0.5.",
            "STONE_SWORD", false, Set.of(MechanicToken.SWORD_BLOCKING),
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("blocking reduces on the vanilla path"),
                    Facets.handled()),
            new SettingsKey<>("sword-blocking", NoSettings.class)),

    /* ------------------------------- CADENCE -------------------------------- */

    ATTACK_COOLDOWN("attack-cooldown", Family.CADENCE, "Attack Cooldown",
            "Remove the 1.9 attack cooldown — full-charge damage on every swing.",
            "NETHERITE_SWORD", false, Set.of(MechanicToken.ATTACK_COOLDOWN),
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("no damage-amount contribution"),
                    Facets.none("no damage-amount contribution")),
            new SettingsKey<>("attack-cooldown", NoSettings.class)),

    ATTACK_SOUNDS("disable-attack-sounds", Family.CADENCE, "Disable Attack Sounds",
            "Suppress the 1.9 swing-result sounds — 1.7/1.8 combat was silent.",
            "NOTE_BLOCK", false, Set.of(MechanicToken.ATTACK_SOUNDS),
            new Facets(
                    Facets.none("no behavior change, cosmetic only"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-attack-sounds", NoSettings.class)),

    SWEEP("disable-sword-sweep", Family.CADENCE, "Disable Sword Sweep",
            "Disable the 1.9 sweep attack — swords hit a single target, no sweep particle.",
            "IRON_SWORD", false, Set.of(MechanicToken.SWEEP),
            new Facets(
                    Facets.handled(),
                    Facets.handled(),
                    Facets.none("no fast-path contribution"),
                    Facets.handled()),
            new SettingsKey<>("disable-sword-sweep", NoSettings.class)),

    /* ------------------------------- SUSTAIN -------------------------------- */

    GOLDEN_APPLES("old-golden-apples", Family.SUSTAIN, "Old Golden Apples",
            "1.8.9 golden-apple effects and the notch-apple recipe.",
            "ENCHANTED_GOLDEN_APPLE", false, Set.of(MechanicToken.GOLDEN_APPLES),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-golden-apples", NoSettings.class)),

    ENDER_PEARL_COOLDOWN("disable-enderpearl-cooldown", Family.SUSTAIN, "Disable Ender-Pearl Cooldown",
            "Remove the 1.9 ender-pearl throw cooldown.",
            "ENDER_PEARL", false, Set.of(MechanicToken.ENDER_PEARL_COOLDOWN),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-enderpearl-cooldown", NoSettings.class)),

    REGEN("old-player-regen", Family.SUSTAIN, "Old Player Regen",
            "1.8 natural regen — heal 1 HP every 4s at food level 18+.",
            "GOLDEN_APPLE", false, Set.of(MechanicToken.REGEN),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-player-regen", NoSettings.class)),

    POTION_DURATIONS("old-potion-durations", Family.SUSTAIN, "Old Potion Durations",
            "Restore the pre-1.9 potion durations.",
            "POTION", false, Set.of(MechanicToken.POTION_DURATIONS),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-potion-durations", NoSettings.class)),

    POTION_VALUES("old-potion-values", Family.SUSTAIN, "Old Potion Values",
            "Restore the pre-1.9 Strength/Weakness damage values.",
            "SPLASH_POTION", false, Set.of(MechanicToken.POTION_VALUES),
            new Facets(
                    Facets.none("applied to fast-path melee only"),
                    Facets.none("no client-visible change"),
                    Facets.handled(),
                    Facets.none("mob/vanilla hits keep vanilla values (scope trade-off)")),
            new SettingsKey<>("old-potion-values", NoSettings.class)),

    /* ------------------------------- LOADOUT -------------------------------- */

    CRAFTING("disable-crafting", Family.LOADOUT, "Disable Crafting",
            "Make the configured items uncraftable (shield by default).",
            "CRAFTING_TABLE", false, Set.of(MechanicToken.CRAFTING),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-crafting", CraftingSettings.class)),

    OFFHAND("disable-offhand", Family.LOADOUT, "Disable Off-Hand",
            "Block items from the off-hand slot — the slot did not exist pre-1.9.",
            "SHIELD", false, Set.of(MechanicToken.OFFHAND),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("disable-offhand", OffhandSettings.class)),

    HITBOX("old-hitboxes", Family.LOADOUT, "Old Hitboxes",
            "1.7/1.8 melee reach (3.0) and hitbox margin, where the server allows.",
            "TARGET", false, Set.of(MechanicToken.HITBOX),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("old-hitboxes", NoSettings.class)),

    /* -------------------------------- COMBO --------------------------------- */

    COMBO_HOLD("combo-hold", Family.COMBO, "Solve Horizontal KB",
            "Solve the fresh horizontal melee knock to land the victim in the un-retaliatable pocket, holding a sweet-spot combo.",
            "ITEM_FRAME", false, Set.of(),
            new Facets(
                    Facets.none("scales the fresh melee knock through the velocity pipeline, not a Bukkit rule"),
                    Facets.none("emits no packets of its own — the shaped knock rides the existing pipeline"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("combo-hold", ComboSettings.class)),

    COMBO_REACH_HANDICAP("combo-reach-handicap", Family.COMBO, "Scale Reach",
            "Shorten a juggled victim's reach while their combo is held, so a launched victim can't"
                    + " answer. Pairs with Solve Horizontal KB under Combo Solver; 1.20.5+.",
            "REPEATER", false, Set.of(),
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
            "SPLASH_POTION", false, Set.of(),
            new Facets(
                    Facets.handled(),
                    Facets.none("no client-visible change — inventory is filled server-side"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("pot-fill", PotFillSettings.class)),

    FAST_POTS("fast-pots", Family.POTS, "Fast Pots",
            "Redirect a steeply-thrown splash potion at the thrower's own feet at a multiplied speed.",
            "SPLASH_POTION", false, Set.of(),
            new Facets(
                    Facets.handled(),
                    Facets.none("velocity is re-aimed server-side; the client sees a normal throw"),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("fast-pots", FastPotsSettings.class));

    private final String yamlKey;
    private final Family family;
    private final String displayName;
    private final String blurb;
    private final String iconName;
    private final boolean defaultEnabled;
    private final Set<MechanicToken> tokens;
    private final Facets facets;
    private final SettingsKey<?> settingsKey;

    Feature(
            String yamlKey,
            Family family,
            String displayName,
            String blurb,
            String iconName,
            boolean defaultEnabled,
            Set<MechanicToken> tokens,
            Facets facets,
            SettingsKey<?> settingsKey) {
        this.yamlKey = yamlKey;
        this.family = family;
        this.displayName = displayName;
        this.blurb = blurb;
        this.iconName = iconName;
        this.defaultEnabled = defaultEnabled;
        this.tokens = Set.copyOf(tokens);
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

    public Set<MechanicToken> tokens() {
        return tokens;
    }

    /** The four-surface declaration, or {@code null} for infrastructure. */
    public Facets facets() {
        return facets;
    }

    public SettingsKey<?> settingsKey() {
        return settingsKey;
    }
}
