package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.tester.TestCase;
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
                }));
    }
}
