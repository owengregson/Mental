package me.vexmc.mental.v5.gui;

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

    /** The two rows the family section tiles balance across (rows 2 and 3). */
    private static final int SECTION_ROW_TOP = 18;
    private static final int SECTION_ROW_BOTTOM = 27;

    /**
     * At or below this many sections a single centred row reads cleaner than a
     * split; above it the tiles balance across two rows so a crowded row never
     * cramps.
     */
    private static final int SINGLE_ROW_LIMIT = 5;

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
        set(STATUS_SLOT, statusPlate());

        drawSections(viewer, DashboardModel.sections());

        set(COMPAT_SLOT, Buttons.nav("COMPASS", "Compatibility",
                "Anticheat posture."),
                click -> navigate(viewer, new CompatibilityMenu(ctx)));
        set(DEBUG_SLOT, Buttons.nav("REPEATER", "Debug",
                "Verbose logging channels for operators."),
                click -> navigate(viewer, new DebugMenu(ctx)));

        set(RELOAD_SLOT, reloadButton(), click -> apply(viewer, () -> ctx.management().reload()));
        set(CLOSE_SLOT, closeButton(), click -> viewer.closeInventory());
    }

    /**
     * Lays the family navigation tiles out balanced and centred: a single centred
     * row for a small set, or — for a larger one — two centred rows in a downward V
     * (funnel). The TOP row takes the wider share and the bottom row the narrower
     * remainder, so the two centred rows taper inward rather than sitting as a full
     * rectangular block: a block would need a count that fills both rows to the same
     * width, which the section count never reaches, so the shape opens into a V
     * instead. Every {@link Family} still renders — the catalog is the registry, so
     * a new family surfaces here with no edit to this method.
     */
    private void drawSections(@NotNull Player viewer, @NotNull List<Family> sections) {
        int count = sections.size();
        if (count == 0) {
            return;
        }
        if (count <= SINGLE_ROW_LIMIT) {
            placeSectionRow(viewer, sections, 0, count, SECTION_ROW_TOP);
            return;
        }
        // The V-split: the top row takes the wider half (floor(count/2) + 1), the
        // bottom the narrower remainder — the two centred rows funnel inward. For the
        // eight families that is 5 over 3, so SUSTAIN rides up into the top row.
        int top = count / 2 + 1;
        placeSectionRow(viewer, sections, 0, top, SECTION_ROW_TOP);
        placeSectionRow(viewer, sections, top, count, SECTION_ROW_BOTTOM);
    }

    /** Centres {@code sections[from, to)} within the nine-wide row at {@code rowBase}. */
    private void placeSectionRow(
            @NotNull Player viewer, @NotNull List<Family> sections, int from, int to, int rowBase) {
        int count = to - from;
        int start = rowBase + Math.max(0, (9 - count) / 2);
        for (int i = 0; i < count && start + i <= rowBase + 8; i++) {
            Family family = sections.get(from + i);
            set(start + i, Buttons.nav(family.iconName(), family.displayName(), family.blurb()),
                    click -> navigate(viewer, new FamilyMenu(ctx, family)));
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
        Icon plate = Buttons.title("NETHER_STAR", "Mental");
        plate.lore("Latency-compensated 1.7.10 combat", Brand.MUTED);
        plate.blank();
        plate.lore(kv("Version", ctx.plugin().getDescription().getVersion()));
        plate.lore(kv("Server", ctx.plugin().environment().describe()));
        plate.lore(kv("Scheduling", ctx.plugin().scheduling().describe()));
        plate.lore(kv("Knockback", ctx.plugin().snapshot().defaultProfile()));
        plate.lore(kv("Modules", enabled + " / " + total + " active"));
        plate.lore(kv("Anticheat", ctx.plugin().snapshot().anticheat().mode()
                .name().toLowerCase(Locale.ROOT)));
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

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }
}
