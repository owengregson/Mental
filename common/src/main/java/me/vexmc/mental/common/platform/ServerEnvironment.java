package me.vexmc.mental.common.platform;

import org.jetbrains.annotations.NotNull;

/**
 * Parsed server version, scheme-aware.
 *
 * <p>Minecraft used {@code 1.x.y} versions through 1.21.11 and switched to a
 * year-based {@code YY.D.H} scheme in 2026. Both parse into the same numeric
 * triple, and plain tuple ordering remains correct across the boundary because
 * every year major (26+) exceeds the legacy major (1).</p>
 */
public record ServerEnvironment(int major, int minor, int patch, boolean recognized, @NotNull String raw) {

    private static final ServerEnvironment UNRECOGNIZED = new ServerEnvironment(0, 0, 0, false, "");

    public static @NotNull ServerEnvironment parse(@NotNull String bukkitVersion) {
        String trimmed = bukkitVersion.trim();
        if (trimmed.isEmpty()) {
            return UNRECOGNIZED;
        }
        int dash = trimmed.indexOf('-');
        String numeric = dash >= 0 ? trimmed.substring(0, dash) : trimmed;
        String[] parts = numeric.split("\\.");
        if (parts.length < 2) {
            return new ServerEnvironment(0, 0, 0, false, bukkitVersion);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new ServerEnvironment(major, minor, patch, true, bukkitVersion);
        } catch (NumberFormatException malformed) {
            return new ServerEnvironment(0, 0, 0, false, bukkitVersion);
        }
    }

    public boolean isAtLeast(int major, int minor, int patch) {
        if (this.major != major) {
            return this.major > major;
        }
        if (this.minor != minor) {
            return this.minor > minor;
        }
        return this.patch >= patch;
    }

    public boolean yearScheme() {
        return major >= 26;
    }

    public @NotNull String describe() {
        if (!recognized) {
            return "unrecognized (" + raw + ")";
        }
        return major + "." + minor + "." + patch + (yearScheme() ? " (year scheme)" : " (legacy scheme)");
    }
}
