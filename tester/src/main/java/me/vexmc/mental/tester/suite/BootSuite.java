package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.ProbeStrategy;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import me.vexmc.mental.v5.gui.DashboardMenu;
import me.vexmc.mental.v5.gui.MenuContext;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
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
                new TestCase("boot: public api facade responds", context -> {
                    var api = Mental.get();
                    context.expect(api != null, "Mental API facade is not registered");
                    context.expect(api.moduleEnabled("knockback"), "API reports knockback disabled");
                    context.expect(!api.moduleEnabled("not-a-module"), "API invented a module");
                    context.expect(api.version() != null && !api.version().isEmpty(), "API version empty");
                    context.expect(api.apiVersion() == 2, "API generation must be 2 (got " + api.apiVersion() + ")");
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
                new TestCase("boot: dashboard GUI renders headless (Adventure + String sinks load)", context -> {
                    // Proves the TextPort/Adventure seam and the universal String-API sinks actually
                    // classload and execute on THIS server. On legacy servers (< 1.16.5) the Paper-native
                    // Component sinks are absent and net.kyori ships only as Mental's relocated copy, so a
                    // broken shade or a stray Component→Bukkit call would throw right here rather than on the
                    // first live /mental open. Run on the global tick (createInventory + item meta are
                    // main/region-affine on some servers); no viewer is needed — the String-title path and
                    // the headless icon render exercise every sink.
                    context.syncRun(() -> {
                        MenuContext menuContext = new MenuContext(mental, mental.management());
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
                    });
                }),
                new TestCase("legacy: era weapon damage resolves through the flattening name seam", context ->
                        legacyEraDamageSmoke(mental, context)),
                new TestCase("legacy: golden-apples enables cleanly (no pre-1.13 refusal)", context ->
                        legacyGoldenApplesSmoke(mental, context)));
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
        // disable: ARMOUR_STRENGTH needs the armor_toughness attribute handle, which is a
        // mapping break on 1.9.4/1.10.2 (its per-revision NMS resolution is Phase 4's
        // concern, not the flattening's) — so it is legitimately platform-disabled there and
        // skipped. The rest are pure-listener members; sword-blocking's shield flow stays out
        // (Phase 4's dedicated live check). This proves the enable-able family assembles on
        // legacy without asserting a version-gated feature into existence.
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
        } finally {
            setFeature(mental, context, Feature.GOLDEN_APPLES, false);
        }
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
