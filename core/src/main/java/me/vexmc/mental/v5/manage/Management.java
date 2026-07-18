package me.vexmc.mental.v5.manage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.vexmc.mental.api.event.KnockbackProfileChangeEvent;
import me.vexmc.mental.api.event.RulesBundleAppliedEvent;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.RulesBundle;
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

    /**
     * Applies a rules bundle server-wide — the Combat Presets GUI screen's
     * write-back and the {@code MentalApi} bundle seam. A bundle is a macro, not a
     * mode: this method validates the bundle in full, writes its whole batch of
     * module/profile/preset/settings keys into the machine overlay in ONE persist,
     * performs ONE {@link MentalPluginV5#reloadAll()} snapshot swap, and fires
     * {@link RulesBundleAppliedEvent}. The human YAML is never re-serialized.
     *
     * <p>Validation is ATOMIC: every module key must resolve to a real
     * {@link Feature} toggle, and the bundle's knockback-profile / effects-preset
     * (when present) must name a loaded profile / preset. If ANY check fails, the
     * overlay is left completely untouched, nothing reloads, no event fires, and
     * the {@link BundleApplyResult} carries the full list of reasons — a bundle
     * with a typo'd key can never half-apply. An unknown bundle name is the same
     * kind of clean refusal.</p>
     */
    public @NotNull BundleApplyResult applyBundle(@NotNull String bundleName) {
        String name = bundleName.trim().toLowerCase(Locale.ROOT);
        RulesBundle bundle = plugin.bundles().get(name);
        if (bundle == null) {
            return new BundleApplyResult(name, false,
                    List.of("no rules bundle named '" + name + "' is loaded"));
        }
        BundlePlan plan = planBundle(bundle,
                plugin.snapshot().profileNames(), plugin.snapshot().effectsPresetNames());
        if (!plan.errors().isEmpty()) {
            return new BundleApplyResult(name, false, plan.errors());
        }
        plugin.overlaySetAll(plan.overlay());
        plugin.reloadAll();
        new RulesBundleAppliedEvent(name).callEvent();
        return new BundleApplyResult(name, true, List.of());
    }

    /**
     * Validates {@code bundle} and expands it into the exact overlay batch to
     * write — a pure function of the bundle and the loaded profile/preset names, so
     * the atomicity and key-expansion contract is unit-testable without a live
     * plugin. Every module key is checked against the {@link Feature} registry
     * (unknown or infrastructure keys are rejected); the optional
     * knockback-profile / effects-preset are checked against the supplied name
     * sets; the optional settings pass straight through as dotted overlay keys. On
     * ANY error the returned plan carries the errors and an EMPTY overlay — the
     * caller must apply nothing.
     */
    static @NotNull BundlePlan planBundle(
            @NotNull RulesBundle bundle,
            @NotNull Set<String> knownProfiles,
            @NotNull Set<String> knownEffectsPresets) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> overlay = new LinkedHashMap<>();

        for (Map.Entry<String, Boolean> module : bundle.modules().entrySet()) {
            String key = module.getKey();
            Optional<Feature> feature = Feature.byModuleId(key);
            if (feature.isEmpty() || feature.get().infrastructure()) {
                errors.add("module '" + key + "' is not a known feature toggle");
                continue;
            }
            overlay.put("modules." + key, module.getValue());
        }

        bundle.knockbackProfile().ifPresent(profile -> {
            if (!knownProfiles.contains(profile)) {
                errors.add("knockback profile '" + profile + "' is not loaded");
            } else {
                overlay.put("knockback.profile", profile);
            }
        });

        bundle.effectsPreset().ifPresent(preset -> {
            if (!knownEffectsPresets.contains(preset)) {
                errors.add("effects preset '" + preset + "' is not loaded");
            } else {
                overlay.put("effects.preset", preset);
            }
        });

        for (Map.Entry<String, String> setting : bundle.settings().entrySet()) {
            overlay.put(setting.getKey(), setting.getValue());
        }

        if (!errors.isEmpty()) {
            return new BundlePlan(List.copyOf(errors), Map.of());
        }
        return new BundlePlan(List.of(), overlay);
    }

    /**
     * The validated apply plan: the reasons a bundle was rejected (empty on
     * success) and the overlay batch to write (empty on any error, so atomicity is
     * structural — a rejected plan carries nothing to apply).
     */
    record BundlePlan(@NotNull List<String> errors, @NotNull Map<String, Object> overlay) {
    }

    /**
     * The outcome of {@link #applyBundle}: which bundle, whether it applied, and —
     * when it did not — the human-readable reasons (an unknown name, or the list of
     * unknown module/profile/preset references). {@code applied} true means the
     * whole batch reached the overlay and the snapshot was swapped.
     */
    public record BundleApplyResult(
            @NotNull String bundle, boolean applied, @NotNull List<String> errors) {
    }
}
