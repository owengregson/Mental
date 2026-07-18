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
 * The management home screen and single GUI entry point. A live status plate, one
 * navigation tile per dashboard {@link Family} section, the always-on
 * compatibility and debug screens, and the global actions (reload, close).
 *
 * <p>The section tiles come straight from {@link DashboardModel#sections()} —
 * <em>the catalog is the descriptor registry</em>, so adding a new {@link
 * Feature} (in an existing family) or a new {@code Family} surfaces here with no
 * edit to this class. Each section opens the generic {@link FamilyMenu}; the
 * knockback family's screen additionally carries the global profile picker.</p>
 */
public final class DashboardMenu extends Menu {

    /** The status plate's slot — row 0, centred. */
    private static final int STATUS_SLOT = 4;

    /** The first-slot of each of the three grouped family rows (rows 1–3). */
    private static final int[] FAMILY_ROW_BASES = {9, 18, 27};

    /** Row-4 nav tiles (compatibility / debug), aligned above reload / close. */
    private static final int COMPAT_SLOT = 39;
    private static final int DEBUG_SLOT = 41;

    /** Row-5 global actions, mirrored around the centre column. */
    private static final int RELOAD_SLOT = 48;
    private static final int CLOSE_SLOT = 50;

    public DashboardMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY).decoration(TextDecoration.BOLD, true);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(Palette.home().pane());
        set(STATUS_SLOT, statusPlate());

        drawFamilyRows(viewer);

        set(COMPAT_SLOT, Buttons.nav("COMPASS", "Compatibility",
                "Anticheat posture — how Mental yields to a prediction anticheat."),
                click -> navigate(viewer, new CompatibilityMenu(ctx)));
        set(DEBUG_SLOT, Buttons.nav("REPEATER", "Debug",
                "Verbose logging channels, streamed to console or your own chat."),
                click -> navigate(viewer, new DebugMenu(ctx)));

        set(RELOAD_SLOT, reloadButton(), click -> apply(viewer, () -> ctx.management().reload()));
        set(CLOSE_SLOT, closeButton(), click -> viewer.closeInventory());
    }

    /**
     * Renders the family tiles grouped into centred rows off
     * {@link DashboardModel#homeRows()} — the engine, the era rules, then the
     * cosmetic/loot pair. Every tile opens the generic {@link FamilyMenu} for its
     * family (the KNOCKBACK and FEEDBACK screens carry their preset-gallery hero
     * there); adding a family means adding it to a home row (pinned by
     * {@code DashboardModelTest}).
     */
    private void drawFamilyRows(@NotNull Player viewer) {
        List<List<Family>> rows = DashboardModel.homeRows();
        for (int r = 0; r < rows.size() && r < FAMILY_ROW_BASES.length; r++) {
            List<Tile> tiles = new ArrayList<>();
            for (Family family : rows.get(r)) {
                tiles.add(Tile.of(
                        Buttons.nav(family.iconName(), family.displayName(), family.blurb()),
                        click -> navigate(viewer, new FamilyMenu(ctx, family))));
            }
            placeCentered(FAMILY_ROW_BASES[r], tiles);
        }
    }

    /**
     * Boot self-test seam: the dashboard's load-bearing icons rendered to
     * {@link ItemStack}s with no viewer and no scheduler hop. Every one runs the
     * {@link Icon} → {@code TextPort} → {@code setDisplayName}/{@code setLore(String)}
     * sink path, so the tester (which cannot itself reference the relocated
     * Adventure types) can prove that path classloads on legacy servers by calling
     * a method whose signature is pure Bukkit. Returns only Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        return List.of(statusPlate(), reloadButton(), closeButton());
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

    private @NotNull ItemStack reloadButton() {
        return Buttons.title("LIME_DYE", "Reload configuration", Brand.SUCCESS)
                .lore("Re-read every file and converge modules.", Brand.MUTED)
                .lore("Applied atomically — no hit reads a half-config.", Brand.MUTED)
                .blank()
                .lore(Component.text("▸ Click to reload", Brand.SECONDARY))
                .build();
    }

    private @NotNull ItemStack closeButton() {
        return Icon.of(MenuMaterials.of("BARRIER"))
                .name(Component.text("Close", Brand.FAILURE).decoration(TextDecoration.BOLD, true))
                .lore("Close this menu.", Brand.MUTED)
                .build();
    }
}
