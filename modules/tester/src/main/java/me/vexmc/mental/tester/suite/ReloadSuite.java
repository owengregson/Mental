package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.tester.TestCase;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/** Repeated reloads must keep every feature converged and never throw. */
public final class ReloadSuite {

    private ReloadSuite() {}

    public static @NotNull List<TestCase> tests(@NotNull MentalPluginV5 mental) {
        return List.of(new TestCase("reload: ten consecutive reloads stay converged", context -> {
            for (int i = 0; i < 10; i++) {
                List<String> warnings = context.sync(mental::reloadAll);
                context.expect(warnings.isEmpty(),
                        "reload produced config warnings: " + warnings);
            }
            context.awaitTicks(2);
            // Every operator-toggleable feature's live active state must equal its
            // configured enablement — a reload must never leave one half-on.
            // Infrastructure descriptors carry no toggle and are always-on.
            for (Feature feature : Feature.values()) {
                if (feature.infrastructure()) {
                    continue;
                }
                boolean active = mental.featureActive(feature);
                boolean enabled = mental.snapshot().enabled(feature);
                context.expect(active == enabled,
                        "feature '" + feature + "' diverged after reloads (active " + active
                                + ", enabled " + enabled + ")");
            }
            boolean dispatched = context.sync(() ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental reload"));
            context.expect(dispatched, "'mental reload' command was not handled");
        }));
    }
}
