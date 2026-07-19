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

    /** Feature cards ride content rows of at most this many, chunked from row 1 down. */
    private static final int CARDS_PER_ROW = 7;

    /** Content cards begin on row 1 (slot base 9); each further row follows at +9. */
    private static final int FIRST_CONTENT_ROW_BASE = 9;

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
        return rowsFor(family);
    }

    /**
     * Inventory rows this family's screen needs: a header row, one content row per
     * chunk of {@link #CARDS_PER_ROW} feature cards (DAMAGE's ten and SUSTAIN's
     * eight ride two rows; every other family fits one), the gallery hero row on
     * the KNOCKBACK / FEEDBACK families, and the back row. {@code LayoutTest} pins
     * that this never exceeds the six-row inventory ceiling for any live family.
     */
    static int rowsFor(@NotNull Family family) {
        return 1 + contentRowCount(family) + (hasHero(family) ? 1 : 0) + 1;
    }

    /** Content rows a family fills — its non-infrastructure cards chunked at {@link #CARDS_PER_ROW}. */
    static int contentRowCount(@NotNull Family family) {
        int cards = DashboardModel.entries(family).size();
        return Math.max(1, (cards + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());
        Snapshot snapshot = ctx.plugin().snapshot();

        set(HEADER_SLOT, headerCard());

        // Cards flow across one content row per seven features, chunked from row 1
        // down — so DAMAGE's ten and SUSTAIN's eight ride two rows instead of
        // overflowing the seven-wide frame (which Layout.contentRow rejects).
        List<Feature> entries = DashboardModel.entries(family);
        for (int i = 0; i < entries.size(); i += CARDS_PER_ROW) {
            int rowBase = FIRST_CONTENT_ROW_BASE + (i / CARDS_PER_ROW) * 9;
            int count = Math.min(CARDS_PER_ROW, entries.size() - i);
            int[] slots = Layout.contentRow(rowBase, count);
            for (int j = 0; j < count; j++) {
                Feature feature = entries.get(i + j);
                boolean enabled = ctx.plugin().featureActive(feature);
                set(slots[j], moduleCard(feature, enabled), cardClick(viewer, feature, enabled));
            }
        }

        int contentRows = contentRowCount(family);
        if (hasHero(family)) {
            PresetKind kind = heroKind();
            set((1 + contentRows) * 9 + 4, heroTile(kind, snapshot),
                    click -> navigate(viewer, new PresetGalleryMenu(ctx, kind)));
        }
        int backRow = 1 + contentRows + (hasHero(family) ? 1 : 0);
        Category parent = Category.of(family);
        set(backRow * 9 + 4, Buttons.back(parent.displayName()),
                click -> navigate(viewer, new CategoryMenu(ctx, parent)));
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
        if (hasHero(family)) {
            icons.add(heroTile(heroKind(), ctx.plugin().snapshot()));
        }
        icons.add(Buttons.back(Category.of(family).displayName()));
        return icons;
    }

    private static boolean hasHero(@NotNull Family family) {
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
