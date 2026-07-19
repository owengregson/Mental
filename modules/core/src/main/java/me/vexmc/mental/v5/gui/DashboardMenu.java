package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The management home screen and single GUI entry point — deliberately five
 * tiles, no more. A live status plate up top, the five {@link Category} tiles in
 * the centre (three over two, from {@link DashboardModel#categoryRows()}), and
 * exactly two flanking buttons beside the lower row: Combat Presets on the left,
 * Close on the right. Everything else the home used to carry (ten family tiles,
 * compatibility, debug, reload) moved one level down into the category screens.
 *
 * <p>The categories come straight from {@link DashboardModel#categoryRows()} and
 * each opens its {@link CategoryMenu} — <em>the catalog is the descriptor
 * registry</em>, so a new {@link Feature} (in an existing family) surfaces with
 * no edit to any GUI class, and {@code DashboardModelTest} pins that every
 * {@link Family} stays reachable through exactly one category.</p>
 */
public final class DashboardMenu extends Menu {

    /** The status plate's slot — row 0, centred. */
    private static final int STATUS_SLOT = 4;

    /** The two centre category rows' base slots (rows 1 and 2). */
    private static final int[] CATEGORY_ROW_BASES = {9, 18};

    /** The two flanking buttons — the lower category row's frame edges. */
    private static final int PRESETS_SLOT = 18;
    private static final int CLOSE_SLOT = 26;

    public DashboardMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY).decoration(TextDecoration.BOLD, true);
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(Palette.home().pane());
        set(STATUS_SLOT, statusPlate());

        List<List<Category>> rows = DashboardModel.categoryRows();
        for (int r = 0; r < rows.size() && r < CATEGORY_ROW_BASES.length; r++) {
            List<Category> row = rows.get(r);
            int[] slots = Layout.contentRow(CATEGORY_ROW_BASES[r], row.size());
            for (int i = 0; i < slots.length; i++) {
                Category category = row.get(i);
                set(slots[i], categoryTile(category),
                        click -> navigate(viewer, new CategoryMenu(ctx, category)));
            }
        }

        set(PRESETS_SLOT, presetsButton(),
                click -> navigate(viewer, new CombatPresetsMenu(ctx)));
        set(CLOSE_SLOT, closeButton(), click -> viewer.closeInventory());
    }

    /**
     * Boot self-test seam: the home's load-bearing icons rendered to
     * {@link ItemStack}s with no viewer and no scheduler hop. Every one runs the
     * {@link Icon} → {@code TextPort} → {@code setDisplayName}/{@code setLore(String)}
     * sink path, so the tester (which cannot itself reference the relocated
     * Adventure types) can prove that path classloads on legacy servers by calling
     * a method whose signature is pure Bukkit. Returns only Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        icons.add(statusPlate());
        for (Category category : Category.values()) {
            icons.add(categoryTile(category));
        }
        icons.add(presetsButton());
        icons.add(closeButton());
        return icons;
    }

    /** A category's home tile: its blurb, its section list, and the open hint. */
    private @NotNull ItemStack categoryTile(@NotNull Category category) {
        Icon tile = Buttons.title(category.iconName(), category.displayName(),
                Palette.category(category).accent());
        Buttons.wrap(category.blurb()).forEach(line -> tile.lore(line, Brand.MUTED));
        tile.blank();
        tile.lore(Buttons.kv("Sections", sectionList(category), Palette.category(category).accent()));
        tile.lore(Component.text("▸ Click to open", Brand.SECONDARY));
        return tile.build();
    }

    /** The tile's one-line contents preview — family names, or the system trio. */
    private static @NotNull String sectionList(@NotNull Category category) {
        if (category == Category.SYSTEM) {
            return "Compatibility · Debug · Reload";
        }
        StringBuilder names = new StringBuilder();
        for (Family family : category.families()) {
            if (names.length() > 0) {
                names.append(" · ");
            }
            names.append(family.displayName());
        }
        return names.toString();
    }

    private @NotNull ItemStack statusPlate() {
        int total = 0;
        int enabled = 0;
        for (Feature feature : Feature.values()) {
            if (feature.infrastructure()) {
                continue;
            }
            total++;
            if (ctx.plugin().featureActive(feature)) {
                enabled++;
            }
        }
        Palette.Theme home = Palette.home();
        Icon plate = Buttons.title("NETHER_STAR", "Mental");
        plate.lore("Latency-compensated 1.7.10 combat", Brand.MUTED);
        plate.blank();
        plate.lore(Buttons.kv("Version", ctx.plugin().getDescription().getVersion(), home.accent()));
        plate.lore(Buttons.kv("Server", ctx.plugin().environment().describe(), home.accent()));
        plate.lore(Buttons.kv("Scheduling", ctx.plugin().scheduling().describe(), home.accent()));
        plate.lore(Buttons.kv("Knockback", ctx.plugin().snapshot().defaultProfile(), home.accent()));
        plate.lore(Buttons.kv("Effects", ctx.plugin().snapshot().selectedEffectsPreset(), home.accent()));
        plate.lore(Buttons.kv("Modules", enabled + " / " + total + " active", home.accent()));
        plate.lore(Buttons.kv("Anticheat", ctx.plugin().snapshot().anticheat().mode()
                .name().toLowerCase(Locale.ROOT), home.accent()));
        return plate.build();
    }

    private @NotNull ItemStack presetsButton() {
        return Buttons.nav("CHEST", "Combat Presets",
                "Apply a whole ruleset at once — CT8c, the classic 1.7 feel, or vanilla.");
    }

    private @NotNull ItemStack closeButton() {
        return Icon.of(MenuMaterials.of("BARRIER"))
                .name(Component.text("Close", Brand.FAILURE).decoration(TextDecoration.BOLD, true))
                .lore("Close this menu.", Brand.MUTED)
                .build();
    }
}
