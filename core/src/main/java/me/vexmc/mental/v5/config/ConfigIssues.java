package me.vexmc.mental.v5.config;

import java.util.ArrayList;
import java.util.List;

/** Collects non-fatal configuration problems for one console report per reload. */
public final class ConfigIssues {

    private final List<String> issues = new ArrayList<>();

    public void warn(String path, String problem, Object fallback) {
        issues.add(path + ": " + problem + " — using " + fallback);
    }

    public List<String> all() {
        return List.copyOf(issues);
    }

    public boolean clean() {
        return issues.isEmpty();
    }
}
