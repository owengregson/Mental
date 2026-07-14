package me.vexmc.mental.v5.manage;

import java.util.List;
import java.util.Locale;
import me.vexmc.mental.api.event.KnockbackProfileChangeEvent;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import org.jetbrains.annotations.NotNull;

/**
 * The configuration write-back seam behind the public API facade and the
 * (Phase 6) management GUI — the v5 replacement for the retired
 * {@code manage.ManagementService}.
 *
 * <p>Each method mutates exactly one machine-overlay key and swaps the live
 * typed snapshot atomically through {@link MentalPluginV5#reloadAll()} — the
 * human config files are never re-serialized, so a half-applied config is never
 * read mid-hit. The overlay wins over the human files, so a reload picks up the
 * change; {@code reloadAll} re-reads the persisted overlay from disk, so the
 * write must land there first (it does — {@code Overlay.set} persists).</p>
 *
 * <p>Every method reaches {@code reloadAll()} and so must run where that is
 * safe — the main thread, the global region thread on Folia. Callers dispatch
 * through {@code Scheduling.runGlobal}; the public API documents the same
 * requirement. The methods themselves run synchronously on the calling thread
 * (they return a value the caller needs in-line), exactly as the old service
 * did.</p>
 */
public final class Management {

    private final MentalPluginV5 plugin;

    public Management(@NotNull MentalPluginV5 plugin) {
        this.plugin = plugin;
    }

    /** Re-reads every configuration file and converges the features; returns warnings. */
    public @NotNull List<String> reload() {
        return plugin.reloadAll();
    }

    /**
     * Sets the server-wide knockback profile (the {@code knockback.profile}
     * overlay key), reloads, and fires {@link KnockbackProfileChangeEvent} on an
     * actual change. Returns false — changing nothing — when no profile by that
     * name is loaded; returns true with no write and no event when it is already
     * the active profile.
     */
    public boolean setGlobalProfile(@NotNull String profileName) {
        String name = profileName.trim().toLowerCase(Locale.ROOT);
        if (!plugin.snapshot().hasProfile(name)) {
            return false;
        }
        String previous = plugin.snapshot().defaultProfile();
        if (previous.equals(name)) {
            return true; // already the active profile — no write, no event.
        }
        plugin.overlaySet("knockback.profile", name);
        plugin.reloadAll();
        new KnockbackProfileChangeEvent(previous, name).callEvent();
        return true;
    }

    /**
     * Sets the server-wide Combat Effects preset (the {@code effects.preset}
     * overlay key) and reloads — the GUI preset picker's write-back, the exact
     * {@link #setGlobalProfile} shape for the FEEDBACK family. The reconciler's
     * settings-change bounce re-assembles the three effects units live, so the
     * new tune plays on the very next hit. Returns false — changing nothing —
     * when no preset by that name is loaded; returns true with no write and no
     * reload when it is already the selected preset.
     */
    public boolean setEffectsPreset(@NotNull String presetName) {
        String name = presetName.trim().toLowerCase(Locale.ROOT);
        if (!plugin.snapshot().hasEffectsPreset(name)) {
            return false;
        }
        if (plugin.snapshot().selectedEffectsPreset().equals(name)) {
            return true; // already the selected preset — no write, no reload.
        }
        plugin.overlaySet("effects.preset", name);
        plugin.reloadAll();
        return true;
    }

    /**
     * Runtime module toggle: writes the feature's {@code modules.*} overlay key
     * and reconverges. Infrastructure features (no yaml toggle) are ignored.
     * Returns the reload's warn-and-fallback issues.
     */
    public @NotNull List<String> setModuleEnabled(@NotNull Feature feature, boolean enabled) {
        if (feature.infrastructure()) {
            return plugin.reloadAll();
        }
        plugin.overlaySet("modules." + feature.yamlKey(), enabled);
        return plugin.reloadAll();
    }

    /**
     * Writes one machine-overlay key and reloads — the single write-back the
     * in-GUI value editors, the compatibility screen, and the debug screen all
     * flow through, so no GUI code reaches past this seam into the plugin's
     * overlay directly. The key is a dotted config path (e.g.
     * {@code effects.death.kill-title}, {@code drop-protection.seconds},
     * {@code anticheat.mode}); the human files are never re-serialized.
     * Returns the reload's warn-and-fallback issues.
     */
    public @NotNull List<String> setOverlay(@NotNull String key, @NotNull Object value) {
        plugin.overlaySet(key, value);
        return plugin.reloadAll();
    }

    /** Clears one machine-overlay key (reset an in-GUI edit back to the file / preset) and reloads. */
    public @NotNull List<String> clearOverlay(@NotNull String key) {
        plugin.overlayRemove(key);
        return plugin.reloadAll();
    }
}
