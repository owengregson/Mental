package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.ProbeStrategy;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.gui.DashboardMenu;
import me.vexmc.mental.v5.gui.MenuContext;
import me.vexmc.mental.tester.TestCase;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
                }));
    }
}
