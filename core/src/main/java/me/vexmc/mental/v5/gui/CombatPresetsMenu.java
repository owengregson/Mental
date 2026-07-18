package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.vexmc.mental.v5.config.RulesBundle;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The Combat Presets picker — the {@link EffectsPresetMenu} model mirrored a third
 * time, for rules bundles. Lists every loaded {@code bundles/*.yml} by stem;
 * clicking a tile applies it server-wide through {@code Management.applyBundle},
 * which validates the whole bundle, writes its batch of module/profile/preset keys
 * into the machine overlay in one persist, and reloads once. A bundle is a macro,
 * not a mode — applying one flips a whole ruleset at once (the CT8c snapshot, the
 * classic 1.7 feel, or Mental-transparent vanilla).
 *
 * <p>Because a bundle leaves no live "active bundle" state behind, the ACTIVE mark
 * is computed read-only: a bundle reads as active when the current config already
 * equals every toggle (and the profile/preset) it would set — so re-applying it is
 * a no-op. The tile preview is READ-ONLY: what the bundle turns on, and which
 * profile/preset it selects.</p>
 *
 * <p>No back stack, so Back is hardcoded to the {@link DashboardMenu} the picker is
 * reached from.</p>
 */
public final class CombatPresetsMenu extends Menu {

    /** Themed icon per shipped bundle; unknown (operator) bundles fall back to paper. */
    private static final Map<String, String> BUNDLE_ICONS = Map.of(
            "ct8c", "NETHERITE_SWORD",
            "signature", "NETHER_STAR",
            "vanilla", "GRASS_BLOCK");

    /** The picker's two bundle rows (rows 2–3 of a six-row inventory). */
    private static final int[] BUNDLE_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    public CombatPresetsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Combat Presets", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        // BUNDLE_SLOTS reach slot 34, so the picker needs the full six rows.
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title("CHEST", "Combat Presets");
        Buttons.wrap("One preset applies a whole ruleset at once — a batch of module"
                + " toggles, plus the knockback profile and effects tune it needs."
                + " It is a macro: applying one leaves the server in a fully-known state.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        List<String> names = bundleNames();
        for (int i = 0; i < names.size() && i < BUNDLE_SLOTS.length; i++) {
            String name = names.get(i);
            RulesBundle bundle = ctx.plugin().bundles().get(name);
            boolean active = bundle != null && matchesCurrent(bundle);
            set(BUNDLE_SLOTS[i], bundleTile(bundle, name, active),
                    click -> apply(viewer, () -> ctx.management().applyBundle(name)));
        }

        set(49, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    /** Every loaded bundle name, sorted — any file dropped into bundles/ shows up. */
    private @NotNull List<String> bundleNames() {
        List<String> names = new ArrayList<>(ctx.plugin().bundles().keySet());
        names.sort(null);
        return names;
    }

    private @NotNull ItemStack bundleTile(RulesBundle bundle, @NotNull String name, boolean active) {
        String material = BUNDLE_ICONS.getOrDefault(name, "PAPER");
        Icon icon = Buttons.title(material,
                bundle != null ? bundle.displayName() : name,
                active ? Brand.SUCCESS : Brand.PRIMARY);
        icon.lore(Component.text("(" + name + ")", Brand.MUTED));
        if (bundle != null) {
            if (!bundle.description().isEmpty()) {
                icon.blank();
                Buttons.wrap(bundle.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            }
            icon.blank();
            preview(icon, bundle);
        }
        icon.blank();
        icon.lore(active
                ? Component.text("● ACTIVE — current config matches", Brand.SUCCESS)
                        .decoration(TextDecoration.BOLD, true)
                : Component.text("▸ Click to apply server-wide", Brand.SECONDARY));
        return icon.glow(active).build();
    }

    /** The read-only summary: how many modules the bundle turns on/off and what it selects. */
    private void preview(@NotNull Icon icon, @NotNull RulesBundle bundle) {
        long on = bundle.modules().values().stream().filter(Boolean::booleanValue).count();
        long off = bundle.modules().size() - on;
        icon.lore(kv("Modules", on + " on · " + off + " off"));
        bundle.knockbackProfile().ifPresent(profile -> icon.lore(kv("Knockback", profile)));
        bundle.effectsPreset().ifPresent(preset -> icon.lore(kv("Effects", preset)));
    }

    /**
     * Whether the live config already equals everything this bundle would set —
     * every module toggle in the requested state, and (when the bundle names them)
     * the active knockback profile and effects preset. A read-only comparison
     * against the current {@link Snapshot}; a module key the bundle sets that is
     * not a known feature is treated as unmatched (the apply would reject it).
     */
    private boolean matchesCurrent(@NotNull RulesBundle bundle) {
        Snapshot snapshot = ctx.plugin().snapshot();
        for (Map.Entry<String, Boolean> module : bundle.modules().entrySet()) {
            Optional<Feature> feature = Feature.byModuleId(module.getKey());
            if (feature.isEmpty() || snapshot.enabled(feature.get()) != module.getValue()) {
                return false;
            }
        }
        if (bundle.knockbackProfile().isPresent()
                && !bundle.knockbackProfile().get().equals(snapshot.defaultProfile())) {
            return false;
        }
        return bundle.effectsPreset().isEmpty()
                || bundle.effectsPreset().get().equals(snapshot.selectedEffectsPreset());
    }

    /**
     * Boot self-test seam (mirrors {@link EffectsPresetMenu#selfTestIcons}): the
     * load-bearing icons rendered with no viewer, so the tester can prove the
     * Adventure/String sink path classloads on legacy servers. Returns only Bukkit
     * types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        Icon header = Buttons.title("CHEST", "Combat Presets");
        icons.add(header.build());
        for (String name : bundleNames()) {
            RulesBundle bundle = ctx.plugin().bundles().get(name);
            icons.add(bundleTile(bundle, name, bundle != null && matchesCurrent(bundle)));
        }
        icons.add(Buttons.back());
        return icons;
    }

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }
}
