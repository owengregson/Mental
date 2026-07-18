package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.preset.PresetCatalog;
import me.vexmc.mental.v5.preset.PresetInfo;
import me.vexmc.mental.v5.preset.PresetKind;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One dashboard {@link Family} as a wall of module cards — the single metaphor
 * for all ten families. Each card is one non-infrastructure {@link Feature} in
 * the family (from {@link DashboardModel#entries(Family)}): a left-click toggles
 * the module through {@link me.vexmc.mental.v5.manage.Management#setModuleEnabled}
 * and repaints; a right-click, when the feature has a destination, opens its
 * {@link SettingsMenu} — or, for the {@link Feature#KNOCKBACK} feature, the
 * knockback {@link PresetGalleryMenu}.
 *
 * <p>{@link Family#KNOCKBACK} and {@link Family#FEEDBACK} carry a fourth hero row:
 * a large tile into the preset gallery for their kind (knockback profiles /
 * combat-effects tunes). Every other family is a compact three-row card screen.
 * The colour identity comes from {@link Palette#of(Family)}.</p>
 */
public final class FamilyMenu extends Menu {

    /** The family header card — row 0, centred. */
    private static final int HEADER_SLOT = 4;

    /** The gallery hero tile — row 2 centre, on the KNOCKBACK / FEEDBACK screens only. */
    private static final int HERO_SLOT = 22;

    /** Back slot on the compact (3-row) form and the tall (4-row) form. */
    private static final int BACK_SLOT_COMPACT = 22;
    private static final int BACK_SLOT_TALL = 31;

    private final Family family;
    private final Palette.Theme theme;

    public FamilyMenu(@NotNull MenuContext ctx, @NotNull Family family) {
        super(ctx);
        this.family = family;
        this.theme = Palette.of(family);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text(family.displayName(), theme.accent()));
    }

    @Override
    protected int rows() {
        // The knockback and effects families carry a hero row into their preset
        // gallery; every other family is a compact three-row card screen.
        return hasHero() ? 4 : 3;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());
        Snapshot snapshot = ctx.plugin().snapshot();

        set(HEADER_SLOT, headerCard());

        List<Feature> entries = DashboardModel.entries(family);
        int[] slots = Layout.contentRow(9, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Feature feature = entries.get(i);
            boolean enabled = ctx.plugin().featureActive(feature);
            set(slots[i], moduleCard(feature, enabled), cardClick(viewer, feature, enabled));
        }

        if (hasHero()) {
            PresetKind kind = heroKind();
            set(HERO_SLOT, heroTile(kind, snapshot),
                    click -> navigate(viewer, new PresetGalleryMenu(ctx, kind)));
            set(BACK_SLOT_TALL, Buttons.back("the Dashboard"),
                    click -> navigate(viewer, new DashboardMenu(ctx)));
        } else {
            set(BACK_SLOT_COMPACT, Buttons.back("the Dashboard"),
                    click -> navigate(viewer, new DashboardMenu(ctx)));
        }
    }

    /**
     * Boot self-test seam: the header card, every module card (with live
     * enablement), the hero when present, and back — pure Bukkit stacks with no
     * viewer and no scheduler hop.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        icons.add(headerCard());
        for (Feature feature : DashboardModel.entries(family)) {
            icons.add(moduleCard(feature, ctx.plugin().featureActive(feature)));
        }
        if (hasHero()) {
            icons.add(heroTile(heroKind(), ctx.plugin().snapshot()));
        }
        icons.add(Buttons.back("the Dashboard"));
        return icons;
    }

    private boolean hasHero() {
        return family == Family.KNOCKBACK || family == Family.FEEDBACK;
    }

    private @NotNull PresetKind heroKind() {
        return family == Family.KNOCKBACK ? PresetKind.KNOCKBACK : PresetKind.EFFECTS;
    }

    private @NotNull ItemStack headerCard() {
        List<Feature> entries = DashboardModel.entries(family);
        int active = 0;
        for (Feature feature : entries) {
            if (ctx.plugin().featureActive(feature)) {
                active++;
            }
        }
        Icon header = Buttons.title(family.iconName(), family.displayName(), theme.accent());
        Buttons.wrap(family.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        header.blank();
        header.lore(Buttons.kv("Active", active + " / " + entries.size() + " modules", theme.accent()));
        return header.build();
    }

    private @NotNull ItemStack moduleCard(@NotNull Feature feature, boolean enabled) {
        return Buttons.moduleCard(feature.iconName(), feature.displayName(), theme.accent(),
                enabled, feature.blurb(), settingsHint(feature));
    }

    /** The right-click advertisement a card carries, or {@code null} when it is toggle-only. */
    private @Nullable String settingsHint(@NotNull Feature feature) {
        if (SettingsCatalog.pageFor(feature).isPresent()) {
            return "▸ Right-click to configure";
        }
        if (feature == Feature.KNOCKBACK) {
            return "▸ Right-click for the preset gallery";
        }
        return null;
    }

    private @NotNull Consumer<InventoryClickEvent> cardClick(
            @NotNull Player viewer, @NotNull Feature feature, boolean enabled) {
        return event -> {
            if (event.isRightClick()) {
                if (SettingsCatalog.pageFor(feature).isPresent()) {
                    navigate(viewer, new SettingsMenu(ctx, feature));
                    return;
                }
                if (feature == Feature.KNOCKBACK) {
                    navigate(viewer, new PresetGalleryMenu(ctx, PresetKind.KNOCKBACK));
                    return;
                }
            }
            apply(viewer, () -> ctx.management().setModuleEnabled(feature, !enabled));
        };
    }

    private @NotNull ItemStack heroTile(@NotNull PresetKind kind, @NotNull Snapshot snapshot) {
        String name = kind == PresetKind.KNOCKBACK ? "Knockback Preset Gallery" : "Effects Preset Gallery";
        Icon hero = Buttons.title(kind.iconName(), name, theme.accent());
        Buttons.wrap(kind.blurb()).forEach(line -> hero.lore(line, Brand.MUTED));
        hero.blank();
        PresetInfo active = PresetCatalog.info(kind, PresetCatalog.selected(kind, snapshot), snapshot);
        hero.lore(Buttons.kv("Active", active.displayName(), theme.accent()));
        hero.lore(Component.text("▸ Click to browse", Brand.SECONDARY));
        return hero.build();
    }
}
