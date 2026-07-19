package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * One home {@link Category} as a short hall of section tiles — the intermediate
 * layer that keeps the home at five icons. A family category renders one tile
 * per {@link Family} it fronts (each with its live active-module count), opening
 * the unchanged {@link FamilyMenu}. The {@link Category#SYSTEM} category renders
 * the three system tiles instead: the compatibility and debug screens plus the
 * reload action, which moved here off the crowded home.
 *
 * <p>Three rows always — header, one content row of at most three tiles (pinned
 * by {@code LayoutTest} against the {@link Category} lists), back. The colour
 * identity comes from {@link Palette#category(Category)}.</p>
 */
public final class CategoryMenu extends Menu {

    /** The category header card — row 0, centred. */
    private static final int HEADER_SLOT = 4;

    /** The single content row's base slot (row 1). */
    private static final int CONTENT_ROW_BASE = 9;

    /** The back button — row 2, centred. */
    private static final int BACK_SLOT = 22;

    private final Category category;
    private final Palette.Theme theme;

    public CategoryMenu(@NotNull MenuContext ctx, @NotNull Category category) {
        super(ctx);
        this.category = category;
        this.theme = Palette.category(category);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text(category.displayName(), theme.accent()));
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());
        set(HEADER_SLOT, headerCard());

        List<Tile> tiles = new ArrayList<>();
        if (category == Category.SYSTEM) {
            tiles.add(Tile.of(Buttons.nav("COMPASS", "Compatibility",
                    "Anticheat posture — how Mental yields to a prediction anticheat."),
                    click -> navigate(viewer, new CompatibilityMenu(ctx))));
            tiles.add(Tile.of(Buttons.nav("REPEATER", "Debug",
                    "Verbose logging channels, streamed to console or your own chat."),
                    click -> navigate(viewer, new DebugMenu(ctx))));
            tiles.add(Tile.of(reloadButton(),
                    click -> apply(viewer, () -> ctx.management().reload())));
        } else {
            for (Family family : category.families()) {
                tiles.add(Tile.of(familyTile(family),
                        click -> navigate(viewer, new FamilyMenu(ctx, family))));
            }
        }
        int[] slots = Layout.contentRow(CONTENT_ROW_BASE, tiles.size());
        for (int i = 0; i < slots.length; i++) {
            set(slots[i], tiles.get(i).item(), tiles.get(i).onClick());
        }

        set(BACK_SLOT, Buttons.back("the home screen"),
                click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    /**
     * Boot self-test seam: the header, every section tile (with live enablement
     * counts on the family categories), and back — pure Bukkit stacks with no
     * viewer and no scheduler hop, mirroring {@code FamilyMenu.selfTestIcons}.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        icons.add(headerCard());
        if (category == Category.SYSTEM) {
            icons.add(reloadButton());
        } else {
            for (Family family : category.families()) {
                icons.add(familyTile(family));
            }
        }
        icons.add(Buttons.back("the home screen"));
        return icons;
    }

    private @NotNull ItemStack headerCard() {
        Icon header = Buttons.title(category.iconName(), category.displayName(), theme.accent());
        Buttons.wrap(category.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        if (category != Category.SYSTEM) {
            int total = 0;
            int active = 0;
            for (Family family : category.families()) {
                for (Feature feature : DashboardModel.entries(family)) {
                    total++;
                    if (ctx.plugin().featureActive(feature)) {
                        active++;
                    }
                }
            }
            header.blank();
            header.lore(Buttons.kv("Active", active + " / " + total + " modules", theme.accent()));
        }
        return header.build();
    }

    /** A family's section tile: its live active count plus the open hint. */
    private @NotNull ItemStack familyTile(@NotNull Family family) {
        List<Feature> entries = DashboardModel.entries(family);
        int active = 0;
        for (Feature feature : entries) {
            if (ctx.plugin().featureActive(feature)) {
                active++;
            }
        }
        Icon tile = Buttons.title(family.iconName(), family.displayName(), Brand.TEXT);
        Buttons.wrap(family.blurb()).forEach(line -> tile.lore(line, Brand.MUTED));
        tile.blank();
        tile.lore(Buttons.kv("Active", active + " / " + entries.size() + " modules", theme.accent()));
        tile.lore(Component.text("▸ Click to open", Brand.SECONDARY));
        return tile.build();
    }

    private @NotNull ItemStack reloadButton() {
        return Buttons.title("LIME_DYE", "Reload configuration", Brand.SUCCESS)
                .lore("Re-read every file and converge modules.", Brand.MUTED)
                .lore("Applied atomically — no hit reads a half-config.", Brand.MUTED)
                .blank()
                .lore(Component.text("▸ Click to reload", Brand.SECONDARY))
                .build();
    }
}
