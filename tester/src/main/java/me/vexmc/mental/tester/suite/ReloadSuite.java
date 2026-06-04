package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.tester.TestCase;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/** Repeated reloads must keep every module converged and never throw. */
public final class ReloadSuite {

    private ReloadSuite() {}

    public static @NotNull List<TestCase> tests(@NotNull MentalPlugin mental) {
        return List.of(new TestCase("reload: ten consecutive reloads stay converged", context -> {
            for (int i = 0; i < 10; i++) {
                List<String> warnings = context.sync(mental::reloadAll);
                context.expect(warnings.isEmpty(),
                        "reload produced config warnings: " + warnings);
            }
            context.awaitTicks(2);
            for (var module : mental.modules().all()) {
                context.expect(module.active() == module.configEnabled(),
                        "module '" + module.id() + "' diverged after reloads");
            }
            boolean dispatched = context.sync(() ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental reload"));
            context.expect(dispatched, "'mental reload' command was not handled");
        }));
    }
}
