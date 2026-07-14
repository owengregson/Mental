package me.vexmc.mental.v5.text;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The optional PlaceholderAPI bridge. Mental never build-depends on PAPI (it is
 * a {@code softdepend} in {@code plugin.yml}); this seam resolves
 * {@code %placeholders%} reflectively when the plugin is installed and returns
 * the raw string untouched when it is not — zero-touch absent, no crash. PAPI
 * operates on plain strings BEFORE any legacy-code deserialization, so the
 * relocation of {@code net.kyori} does not apply here; a caller renders its own
 * {@code {TOKEN}} substitutions on the raw ampersand string first, hands the
 * result here for {@code %placeholder%} expansion, then deserializes.
 *
 * <p>The presence probe is done once and cached (thread-safe); {@link #reset()}
 * re-probes after a reload so an admin who installs PAPI and reloads picks it up
 * without a restart. Every call is failure-swallowing: a missed placeholder on a
 * cosmetic path always beats a surfaced exception.</p>
 */
public final class Placeholders {

    /** {@code me.clip.placeholderapi.PlaceholderAPI#setPlaceholders(Player, String)}, or null when absent. */
    private static volatile Method setPlaceholders;
    private static volatile boolean probed;
    private static volatile boolean available;

    private Placeholders() {}

    /** Whether PlaceholderAPI is installed and its bridge method resolved. */
    public static boolean available() {
        probe();
        return available;
    }

    /** Drop the cached probe so the next call re-detects PAPI (called on config reload). */
    public static synchronized void reset() {
        probed = false;
        available = false;
        setPlaceholders = null;
    }

    /**
     * Expands PlaceholderAPI's {@code %placeholders%} in {@code raw} for
     * {@code viewer}, or returns {@code raw} unchanged when PAPI is absent (or a
     * reflection call fails). Null/empty passes straight through. Never throws.
     */
    public static String apply(Player viewer, String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        probe();
        Method method = setPlaceholders;
        if (method == null) {
            return raw;
        }
        try {
            Object rendered = method.invoke(null, viewer, raw);
            return rendered instanceof String text ? text : raw;
        } catch (Throwable failure) {
            return raw;
        }
    }

    private static void probe() {
        if (probed) {
            return;
        }
        synchronized (Placeholders.class) {
            if (probed) {
                return;
            }
            Method method = null;
            boolean present = false;
            try {
                // Bukkit.getServer() may be null in headless unit tests — guard it
                // so the probe degrades to "absent" instead of throwing.
                if (Bukkit.getServer() != null
                        && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    method = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                            .getMethod("setPlaceholders", Player.class, String.class);
                    present = true;
                }
            } catch (Throwable absent) {
                method = null;
                present = false;
            }
            setPlaceholders = method;
            available = present;
            probed = true;
        }
    }
}
