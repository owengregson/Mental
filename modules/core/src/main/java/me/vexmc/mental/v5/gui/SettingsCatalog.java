package me.vexmc.mental.v5.gui;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.ChargedAttackSettings;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;
import me.vexmc.mental.v5.feature.Feature;
import org.jetbrains.annotations.NotNull;

/**
 * The knob registry: which of a feature's settings are edited in-GUI, with what
 * widget, bounds, and copy. The same philosophy as {@code DashboardModel} — one
 * enumerable catalog the render path reads directly, pinned by
 * {@code SettingsCatalogTest} — applied to the settings layer. List-valued knobs
 * are deliberately POINTER tiles (the GUI never edits YAML lists; the overlay is
 * scalar-only by design), and knobs whose parse contract is drop-to-fallback
 * carry GUI clamps INSIDE the legal range so a GUI write can never produce a
 * reload warning.
 */
public final class SettingsCatalog {
    // RED-TEAM FIX: the class and configuredFeatures() are public — the tester
    // (a separate plugin) imports SettingsCatalog and iterates configuredFeatures()
    // in BootSuite. Knob/Page/pageFor stay package-private (only SettingsMenu and
    // same-package tests read them).

    enum Kind { TOGGLE, STEP_INT, STEP_DOUBLE, CYCLE, TEXT, NUMBER, POINTER, INFO }

    /**
     * One knob descriptor. {@code reader} returns the CURRENT effective value from
     * the snapshot (Boolean for TOGGLE, Number for the STEP and NUMBER kinds,
     * String for CYCLE/TEXT); null for POINTER/INFO. {@code options} carries the exact strings
     * a CYCLE writes (case matters — mirrored from the bundled YAML). {@code file}
     * and {@code section} direct POINTER tiles.
     */
    record Knob(Kind kind, String key, String label, String materialName, String blurb,
                double min, double max, double step, String unit,
                List<String> options, Function<Snapshot, Object> reader,
                String file, String section) {

        static Knob toggle(String key, String label, String material, String blurb,
                           Function<Snapshot, Object> reader) {
            return new Knob(Kind.TOGGLE, key, label, material, blurb,
                    0, 0, 0, "", List.of(), reader, null, null);
        }

        static Knob stepInt(String key, String label, String material, String blurb,
                            int min, int max, int step, String unit,
                            Function<Snapshot, Object> reader) {
            return new Knob(Kind.STEP_INT, key, label, material, blurb,
                    min, max, step, unit, List.of(), reader, null, null);
        }

        static Knob stepDouble(String key, String label, String material, String blurb,
                               double min, double max, double step, String unit,
                               Function<Snapshot, Object> reader) {
            return new Knob(Kind.STEP_DOUBLE, key, label, material, blurb,
                    min, max, step, unit, List.of(), reader, null, null);
        }

        static Knob cycle(String key, String label, String material, String blurb,
                          List<String> options, Function<Snapshot, Object> reader) {
            return new Knob(Kind.CYCLE, key, label, material, blurb,
                    0, 0, 0, "", options, reader, null, null);
        }

        static Knob text(String key, String label, String material, String blurb,
                         Function<Snapshot, Object> reader) {
            return new Knob(Kind.TEXT, key, label, material, blurb,
                    0, 0, 0, "", List.of(), reader, null, null);
        }

        static Knob number(String key, String label, String material, String blurb,
                           double min, double max, String unit,
                           Function<Snapshot, Object> reader) {
            return new Knob(Kind.NUMBER, key, label, material, blurb,
                    min, max, 0, unit, List.of(), reader, null, null);
        }

        static Knob pointer(String label, String material, String blurb,
                            String file, String section) {
            return new Knob(Kind.POINTER, null, label, material, blurb,
                    0, 0, 0, "", List.of(), null, file, section);
        }

        static Knob info(String label, String material, String blurb) {
            return new Knob(Kind.INFO, null, label, material, blurb,
                    0, 0, 0, "", List.of(), null, null, null);
        }
    }

    /** One screen: the owning feature and its knob rows (subsections). */
    record Page(Feature feature, List<List<Knob>> groups) {}

    private static final Map<Feature, Page> PAGES = buildPages();

    private SettingsCatalog() {}

    /** The page for a feature, or empty when it is toggle-only. */
    static @NotNull Optional<Page> pageFor(@NotNull Feature feature) {
        return Optional.ofNullable(PAGES.get(feature));
    }

    /** Every feature that owns a page — the tester's iteration seam (PUBLIC). */
    public static @NotNull List<Feature> configuredFeatures() {
        return List.copyOf(PAGES.keySet());
    }

    private static Map<Feature, Page> buildPages() {
        Map<Feature, Page> pages = new EnumMap<>(Feature.class);

        pages.put(Feature.HIT_REGISTRATION, new Page(Feature.HIT_REGISTRATION, List.of(
                List.of(
                        Knob.stepInt("hit-registration.max-cps", "Max CPS", "REPEATER",
                                "Attack packets per second before the pipeline starts ignoring the excess.",
                                5, 40, 1, "CPS",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).maxCps()),
                        Knob.toggle("hit-registration.fast-path.enabled", "Fast Path", "FEATHER",
                                "Register hits on the netty thread — zero server-tick latency on feedback.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).fastPath()),
                        Knob.toggle("hit-registration.fast-path.pre-send-feedback", "Pre-Send Feedback", "SUGAR",
                                "Ship the victim's hurt feedback before the tick even begins.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).preSendFeedback()),
                        Knob.toggle("hit-registration.fast-path.bundle-feedback", "Bundle Feedback", "STRING",
                                "On 1.19.4+, land velocity and hurt animation in the same client frame.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).bundleFeedback())),
                List.of(
                        Knob.toggle("hit-registration.fast-path.simulate-crits", "Simulate Crits", "NETHER_STAR",
                                "Apply the era critical-hit rule on fast-path hits.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).simulateCrits()),
                        Knob.toggle("hit-registration.fast-path.legacy-tool-damage", "Legacy Tool Damage", "IRON_PICKAXE",
                                "Use the 1.8 tool damage table on fast-path hits.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class).legacyToolDamage()),
                        Knob.info("Feedback Interval", "CLOCK",
                                "The feedback-min-interval-ms knob is 'auto' — derived live from the"
                                        + " victim's hurt window. Tune it in hit-registration.yml.")),
                List.of(
                        Knob.toggle("hit-registration.reach-validation.enabled", "Reach Validation", "IRON_BARS",
                                "Mental's own ping-rewound reach check — for servers running no anticheat.",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class)
                                        .reachValidation().enabled()),
                        Knob.stepDouble("hit-registration.reach-validation.max-reach", "Max Reach", "TARGET",
                                "The farthest a rewound attack may reach before it is discarded.",
                                2.5, 6.0, 0.1, "blocks",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class)
                                        .reachValidation().maxReach()),
                        Knob.stepDouble("hit-registration.reach-validation.leniency", "Leniency", "SLIME_BALL",
                                "Forgiveness added on top of max reach for hitbox edges and jitter.",
                                0.0, 2.0, 0.05, "blocks",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class)
                                        .reachValidation().leniency()),
                        Knob.stepInt("hit-registration.reach-validation.interpolation-offset-ms",
                                "Interpolation Offset", "CLOCK",
                                "How far behind real time the victim's rewound position is sampled.",
                                0, 500, 10, "ms",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class)
                                        .reachValidation().interpolationOffsetMillis()),
                        Knob.stepInt("hit-registration.reach-validation.rewind-cap-ms", "Rewind Cap", "ANVIL",
                                "The most latency the rewind will honour — pings past this stop gaining reach.",
                                0, 1000, 50, "ms",
                                s -> settings(s, Feature.HIT_REGISTRATION, HitRegSettings.class)
                                        .reachValidation().rewindCapMillis())))));

        pages.put(Feature.LATENCY_COMPENSATION, new Page(Feature.LATENCY_COMPENSATION, List.of(
                List.of(
                        Knob.cycle("latency-compensation.probe-strategy", "Probe Strategy", "COMPASS",
                                "How Mental measures each player's live ping mid-fight. Below 1.17 PING"
                                        + " rides transactions automatically.",
                                List.of("PING", "TRANSACTION", "KEEPALIVE"),
                                s -> settings(s, Feature.LATENCY_COMPENSATION, CompensationSettings.class)
                                        .probeStrategy().name()),
                        Knob.stepInt("latency-compensation.spike-threshold-ms", "Spike Threshold", "REDSTONE",
                                "A ping jump larger than this is a spike and is filtered, not believed.",
                                5, 200, 5, "ms",
                                s -> settings(s, Feature.LATENCY_COMPENSATION, CompensationSettings.class)
                                        .spikeThresholdMillis()),
                        Knob.stepInt("latency-compensation.probe-interval-ticks", "Probe Interval", "CLOCK",
                                "How often the latency probe fires while a player is in combat.",
                                1, 100, 1, "ticks",
                                s -> settings(s, Feature.LATENCY_COMPENSATION, CompensationSettings.class)
                                        .probeIntervalTicks()),
                        Knob.stepInt("latency-compensation.combat-timeout-ticks", "Combat Timeout", "ANVIL",
                                "How long after the last hit a player still counts as in combat.",
                                5, 1200, 5, "ticks",
                                s -> settings(s, Feature.LATENCY_COMPENSATION, CompensationSettings.class)
                                        .combatTimeoutTicks()),
                        Knob.toggle("latency-compensation.off-ground-sync", "Off-Ground Sync", "FEATHER",
                                "Correct vertical knockback against the victim's true airborne state.",
                                s -> settings(s, Feature.LATENCY_COMPENSATION, CompensationSettings.class)
                                        .offGroundSync())))));

        pages.put(Feature.FISHING_KNOCKBACK, new Page(Feature.FISHING_KNOCKBACK, List.of(
                List.of(
                        Knob.number("fishing-knockback.damage", "Hook Damage", "FISHING_ROD",
                                "The damage a landed hook deals. Near-zero keeps 1.7 rods: a real hit"
                                        + " that knocks but barely hurts.",
                                0.0, 20.0, "HP",
                                s -> settings(s, Feature.FISHING_KNOCKBACK, FishingKnockbackSettings.class).damage()),
                        Knob.cycle("fishing-knockback.reel-in", "Reel-In Policy", "TRIPWIRE_HOOK",
                                "What happens on reel-in while hooked to a player — the era yank, or a clean cancel.",
                                List.of("legacy", "cancel"),
                                s -> settings(s, Feature.FISHING_KNOCKBACK, FishingKnockbackSettings.class)
                                        .reelIn().name().toLowerCase(Locale.ROOT)),
                        Knob.toggle("fishing-knockback.knockback-non-player-entities", "Knock Mobs", "EGG",
                                "Let hooks shove mobs too, not just players.",
                                s -> settings(s, Feature.FISHING_KNOCKBACK, FishingKnockbackSettings.class)
                                        .knockbackNonPlayerEntities())))));

        pages.put(Feature.PROJECTILE_KNOCKBACK, new Page(Feature.PROJECTILE_KNOCKBACK, List.of(
                List.of(
                        Knob.toggle("projectile-knockback.arrows", "Arrow Knockback", "BOW",
                                "1.7 arrow shove — away from the shooter, not the impact.",
                                s -> settings(s, Feature.PROJECTILE_KNOCKBACK, ProjectileKnockbackSettings.class)
                                        .arrows()),
                        Knob.number("projectile-knockback.damage.snowball", "Snowball Damage", "SNOWBALL",
                                "Snowball hit damage. Near-zero keeps the classic no-hurt trade.",
                                0.0, 20.0, "HP",
                                s -> settings(s, Feature.PROJECTILE_KNOCKBACK, ProjectileKnockbackSettings.class)
                                        .snowballDamage()),
                        Knob.number("projectile-knockback.damage.egg", "Egg Damage", "EGG",
                                "Egg hit damage. Near-zero keeps the classic no-hurt trade.",
                                0.0, 20.0, "HP",
                                s -> settings(s, Feature.PROJECTILE_KNOCKBACK, ProjectileKnockbackSettings.class)
                                        .eggDamage()),
                        Knob.number("projectile-knockback.damage.ender-pearl", "Pearl Damage", "ENDER_PEARL",
                                "Ender-pearl impact damage dealt to the player it strikes.",
                                0.0, 20.0, "HP",
                                s -> settings(s, Feature.PROJECTILE_KNOCKBACK, ProjectileKnockbackSettings.class)
                                        .enderPearlDamage())))));

        pages.put(Feature.COMBO_HOLD, new Page(Feature.COMBO_HOLD, List.of(
                List.of(
                        Knob.stepInt("combo-hold.min-hits", "Min Hits", "IRON_SWORD",
                                "Hits from one attacker before the servo engages — two confirms intent.",
                                1, 10, 1, "hits",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).minHits()),
                        Knob.stepInt("combo-hold.max-gap-ticks", "Max Gap", "CLOCK",
                                "An inter-hit gap longer than this ends the chain. Era cadence is 10–12.",
                                1, 100, 1, "ticks",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).maxGapTicks()),
                        Knob.stepInt("combo-hold.grounded-run-ticks", "Grounded Run", "SLIME_BALL",
                                "Consecutive grounded ticks that end the combo — skims survive, touchdowns don't.",
                                1, 100, 1, "ticks",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).groundedRunTicks()),
                        Knob.stepDouble("combo-hold.blowout-blocks", "Blowout Distance", "TARGET",
                                "Separation past this ends the combo — the pocket is gone.",
                                1.0, 20.0, 0.5, "blocks",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).blowoutBlocks())),
                List.of(
                        Knob.stepDouble("combo-hold.gain", "Servo Gain", "REDSTONE",
                                "Blend toward the exact solve — 1.0 is exact, lower softens the touch.",
                                0.0, 1.0, 0.05, "×",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).gain()),
                        Knob.stepDouble("combo-hold.min-factor", "Min Factor", "HOPPER",
                                "The honesty floor on the knock multiplier — below it, era physics wins.",
                                0.5, 1.0, 0.01, "×",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).minFactor()),
                        Knob.stepDouble("combo-hold.max-factor", "Max Factor", "PISTON",
                                "The honesty ceiling on the knock multiplier — past it, era physics wins.",
                                1.0, 2.0, 0.01, "×",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).maxFactor()),
                        Knob.stepInt("combo-hold.window-ticks", "Solve Window", "REPEATER",
                                "The flight window the inverse solve lands the victim inside.",
                                1, 40, 1, "ticks",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).windowTicks()),
                        Knob.cycle("combo-hold.target-mode", "Target Mode", "COMPASS",
                                "boundary lands them where their answer is denied by a hair; static"
                                        + " holds a fixed range.",
                                List.of("boundary", "static"),
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class)
                                        .targetMode().name().toLowerCase(Locale.ROOT))),
                List.of(
                        Knob.stepDouble("combo-hold.target", "Static Target", "ITEM_FRAME",
                                "The held separation when target mode is static (the lab's 2.85 equilibrium).",
                                2.0, 3.5, 0.05, "blocks",
                                s -> settings(s, Feature.COMBO_HOLD, ComboSettings.class).staticTarget()),
                        Knob.pointer("Reach Geometry", "BOOK",
                                "victim-reach, attacker-reach, deny-margin, jitter-margin and target-floor"
                                        + " are precision geometry — tuned in the file.",
                                "combo.yml", "combo-hold")))));

        pages.put(Feature.COMBO_REACH_HANDICAP, new Page(Feature.COMBO_REACH_HANDICAP, List.of(
                List.of(
                        Knob.stepDouble("combo-reach-handicap.reach-scale", "Reach Scale", "REPEATER",
                                "The multiplier on a juggled victim's reach — 0.87 takes the era 3.0 to 2.61.",
                                0.5, 1.0, 0.01, "×",
                                s -> settings(s, Feature.COMBO_REACH_HANDICAP, ReachHandicapSettings.class).scale()),
                        Knob.info("Version Note", "IRON_BARS",
                                "1.20.5+ only: the interaction-range attribute is client-synced from there."
                                        + " Below, this module is a documented no-op.")))));

        pages.put(Feature.POT_FILL, new Page(Feature.POT_FILL, List.of(
                List.of(
                        Knob.text("pot-fill.permission", "Permission Node", "NAME_TAG",
                                "The node a player must hold to run /potfill. Blank is refused — the default stands.",
                                s -> settings(s, Feature.POT_FILL, PotFillSettings.class).permission()),
                        Knob.number("pot-fill.cost-per-potion", "Cost per Potion", "GOLD_INGOT",
                                "The Vault charge per filled potion. Zero is free and needs no economy plugin.",
                                0.0, 10000.0, "$",
                                s -> settings(s, Feature.POT_FILL, PotFillSettings.class).costPerPotion())))));

        pages.put(Feature.FAST_POTS, new Page(Feature.FAST_POTS, List.of(
                List.of(
                        Knob.stepDouble("fast-pots.angle-degrees", "Trigger Angle", "ARROW",
                                "How steeply below the horizon a throw must aim before the redirect kicks in.",
                                0.0, 90.0, 5, "°",
                                s -> settings(s, Feature.FAST_POTS, FastPotsSettings.class).angleDegrees()),
                        Knob.stepDouble("fast-pots.min-speed-multiplier", "Speed Floor", "HOPPER",
                                "The redirected launch never ships slower than this share of vanilla speed.",
                                0.05, 1.0, 0.05, "×",
                                s -> settings(s, Feature.FAST_POTS, FastPotsSettings.class).minSpeedMultiplier()),
                        Knob.stepDouble("fast-pots.max-speed-multiplier", "Speed Ceiling", "PISTON",
                                "The redirected launch never ships faster than this multiple of vanilla speed.",
                                1.0, 5.0, 0.1, "×",
                                s -> settings(s, Feature.FAST_POTS, FastPotsSettings.class).maxSpeedMultiplier()),
                        Knob.stepDouble("fast-pots.lead-ticks", "Forward Lead", "FEATHER",
                                "Aim the burst slightly ahead of your feet so you run INTO the cloud.",
                                0.0, 5.0, 0.5, "ticks",
                                s -> settings(s, Feature.FAST_POTS, FastPotsSettings.class).leadTicks())))));

        pages.put(Feature.OFFHAND, new Page(Feature.OFFHAND, List.of(
                List.of(
                        Knob.toggle("disable-offhand.whitelist", "Whitelist Mode", "SHIELD",
                                "ON: only the listed items are allowed off-hand. OFF: the listed items are blocked.",
                                s -> settings(s, Feature.OFFHAND, OffhandSettings.class).whitelist()),
                        Knob.text("disable-offhand.denied-message", "Denied Message", "PAPER",
                                "The line shown when an off-hand action is denied. Empty suppresses it.",
                                s -> settings(s, Feature.OFFHAND, OffhandSettings.class).deniedMessage()),
                        Knob.pointer("Item List", "CHEST",
                                "The whitelist/blocklist itself is a material list.",
                                "loadout.yml", "disable-offhand.items")))));

        pages.put(Feature.CRAFTING, new Page(Feature.CRAFTING, List.of(
                List.of(
                        Knob.pointer("Blocked Items", "CRAFTING_TABLE",
                                "The materials that become uncraftable — SHIELD by default, the one 1.9"
                                        + " item an era server blocks.",
                                "loadout.yml", "disable-crafting.items")))));

        pages.put(Feature.HIT_FEEDBACK, new Page(Feature.HIT_FEEDBACK, List.of(
                List.of(
                        Knob.stepDouble("effects.hit.low-health-threshold-percent", "Low-Health Threshold", "REDSTONE",
                                "Below this share of max health, the extra low-health sound layer fires.",
                                0, 100, 1, "%",
                                s -> settings(s, Feature.HIT_FEEDBACK, HitFeedbackSettings.class)
                                        .lowHealthThresholdPercent()),
                        Knob.pointer("Sounds & Particles", "JUKEBOX",
                                "The layered hit chord, particles and low-health chirp live in the preset tune.",
                                "effects/presets/<selected>.yml", "hit-feedback")))));

        pages.put(Feature.DAMAGE_INDICATORS, new Page(Feature.DAMAGE_INDICATORS, List.of(
                List.of(
                        Knob.text("effects.indicators.text", "Damage Text", "PAPER",
                                "The pop-off template. {HEALTH} is the damage dealt; & colour codes render.",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class).text()),
                        Knob.text("effects.indicators.crit-text", "Crit Text", "BLAZE_POWDER",
                                "The critical variant — fires on an era crit or a heavy hit.",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class).critText()),
                        Knob.text("effects.indicators.heal-text", "Heal Text", "GLISTERING_MELON_SLICE",
                                "Shown to the last attacker when the target heals. Empty disables heal indicators.",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class).healText())),
                List.of(
                        Knob.stepInt("effects.indicators.lifetime-ticks", "Lifetime", "CLOCK",
                                "How long a marker may live before it despawns mid-air.",
                                1, 200, 5, "ticks",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class)
                                        .lifetimeTicks()),
                        Knob.stepDouble("effects.indicators.crit-threshold-percent", "Crit Threshold", "REDSTONE",
                                "Damage at or above this share of max health also counts as a crit.",
                                0, 100, 1, "%",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class)
                                        .critThresholdPercent()),
                        Knob.stepInt("effects.indicators.roll-hold-ticks", "Roll Hold", "HOPPER",
                                "Hold a fresh hit's marker so mid-window upgrades fold into one number.",
                                0, 10, 1, "ticks",
                                s -> settings(s, Feature.DAMAGE_INDICATORS, DamageIndicatorsSettings.class)
                                        .rollHoldTicks()),
                        Knob.pointer("Ballistics", "ARMOR_STAND",
                                "The arc itself — ring, launch, gravity, drag — is a tuned art. Shape it"
                                        + " in the preset.",
                                "effects/presets/<selected>.yml", "damage-indicators")))));

        pages.put(Feature.DEATH_EFFECTS, new Page(Feature.DEATH_EFFECTS, List.of(
                List.of(
                        Knob.text("effects.death.kill-title", "Kill Title", "NAME_TAG",
                                "Flashed to the killer. {NAME}, {KILLER} and {PROTECT_SECONDS} substitute;"
                                        + " & codes render.",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class)
                                        .killTitle().title()),
                        Knob.text("effects.death.kill-subtitle", "Kill Subtitle", "PAPER",
                                "The smaller line under the kill title. Empty with an empty title sends nothing.",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class)
                                        .killTitle().subtitle()),
                        Knob.stepInt("effects.death.title-fade-in", "Fade In", "GLOWSTONE_DUST",
                                "Client ticks the title takes to appear.",
                                0, 200, 5, "ticks",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class)
                                        .killTitle().fadeIn()),
                        Knob.stepInt("effects.death.title-stay", "Stay", "CLOCK",
                                "Client ticks the title holds on screen.",
                                0, 400, 5, "ticks",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class)
                                        .killTitle().stay()),
                        Knob.stepInt("effects.death.title-fade-out", "Fade Out", "REDSTONE",
                                "Client ticks the title takes to leave.",
                                0, 200, 5, "ticks",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class)
                                        .killTitle().fadeOut())),
                List.of(
                        Knob.toggle("effects.death.lightning", "Cosmetic Lightning", "BLAZE_POWDER",
                                "A packet-only bolt at the death spot — all flash, no fire, no damage.",
                                s -> settings(s, Feature.DEATH_EFFECTS, DeathEffectsSettings.class).lightning()),
                        Knob.pointer("Sounds & Firework", "FIREWORK_ROCKET",
                                "The death chord, particles and the coloured blast live in the preset tune.",
                                "effects/presets/<selected>.yml", "death-effects")))));

        pages.put(Feature.DROP_PROTECTION, new Page(Feature.DROP_PROTECTION, List.of(
                List.of(
                        Knob.stepInt("drop-protection.seconds", "Protection Window", "CLOCK",
                                "How long a slain player's drops stay locked to the killer.",
                                1, 3600, 5, "s",
                                s -> settings(s, Feature.DROP_PROTECTION, DropProtectionSettings.class).seconds()),
                        Knob.cycle("drop-protection.glow-color", "Glow Colour", "GOLD_INGOT",
                                "The outline colour the killer alone sees on the protected drops.",
                                List.of("GOLD", "YELLOW"),
                                s -> settings(s, Feature.DROP_PROTECTION, DropProtectionSettings.class)
                                        .glowColor().name()),
                        Knob.info("Pickup Rule", "PLAYER_HEAD",
                                "Only the killer may pick the drops up while the window runs; afterwards"
                                        + " they are free-for-all.")))));

        // Combat Test 8c — the two rule features that carry tunables (the other
        // eleven CT8c modules are era-fixed toggles). CHARGED_ATTACKS exposes its
        // scalar charge gate; WEAPON_ATTACK_SPEEDS's twelve-value per-class att/s
        // table is a POINTER (the overlay is scalar-only, and the ladder is file
        // work), mirroring how OFFHAND / CRAFTING / combo geometry surface lists.
        pages.put(Feature.CHARGED_ATTACKS, new Page(Feature.CHARGED_ATTACKS, List.of(
                List.of(
                        Knob.toggle("charged-attacks.require-full-charge", "Require Full Charge", "CLOCK",
                                "Reject a hit landed below 100% charge — a landed hit needs a full recharge.",
                                s -> settings(s, Feature.CHARGED_ATTACKS, ChargedAttackSettings.class)
                                        .requireFullCharge()),
                        Knob.stepInt("charged-attacks.miss-recovery-ticks", "Miss Recovery", "REPEATER",
                                "After an air swing, a re-attack is allowed once more than this many ticks pass.",
                                0, 20, 1, "ticks",
                                s -> settings(s, Feature.CHARGED_ATTACKS, ChargedAttackSettings.class)
                                        .missRecoveryTicks()),
                        Knob.stepDouble("charged-attacks.charged-threshold", "Charge Threshold", "REDSTONE",
                                "The charge scale (0–2) at or above which a hit counts as charged — 1.95 = 195%.",
                                1.0, 2.0, 0.05, "×",
                                s -> settings(s, Feature.CHARGED_ATTACKS, ChargedAttackSettings.class)
                                        .chargedThreshold()),
                        Knob.stepDouble("charged-attacks.charged-reach-bonus", "Reach Bonus", "TARGET",
                                "Extra reach, in blocks, granted to a charged hit.",
                                0.0, 2.0, 0.1, "blocks",
                                s -> settings(s, Feature.CHARGED_ATTACKS, ChargedAttackSettings.class)
                                        .chargedReachBonus()),
                        Knob.toggle("charged-attacks.deny-bonus-while-crouching", "Deny Bonus Crouched",
                                "LEATHER_BOOTS",
                                "Deny the charged reach bonus while the attacker is crouching (8c behavior).",
                                s -> settings(s, Feature.CHARGED_ATTACKS, ChargedAttackSettings.class)
                                        .denyBonusWhileCrouching())))));

        pages.put(Feature.WEAPON_ATTACK_SPEEDS, new Page(Feature.WEAPON_ATTACK_SPEEDS, List.of(
                List.of(
                        Knob.pointer("Attack Speeds", "DIAMOND_SWORD",
                                "The per-class attacks-per-second table — fist, sword, axe, pickaxe, shovel,"
                                        + " trident, and the tier-sensitive hoe ladder — is precision tuning,"
                                        + " edited in the file.",
                                "config.yml", "weapon-attack-speeds")))));

        return pages;
    }

    /**
     * The feature's parsed settings, resolved by the descriptor's OWN identity
     * {@link me.vexmc.mental.v5.feature.SettingsKey} (the snapshot map is
     * identity-keyed — never construct a fresh key).
     */
    private static <S> S settings(Snapshot snapshot, Feature feature, Class<S> type) {
        return type.cast(snapshot.settings(feature.settingsKey()));
    }
}
