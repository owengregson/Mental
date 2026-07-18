package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.platform.PaneColor;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.ProbeStrategy;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import me.vexmc.mental.v5.feature.sustain.GoldenApplesUnit;
import me.vexmc.mental.v5.gui.ChatPrompt;
import me.vexmc.mental.v5.gui.CombatPresetsMenu;
import me.vexmc.mental.v5.gui.CompatibilityMenu;
import me.vexmc.mental.v5.gui.DashboardMenu;
import me.vexmc.mental.v5.gui.DebugMenu;
import me.vexmc.mental.v5.gui.FamilyMenu;
import me.vexmc.mental.v5.gui.MeleeFormula;
import me.vexmc.mental.v5.gui.Menu;
import me.vexmc.mental.v5.gui.MenuContext;
import me.vexmc.mental.v5.gui.PresetGalleryMenu;
import me.vexmc.mental.v5.gui.SettingsCatalog;
import me.vexmc.mental.v5.gui.SettingsMenu;
import me.vexmc.mental.v5.platform.ManifestEntry;
import me.vexmc.mental.v5.platform.SwordBlockAdapter;
import me.vexmc.mental.v5.platform.WeaponTooltipAdapter;
import me.vexmc.mental.v5.preset.PresetKind;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;

/** The plugin came up correctly on this exact server version. */
public final class BootSuite {

    /** The delivery + knockback families live at the 4A2 swap; each must converge active. */
    private static final List<Feature> EXPECTED_FEATURES = List.of(
            Feature.ANTICHEAT_COMPAT, Feature.HIT_REGISTRATION, Feature.KNOCKBACK,
            Feature.LATENCY_COMPENSATION, Feature.FISHING_KNOCKBACK, Feature.ROD_VELOCITY,
            Feature.PROJECTILE_KNOCKBACK);

    private BootSuite() {}

    public static @NotNull List<TestCase> tests(@NotNull MentalPluginV5 mental) {
        return List.of(
                new TestCase("boot: every delivery/knockback feature converged active", context -> {
                    for (Feature feature : EXPECTED_FEATURES) {
                        context.expect(mental.featureActive(feature),
                                "feature '" + feature + "' is not active");
                    }
                }),
                new TestCase("boot: capability report matches the booted version", context -> {
                    var capabilities = mental.capabilities();
                    var environment = mental.environment();
                    context.expect(environment.recognized(),
                            "server version was not recognized: " + environment.raw());
                    context.expect(capabilities.brigadierCommands() == environment.isAtLeast(1, 20, 6),
                            "brigadier capability (" + capabilities.brigadierCommands()
                                    + ") disagrees with version " + environment.describe());
                    if (capabilities.folia()) {
                        context.expect(capabilities.modernSchedulers(),
                                "folia without modern schedulers is impossible");
                        context.expect("folia".equals(mental.scheduling().describe()),
                                "folia must select the folia scheduling backend");
                    } else {
                        context.expect("bukkit".equals(mental.scheduling().describe()),
                                "non-folia servers must select the bukkit scheduling backend");
                    }
                }),
                new TestCase("boot: loaded bytecode tier matches the expected tier for this server", context -> {
                    // Q1/H5: the Multi-Release tree that actually loaded is a per-version live fact, not
                    // an assumption. The plugin self-inspects its own class bytes (the SAME value the boot
                    // report prints); the tester asserts it against the expected tier — the entry-declared
                    // -Dmental.tester.tier when Phase 2 wires it per-entry, else the JVM-derived default
                    // (Java 17+ ⇒ original versions/17 v61; Java 8–16 ⇒ base v52 — no versions/16 tier exists).
                    // A modern loader that surprised us by serving the downgraded base fails HERE, loudly.
                    int actual = mental.loadedBytecodeMajor();
                    int expected = expectedBytecodeMajor();
                    context.expect(actual == expected,
                            "loaded bytecode major " + actual + " ("
                                    + MentalPluginV5.describeBytecodeTier(actual) + ") != expected " + expected
                                    + " (" + MentalPluginV5.describeBytecodeTier(expected) + ") on "
                                    + mental.environment().describe() + " [java.specification.version="
                                    + System.getProperty("java.specification.version") + ", mental.tester.tier="
                                    + System.getProperty("mental.tester.tier", "<unset>") + "]");
                }),
                new TestCase("boot: public api facade responds", context -> {
                    var api = Mental.get();
                    context.expect(api != null, "Mental API facade is not registered");
                    context.expect(api.moduleEnabled("knockback"), "API reports knockback disabled");
                    context.expect(!api.moduleEnabled("not-a-module"), "API invented a module");
                    context.expect(api.version() != null && !api.version().isEmpty(), "API version empty");
                    context.expect(api.apiVersion() == 3, "API generation must be 3 (got " + api.apiVersion() + ")");
                    // The gen-3 capability roster is static per implementation (§4): every
                    // shipped capability answers true regardless of module toggles; the one
                    // deferred to 3.1 answers false. combat() is the runtime signal — null at
                    // the default-OFF combo modules (detection not running), even though
                    // COMBO_QUERY the capability stays true.
                    context.expect(api.has(Mental.MentalApi.Capability.COMBO_EVENTS), "COMBO_EVENTS capability missing");
                    context.expect(api.has(Mental.MentalApi.Capability.COMBO_CHAIN_EVENTS), "COMBO_CHAIN_EVENTS capability missing");
                    context.expect(api.has(Mental.MentalApi.Capability.COMBO_HIT_EVENTS), "COMBO_HIT_EVENTS capability missing");
                    context.expect(api.has(Mental.MentalApi.Capability.COMBO_QUERY), "COMBO_QUERY capability missing");
                    context.expect(api.has(Mental.MentalApi.Capability.WINDOW_QUERY), "WINDOW_QUERY capability missing");
                    context.expect(api.has(Mental.MentalApi.Capability.KNOCKBACK_OUTCOMES), "KNOCKBACK_OUTCOMES capability missing");
                    context.expect(!api.has(Mental.MentalApi.Capability.MITIGATION_PREVIEW),
                            "MITIGATION_PREVIEW deferred to 3.1 — must be false");
                    context.expect(api.combat() == null, "combat() must be null with combo modules at default OFF");
                }),
                new TestCase("boot: latency probe transport matches the server version", context -> {
                    // The transport is version-determined: below 1.17 the play PING/PONG
                    // channel is absent on the wire, so probes ride window-confirmation
                    // TRANSACTIONs (TransactionProbeRim); at/above 1.17 the dedicated play
                    // channel (ProbeRim, PING). This pins BOTH the legacy selection and the
                    // modern regression (PING stays selected on 1.17+ — byte-identical path).
                    boolean modern = mental.environment().isAtLeast(1, 17, 0);
                    ProbeStrategy expected = modern ? ProbeStrategy.PING : ProbeStrategy.TRANSACTION;
                    context.expect(mental.probeTransport() == expected,
                            "probe transport " + mental.probeTransport() + " != expected " + expected
                                    + " for " + mental.environment().describe());
                    // The send path (and thus the transport's server wrapper) must classload and
                    // run without throwing on THIS server. Clientless test players carry no PE
                    // user, so no echo returns — the wire RTT round-trip is verified out of band
                    // (Phase 2 gate note), but a pre-1.17 wrapper break would surface right here.
                    context.syncRun(() -> context.expect(mental.probeSelfTest(),
                            "probe send self-test threw on " + mental.environment().describe()));
                }),
                new TestCase("boot: every management screen renders headless (Adventure + String sinks load)", context -> {
                    // Proves the TextPort/Adventure seam and the universal String-API sinks actually
                    // classload and execute on THIS server, for EVERY redesigned screen. On legacy servers
                    // (< 1.16.5) the Paper-native Component sinks are absent and net.kyori ships only as
                    // Mental's relocated copy, so a broken shade or a stray Component→Bukkit call would
                    // throw right here rather than on the first live /mental open. Run on the global tick
                    // (createInventory + item meta are main/region-affine on some servers); no viewer is
                    // needed — the String-title path and the headless icon render exercise every sink.
                    context.syncRun(() -> {
                        MenuContext menuContext = new MenuContext(
                                mental, mental.management(), new ChatPrompt(mental.scheduling()));
                        DashboardMenu dashboard = new DashboardMenu(menuContext);

                        List<ItemStack> icons = dashboard.selfTestIcons();
                        context.expect(!icons.isEmpty(), "dashboard rendered no icons");
                        for (ItemStack icon : icons) {
                            context.expect(icon != null && icon.getType() != Material.AIR,
                                    "a dashboard icon rendered as null/AIR — an Icon build sink failed");
                        }

                        Inventory inventory = dashboard.selfTestInventory();
                        context.expect(inventory.getSize() == 54,
                                "dashboard inventory size " + inventory.getSize() + " != 54");
                        context.expect(inventory.getHolder() == dashboard,
                                "inventory holder is not the menu — the click-router identity test would break");

                        // Every family renders on the one FamilyMenu metaphor — a 3+ row chest whose
                        // holder identity the click router still recognizes.
                        for (Family family : Family.values()) {
                            FamilyMenu menu = new FamilyMenu(menuContext, family);
                            assertScreenRenders(context, menu, menu.selfTestIcons(), "family " + family);
                            int size = menu.selfTestInventory().getSize();
                            context.expect(size % 9 == 0 && size >= 27,
                                    "family (" + family + ") inventory size " + size + " is not a 3+ row chest");
                        }

                        // Every descriptor-driven settings page — the catalog's own iteration seam IS the
                        // render list, so a newly configured page can never ship un-rendered.
                        for (Feature feature : SettingsCatalog.configuredFeatures()) {
                            SettingsMenu menu = new SettingsMenu(menuContext, feature);
                            assertScreenRenders(context, menu, menu.selfTestIcons(), "settings " + feature);
                        }

                        // The knockback gallery renders per MeleeFormula tab (keeping the MeleeFormula
                        // compile pin alive); the modern tab pulls the Netherite/Trident icon names
                        // (absent below 1.16 → MenuMaterials fallback), so a legacy classload break or a
                        // stray null icon surfaces at boot rather than on first open.
                        PresetGalleryMenu knockback = new PresetGalleryMenu(menuContext, PresetKind.KNOCKBACK);
                        for (MeleeFormula which : MeleeFormula.values()) {
                            assertIconsRender(context, knockback.selfTestIcons(which), "knockback gallery " + which);
                        }
                        Inventory knockbackList = knockback.selfTestInventory();
                        context.expect(knockbackList.getSize() == 54,
                                "knockback-gallery inventory size " + knockbackList.getSize() + " != 54");
                        context.expect(knockbackList.getHolder() == knockback,
                                "knockback-gallery inventory holder is not the menu");

                        // The Combat Effects gallery rides the same sink path with its own preview lore.
                        PresetGalleryMenu effects = new PresetGalleryMenu(menuContext, PresetKind.EFFECTS);
                        assertScreenRenders(context, effects, effects.selfTestIcons(), "effects gallery");
                        Inventory effectsList = effects.selfTestInventory();
                        context.expect(effectsList.getSize() == 54,
                                "effects-gallery inventory size " + effectsList.getSize() + " != 54");

                        // The two neutral system screens.
                        CompatibilityMenu compatibility = new CompatibilityMenu(menuContext);
                        assertScreenRenders(context, compatibility, compatibility.selfTestIcons(), "compatibility");
                        DebugMenu debug = new DebugMenu(menuContext);
                        assertScreenRenders(context, debug, debug.selfTestIcons(), "debug");

                        // The Combat Presets screen (rules bundles) rides the same headless sink path.
                        CombatPresetsMenu combatPresets = new CombatPresetsMenu(menuContext);
                        assertScreenRenders(context, combatPresets, combatPresets.selfTestIcons(),
                                "combat presets");

                        // §7.2: the pane-regression guard on every version — a legacy pane-colour break can
                        // never ship silently, since STONE (the resolver's fall-through) fails the check.
                        panesRenderEraCorrect(context);
                    });
                }),
                new TestCase("legacy: era weapon damage resolves through the flattening name seam", context ->
                        legacyEraDamageSmoke(mental, context)),
                new TestCase("legacy: golden-apples enables cleanly (no pre-1.13 refusal)", context ->
                        legacyGoldenApplesSmoke(mental, context)),
                new TestCase("platform: the manifest degrades to the expected per-version resolution set",
                        context -> manifestDegradesPerVersion(mental, context)),
                new TestCase("legacy: era-hitbox reach enables (Bukkit-only, a no-op below 1.20.5)", context ->
                        legacyHitboxEnables(mental, context)),
                new TestCase("legacy: sword-blocking selects the off-hand-shield decoration (tier NONE)",
                        context -> legacySwordBlockDecoration(mental, context)),
                new TestCase("legacy: cooldown tooltip strip drops attack_speed, keeps attack_damage (path C)",
                        context -> legacyTooltipStrip(mental, context)));
    }

    /**
     * The class-file major the plugin's bytecode is expected to load at on this server.
     * The entry-declared {@code -Dmental.tester.tier} (an integer major) wins when present —
     * Phase 2 sets it per matrix entry from the support-matrix {@code bytecodeTier}. Absent,
     * it derives from the running JVM's feature version: Java 17+ reads the original
     * versions/17 tree (v61); everything below (Java 8–16) reads the mega-jar base (v52).
     * There is NO versions/16 tier — jvmdg 1.3.6 cannot co-produce it alongside versions/17
     * (Phase 1 escalation 1) — so even a Java-16 MR-aware loader finds no versioned tier
     * ≤ 16 and reads base. (The per-entry -Dmental.tester.tier is the authority in the
     * matrix; this default only backs ad-hoc boots that do not set it.)
     */
    private static int expectedBytecodeMajor() {
        String declared = System.getProperty("mental.tester.tier");
        if (declared != null && !declared.trim().isEmpty()) {
            return Integer.parseInt(declared.trim());
        }
        String spec = System.getProperty("java.specification.version", "");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2); // "1.8" -> "8"
        }
        int feature;
        try {
            feature = Integer.parseInt(spec);
        } catch (NumberFormatException malformed) {
            feature = -1;
        }
        if (feature >= 17) {
            return 61;
        }
        // Java 8–16 all read the base tree: there is no versioned tier ≤ 16 in the jar
        // (versions/17 sits above even a Java-16 loader; the versions/16 tier was dropped).
        return 52;
    }

    /* ------------------------------------------------------------------ */
    /*  Gate 4 — the per-version manifest expectations table (item 6)       */
    /* ------------------------------------------------------------------ */

    /**
     * Turns "the manifest degraded correctly" into a per-version pinned fact. For EVERY manifest
     * entry the expected presence is a function of one declared first-present version (the Required
     * handles present on all supported versions; each OptionalSince present at/above its own since),
     * asserted against {@code entry.present()} on the running server. Runs on every entry — modern
     * (everything resolves per its since) and legacy alike — so a mapping break or a mis-degraded
     * probe on any single version fails structurally here. The table must stay complete in both
     * directions: an unknown entry name or a stale table key is itself a failure.
     */
    private static void manifestDegradesPerVersion(MentalPluginV5 mental, TestContext context) {
        ServerEnvironment env = mental.environment();
        Map<String, int[]> presentSince = manifestPresentSince();
        Set<String> seen = new HashSet<>();
        for (ManifestEntry entry : mental.platformProfile().entries()) {
            seen.add(entry.name());
            int[] since = presentSince.get(entry.name());
            context.expect(since != null,
                    "manifest entry '" + entry.name() + "' is absent from the expectations table — add it "
                            + "(a new probe must declare its per-version band)");
            if (since == null) {
                continue;
            }
            boolean expected = env.isAtLeast(since[0], since[1], since[2]);
            context.expect(entry.present() == expected,
                    "manifest '" + entry.name() + "' present=" + entry.present() + " but expected " + expected
                            + " on " + env.describe() + " (present-since " + since[0] + "." + since[1] + "."
                            + since[2] + ")");
        }
        for (String name : presentSince.keySet()) {
            context.expect(seen.contains(name),
                    "expectations table names '" + name + "' but the live manifest has no such entry (stale?)");
        }
        // Item 1's payoff: with armor_toughness now OptionalSince, NO feature is platform-disabled on
        // any supported version — the mapping-break disable set is empty on 1.9.4/1.10.2 too.
        context.expect(mental.platformProfile().disabledFeatures().isEmpty(),
                "features platform-disabled on " + env.describe() + ": "
                        + mental.platformProfile().disabledFeatures());
    }

    /**
     * The first version each manifest entry resolves on — the pinned expectations table (item 6).
     * Required handles are present on every supported version ({@code {1,0,0}}); each OptionalSince
     * entry declares the version it first appears. {@code component:weapon_tooltip} is present on the
     * whole range after item 3 (path C on 1.9.4–1.15.2, path B on 1.16.5–1.20.x, path A on 1.20.5+).
     */
    private static Map<String, int[]> manifestPresentSince() {
        int[] always = {1, 0, 0};
        Map<String, int[]> table = new LinkedHashMap<>();
        for (String required : List.of(
                "attribute:attack_damage", "attribute:attack_speed", "attribute:knockback_resistance",
                "attribute:max_health", "attribute:armor",
                "enchant:sharpness", "enchant:punch", "enchant:knockback", "enchant:protection",
                "enchant:fire_protection", "enchant:feather_falling", "enchant:blast_protection",
                "enchant:projectile_protection", "enchant:unbreaking")) {
            table.put(required, always);
        }
        table.put("attribute:armor_toughness", new int[] {1, 11, 2}); // item 1 — OptionalSince(1.11.2)
        table.put("attribute:gravity", new int[] {1, 20, 5});
        table.put("attribute:entity_interaction_range", new int[] {1, 20, 5});
        table.put("capability:hurt_animation_bundle", new int[] {1, 19, 4});
        table.put("capability:knockback_event", new int[] {1, 20, 6});
        table.put("capability:recipe_key", new int[] {1, 12, 0}); // GAP 1 — NamespacedKey + keyed ctor land 1.12
        table.put("capability:sweep_cause", new int[] {1, 11, 0}); // GAP 2 — ENTITY_SWEEP_ATTACK lands 1.11
        table.put("flag:projectile_kb_restored", new int[] {1, 21, 2});
        table.put("marker:join_protection_layout", new int[] {1, 21, 2});
        table.put("component:max_damage", new int[] {1, 20, 5});
        table.put("component:sword_block", new int[] {1, 21, 0});
        table.put("component:weapon_tooltip", always); // item 3 — resolvable on the whole range
        table.put("component:attack_range", new int[] {1, 21, 5});
        return table;
    }

    /* ------------------------------------------------------------------ */
    /*  Loadout / block / cadence on legacy (items 4, 5, 7)                */
    /* ------------------------------------------------------------------ */

    /**
     * Item 4: HITBOX owns no Required manifest handle and its two reach levers are capability-gated
     * Bukkit surfaces (no NMS), so it must ENABLE cleanly on every legacy revision and be a complete
     * no-op below 1.20.5 (no interaction-range attribute, no ATTACK_RANGE component) — era-benign,
     * since 1.9–1.16 survival reach is already ~3.0. Pins enable-converges + zero-touch teardown.
     * Skips (with a reason) at/above 1.20.5 where the levers become live (covered by HitboxSuite).
     */
    private static void legacyHitboxEnables(MentalPluginV5 mental, TestContext context) throws Exception {
        if (mental.environment().isAtLeast(1, 20, 5)) {
            context.note("skipped: the hitbox-reach levers are live at/above 1.20.5 ("
                    + mental.environment().describe() + ") — this case pins the legacy no-op band.");
            return;
        }
        context.expect(!mental.platformProfile().disabledFeatures().contains(Feature.HITBOX),
                "HITBOX must never be platform-disabled — it owns no Required manifest handle");
        try {
            setFeature(mental, context, Feature.HITBOX, true);
            context.expect(mental.featureActive(Feature.HITBOX),
                    "HITBOX did not converge active on a legacy server (it is pure Bukkit — must enable)");
        } finally {
            setFeature(mental, context, Feature.HITBOX, false);
        }
        context.expect(!mental.featureActive(Feature.HITBOX),
                "HITBOX did not disable — zero-touch not restored");
    }

    /**
     * Item 5: below 1.21 the era sword-block mechanic is the off-hand-shield decoration
     * (EphemeralDecoration off-hand tier), selected exactly when {@code SwordBlockAdapter.tier() ==
     * NONE}. SHIELD exists 1.9+; Phase 1 added the pre-1.14 in-memory temp-shield identity. A
     * clientless boot has no real player to drive inject/revert through, so this pins the WIRING:
     * enable converges active, the adapter reports tier NONE (the off-hand path is the one selected),
     * SHIELD resolves, and disable restores zero-touch. The live inject/revert leak-check needs a real
     * player (Phase 5 FakePlayer) — stated here, never silently skipped.
     */
    private static void legacySwordBlockDecoration(MentalPluginV5 mental, TestContext context)
            throws Exception {
        if (mental.environment().isAtLeast(1, 21, 0)) {
            context.note("skipped: 1.21+ drives the in-place component sword-block pose ("
                    + mental.environment().describe() + ") — this case pins the pre-1.21 off-hand path.");
            return;
        }
        context.expect(mental.platformProfile().swordBlock().tier() == SwordBlockAdapter.Tier.NONE,
                "pre-1.21 sword-block adapter tier must be NONE — the off-hand-shield decoration path");
        context.expect(Material.getMaterial("SHIELD") != null,
                "SHIELD material must resolve (it exists 1.9+) for the off-hand decoration");
        try {
            setFeature(mental, context, Feature.SWORD_BLOCKING, true);
            context.expect(mental.featureActive(Feature.SWORD_BLOCKING),
                    "SWORD_BLOCKING did not converge active on a legacy server");
        } finally {
            setFeature(mental, context, Feature.SWORD_BLOCKING, false);
        }
        context.expect(!mental.featureActive(Feature.SWORD_BLOCKING),
                "SWORD_BLOCKING did not disable — zero-touch decoration teardown not restored");
        context.note("sword-block off-hand-shield inject/revert leak-check requires a real player "
                + "(a clientless boot cannot drive it) — deferred to Phase 5 FakePlayer.");
    }

    /**
     * Item 7: the pre-1.13-through-1.15 cooldown tooltip strip is the versioned-NMS NBT path (path C),
     * whose read-back seam ({@link WeaponTooltipAdapter#mainHandAttributeNames}) is available exactly
     * where path C resolves (1.9.4–1.15.2). Build a DIAMOND_SWORD, confirm it carries BOTH attack
     * modifiers, run the strip, and assert the result's effective main-hand set has attack_damage but
     * NOT attack_speed. Skips (with a reason) where path C is unavailable (1.16.5+, where path B/A own
     * the strip and the existing AttackCooldownUnit unit test covers it).
     */
    private static void legacyTooltipStrip(MentalPluginV5 mental, TestContext context) throws Exception {
        WeaponTooltipAdapter tooltip = mental.platformProfile().weaponTooltip();
        Material diamondSword = Material.getMaterial("DIAMOND_SWORD");
        context.expect(diamondSword != null, "DIAMOND_SWORD did not resolve — environment misread?");
        context.syncRun(() -> {
            ItemStack sword = new ItemStack(diamondSword);
            Set<String> before = tooltip.mainHandAttributeNames(sword);
            if (before == null) {
                context.note("skipped: the versioned-NMS NBT read-back (path C) is unavailable on "
                        + mental.environment().describe() + " — path B/A own the strip there "
                        + "(covered by AttackCooldownUnitTest).");
                return;
            }
            context.expect(containsAttack(before, "attackdamage") && containsAttack(before, "attackspeed"),
                    "a vanilla diamond sword must carry BOTH attack_damage and attack_speed before the strip "
                            + "(saw " + before + ")");
            ItemStack stripped = tooltip.stripAttackSpeed(sword);
            context.expect(stripped != null, "the strip returned no change on a sword that has attack_speed");
            Set<String> after = tooltip.mainHandAttributeNames(stripped);
            context.expect(after != null, "read-back of the stripped item failed");
            context.expect(containsAttack(after, "attackdamage"),
                    "attack_damage must survive the strip (saw " + after + ")");
            context.expect(!containsAttack(after, "attackspeed"),
                    "attack_speed must be gone after the strip (saw " + after + ")");
        });
    }

    /** Asserts every icon in a menu's headless self-test render built as a non-AIR Bukkit item. */
    private static void assertIconsRender(
            @NotNull TestContext context, @NotNull List<ItemStack> icons, @NotNull String label) {
        context.expect(!icons.isEmpty(), label + " rendered no icons");
        for (ItemStack icon : icons) {
            context.expect(icon != null && icon.getType() != Material.AIR,
                    "a " + label + " icon rendered as null/AIR — an Icon build sink failed");
        }
    }

    /** Asserts a menu's headless render built non-AIR icons and kept its holder identity. */
    private static void assertScreenRenders(@NotNull TestContext context, @NotNull Menu menu,
            @NotNull List<ItemStack> icons, @NotNull String label) {
        assertIconsRender(context, icons, label);
        context.expect(menu.selfTestInventory().getHolder() == menu,
                label + " inventory holder is not the menu — the click-router identity test would break");
    }

    /**
     * §7.2 pane-regression guard: every {@link PaneColor} must resolve to a real coloured pane on this
     * exact server. STONE is {@code MenuMaterials.FALLBACK} — its appearance means neither the modern
     * per-colour material nor the pre-flattening STAINED_GLASS_PANE resolved, the exact silent legacy
     * regression this check exists to catch. On a flattened server (the modern name is present) the stack
     * is that per-colour material with durability 0 and NO data value; below 1.13 it is STAINED_GLASS_PANE
     * carrying the colour's own data value. Runs on every version including 1.9.4.
     */
    @SuppressWarnings("deprecation") // getDurability() is the pre-1.13 pane data-value accessor
    private static void panesRenderEraCorrect(@NotNull TestContext context) {
        for (PaneColor color : PaneColor.values()) {
            ItemStack pane = MenuMaterials.pane(color);
            context.expect(pane != null && pane.getType() != Material.AIR
                            && !"STONE".equals(pane.getType().name()),
                    "pane " + color + " resolved to null/AIR/STONE — the pane resolver fell through");
            if (Material.getMaterial(color.modernName()) != null) {
                context.expect(pane.getType().name().equals(color.modernName()),
                        "modern pane " + color + " must be " + color.modernName()
                                + " (got " + pane.getType().name() + ")");
                context.expect(pane.getDurability() == 0,
                        "modern pane " + color + " must carry no data value (got " + pane.getDurability() + ")");
            } else {
                context.expect(pane.getType().name().equals("STAINED_GLASS_PANE"),
                        "legacy pane " + color + " must be STAINED_GLASS_PANE (got " + pane.getType().name() + ")");
                context.expect(pane.getDurability() == color.legacyData(),
                        "legacy pane " + color + " must carry data " + color.legacyData()
                                + " (got " + pane.getDurability() + ")");
            }
        }
    }

    /** Underscore-insensitive membership: matches {@code generic.attackSpeed} and {@code attack_speed}. */
    private static boolean containsAttack(@NotNull Set<String> names, @NotNull String needle) {
        for (String name : names) {
            if (name.replace("_", "").contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Flattening correctness (Phase 3) — pre-1.13 rules smoke            */
    /* ------------------------------------------------------------------ */

    /**
     * Q1: on a pre-flattening server {@code Material.name()} returns the OLD combat
     * constant, which the kernel's modern-named weapon table would miss. This drives
     * the REAL {@link DamageShaper} seam (EffectiveMaterial → {@code
     * LegacyMaterialNames.modernize} → {@code DamageTables.weaponDamage}) for a
     * legacy {@code WOOD_SWORD} stack and pins the era value (5.0 = WOODEN_SWORD,
     * cited from {@code DamageTablesTest} — not re-derived), including the two
     * compound rename traps. It also proves the DAMAGE family enables on legacy.
     * Identity on flattened servers, so it skips (with a reason) at/above 1.13.
     */
    private static void legacyEraDamageSmoke(MentalPluginV5 mental, TestContext context) throws Exception {
        if (mental.environment().isAtLeast(1, 13, 0)) {
            context.note("skipped: the flattening name seam is identity on 1.13+ ("
                    + mental.environment().describe() + ") — legacy names never surface there.");
            return;
        }
        Material woodSword = Material.getMaterial("WOOD_SWORD");
        context.expect(woodSword != null,
                "WOOD_SWORD did not resolve on a pre-1.13 server — environment misread?");
        context.expect(Material.getMaterial("WOODEN_SWORD") == null,
                "WOODEN_SWORD resolved on a supposedly pre-flattening server — not actually pre-1.13?");

        // Enable exactly the DAMAGE-family modules this version's PlatformProfile did NOT
        // disable. As of Phase 4 item 1 that is ALL of them on every legacy version:
        // ARMOUR_STRENGTH's armor_toughness handle became OptionalSince (the era 1.8 flat model
        // ignores toughness), so it is no longer platform-disabled on 1.9.4/1.10.2 and enables
        // here too. The platformDisabled filter stays as a guard against any future mapping break.
        // The rest are pure-listener members; sword-blocking's shield flow stays out (its own
        // dedicated legacy check). This proves the enable-able family assembles on legacy without
        // asserting a version-gated feature into existence.
        Set<Feature> platformDisabled = mental.platformProfile().disabledFeatures();
        List<Feature> toEnable = new ArrayList<>();
        for (Feature feature : List.of(Feature.ARMOUR_STRENGTH, Feature.ARMOUR_DURABILITY,
                Feature.CRIT_FALLBACK, Feature.TOOL_DURABILITY)) {
            if (!platformDisabled.contains(feature)) {
                toEnable.add(feature);
            }
        }
        try {
            for (Feature feature : toEnable) {
                setFeature(mental, context, feature, true);
            }
            for (Feature feature : toEnable) {
                context.expect(mental.featureActive(feature),
                        "DAMAGE-family feature " + feature + " did not converge active on legacy");
            }
            context.syncRun(() -> {
                context.expectNear(5.0, DamageShaper.eraToolBase(new ItemStack(woodSword)), 1.0e-9,
                        "era WOOD_SWORD damage (→ WOODEN_SWORD)");
                Material goldSword = Material.getMaterial("GOLD_SWORD");
                context.expect(goldSword != null, "GOLD_SWORD did not resolve pre-1.13");
                context.expectNear(5.0, DamageShaper.eraToolBase(new ItemStack(goldSword)), 1.0e-9,
                        "era GOLD_SWORD damage (→ GOLDEN_SWORD)");
                Material woodSpade = Material.getMaterial("WOOD_SPADE");
                context.expect(woodSpade != null, "WOOD_SPADE did not resolve pre-1.13");
                context.expectNear(2.0, DamageShaper.eraToolBase(new ItemStack(woodSpade)), 1.0e-9,
                        "era WOOD_SPADE damage (compound → WOODEN_SHOVEL)");
            });
        } finally {
            for (Feature feature : toEnable) {
                setFeature(mental, context, feature, false);
            }
            for (Feature feature : toEnable) {
                context.expect(!mental.featureActive(feature),
                        "DAMAGE-family feature " + feature + " did not disable — zero-touch not restored");
            }
        }
    }

    /**
     * Q3/H4: golden-apples must ENABLE on every legacy entry (1.9.4–1.16.5), not
     * refuse pre-1.13. Since {@code featureActive} cannot tell a genuine enable from
     * the retired refusal early-return (both leave an open scope), this asserts the
     * notch-apple recipe actually registered — proof the real recipe path engaged.
     * Skips (with a reason) at/above 1.17 so the modern boot run is left untouched.
     */
    private static void legacyGoldenApplesSmoke(MentalPluginV5 mental, TestContext context) throws Exception {
        if (mental.environment().isAtLeast(1, 17, 0)) {
            context.note("skipped: the golden-apples legacy verification targets the 1.9–1.16 band ("
                    + mental.environment().describe() + ").");
            return;
        }
        boolean flattened = mental.environment().isAtLeast(1, 13, 0);
        try {
            setFeature(mental, context, Feature.GOLDEN_APPLES, true);
            context.expect(mental.featureActive(Feature.GOLDEN_APPLES),
                    "golden-apples did not converge active on a legacy server");
            boolean registered = context.sync(() -> notchAppleRecipeRegistered(flattened));
            context.expect(registered,
                    "golden-apples enabled but no notch-apple recipe registered — the legacy recipe "
                            + "path did not engage (pre-1.13 refusal not lifted?)");
            // The 2.4.1 GAP-1 regression pin: the recipe half (scope.task) survives a poisoned
            // registerEvents, so a recipe alone cannot prove the CONSUME half is alive. Bukkit
            // reflects over every declared method at registerEvents and swallows a descriptor
            // NoClassDefFoundError into one SEVERE line, registering ZERO handlers — before the
            // NappleKeyed hoist this assertion failed on 1.9.4–1.11.2 (NamespacedKey lands 1.12).
            boolean consumeRegistered = context.sync(BootSuite::goldenApplesConsumeHandlerRegistered);
            context.expect(consumeRegistered,
                    "golden-apples active but no GoldenApplesUnit handler is registered on "
                            + "PlayerItemConsumeEvent — registerEvents swallowed a listener-descriptor "
                            + "linkage error (the GAP-1 NamespacedKey hazard)");
        } finally {
            setFeature(mental, context, Feature.GOLDEN_APPLES, false);
        }
    }

    /** Whether a {@link GoldenApplesUnit} holds a live {@link PlayerItemConsumeEvent} handler. */
    private static boolean goldenApplesConsumeHandlerRegistered() {
        for (RegisteredListener listener : PlayerItemConsumeEvent.getHandlerList().getRegisteredListeners()) {
            if (listener.getListener() instanceof GoldenApplesUnit) {
                return true;
            }
        }
        return false;
    }

    /** Toggles a feature through the management write-back seam and waits one tick for convergence. */
    private static void setFeature(
            MentalPluginV5 mental, TestContext context, Feature feature, boolean enabled) throws Exception {
        context.syncRun(() -> mental.management().setModuleEnabled(feature, enabled));
        context.awaitTicks(1);
    }

    /**
     * Whether a notch-apple ({@code ENCHANTED_GOLDEN_APPLE} on 1.13+, {@code
     * GOLDEN_APPLE:1} pre-flattening) shaped recipe is registered — the positive
     * signal that the golden-apples recipe path ran rather than refusing.
     */
    @SuppressWarnings("deprecation") // getDurability() is the pre-1.13 data-value accessor
    private static boolean notchAppleRecipeRegistered(boolean flattened) {
        Material enchanted = flattened ? Material.getMaterial("ENCHANTED_GOLDEN_APPLE") : Material.GOLDEN_APPLE;
        if (enchanted == null) {
            return false;
        }
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe instanceof ShapedRecipe shaped) {
                ItemStack result = shaped.getResult();
                if (result.getType() == enchanted && (flattened || result.getDurability() == 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
