package me.vexmc.mental.config;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Collects non-fatal configuration problems for one console report per reload. */
public final class ConfigIssues {

    private final List<String> issues = new ArrayList<>();

    public void warn(@NotNull String path, @NotNull String problem, @NotNull Object fallback) {
        issues.add(path + ": " + problem + " — using " + fallback);
    }

    public @NotNull List<String> all() {
        return List.copyOf(issues);
    }

    public boolean clean() {
        return issues.isEmpty();
    }
}
