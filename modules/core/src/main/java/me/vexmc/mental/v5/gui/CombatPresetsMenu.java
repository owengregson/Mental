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
 * The Combat Presets picker on the redesigned chrome — the whole-ruleset macros, a
 * sibling to the {@link PresetGalleryMenu}. Lists every loaded {@code bundles/*.yml}
 * by stem; clicking a tile applies it server-wide through {@code
 * Management.applyBundle}, which validates the whole bundle, writes its batch of
 * module/profile/preset keys into the machine overlay in one persist, and reloads
 * once. A bundle is a macro, not a mode — applying one flips a whole ruleset at once
 * (the CT8c snapshot, the classic 1.7 feel, or Mental-transparent vanilla).
 *
 * <p>Because a bundle leaves no live "active bundle" state behind, the ACTIVE mark
 * is computed read-only: a bundle reads as active when the current config already
 * equals every toggle (and the profile/preset) it would set — so re-applying it is
 * a no-op. The tile preview is READ-ONLY: what the bundle turns on, and which
 * profile/preset it selects.</p>
 *
 * <p>Reached from the {@link DashboardMenu} Combat Presets tile; with no back stack,
 * Back returns there. The neutral {@link Palette#system()} chrome matches its
 * dashboard-nav siblings (Compatibility, Debug).</p>
 */
public final class CombatPresetsMenu extends Menu {

    /** Themed icon per shipped bundle; unknown (operator) bundles fall back to paper. */
    private static final Map<String, String> BUNDLE_ICONS = Map.of(
            "ct8c", "NETHERITE_SWORD",
            "signature", "NETHER_STAR",
            "vanilla", "GRASS_BLOCK");

    /** Bundle tiles ride content rows of at most seven, chunked from row 2 down. */
    private static final int TILES_PER_ROW = 7;
    private static final int FIRST_BUNDLE_ROW_BASE = 18;
    private static final int HEADER_SLOT = 4;
    private static final int BACK_SLOT = 49;

    private final Palette.Theme theme = Palette.system();

    public CombatPresetsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text("Combat Presets", theme.accent()));
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());

        Icon header = Buttons.title("CHEST", "Combat Presets", theme.accent());
        Buttons.wrap("One preset applies a whole ruleset at once — a batch of module"
                + " toggles, plus the knockback profile and effects tune it needs."
                + " It is a macro: applying one leaves the server in a fully-known state.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(HEADER_SLOT, header.build());

        // Bundles flow across up to two centred content rows (seven per row) — the
        // three shipped bundles centre on row 2; an operator's extra bundles spill
        // to row 3. Beyond fourteen, the surplus falls outside the six-row frame and
        // Menu.set drops it (a bundle count that high is not a real deployment).
        List<String> names = bundleNames();
        for (int i = 0; i < names.size(); i += TILES_PER_ROW) {
            int rowBase = FIRST_BUNDLE_ROW_BASE + (i / TILES_PER_ROW) * 9;
            int count = Math.min(TILES_PER_ROW, names.size() - i);
            int[] slots = Layout.contentRow(rowBase, count);
            for (int j = 0; j < count; j++) {
                String name = names.get(i + j);
                RulesBundle bundle = ctx.plugin().bundles().get(name);
                boolean active = bundle != null && matchesCurrent(bundle);
                set(slots[j], bundleTile(bundle, name, active),
                        click -> apply(viewer, () -> ctx.management().applyBundle(name)));
            }
        }

        set(BACK_SLOT, Buttons.back("the home screen"), click -> navigate(viewer, new DashboardMenu(ctx)));
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
        icon.lore(Buttons.kv("Modules", on + " on · " + off + " off", theme.accent()));
        bundle.knockbackProfile().ifPresent(
                profile -> icon.lore(Buttons.kv("Knockback", profile, theme.accent())));
        bundle.effectsPreset().ifPresent(
                preset -> icon.lore(Buttons.kv("Effects", preset, theme.accent())));
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
     * Boot self-test seam: the load-bearing icons rendered with no viewer, so the
     * tester can prove the Adventure/String sink path classloads on legacy servers.
     * Returns only Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        icons.add(Buttons.title("CHEST", "Combat Presets", theme.accent()).build());
        for (String name : bundleNames()) {
            RulesBundle bundle = ctx.plugin().bundles().get(name);
            icons.add(bundleTile(bundle, name, bundle != null && matchesCurrent(bundle)));
        }
        icons.add(Buttons.back("the home screen"));
        return icons;
    }
}
