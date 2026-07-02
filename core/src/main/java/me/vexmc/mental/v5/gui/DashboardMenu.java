package me.vexmc.mental.v5.gui;

import java.util.List;
import java.util.Locale;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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

    /** Row-2 slots the (up to nine) family section tiles centre within. */
    private static final int SECTION_ROW_BASE = 18;

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
        set(4, statusPlate());

        List<Family> sections = DashboardModel.sections();
        int start = SECTION_ROW_BASE + Math.max(0, (9 - sections.size()) / 2);
        for (int i = 0; i < sections.size() && start + i <= 26; i++) {
            Family family = sections.get(i);
            set(start + i, Buttons.nav(family.iconName(), family.displayName(), family.blurb()),
                    click -> navigate(viewer, new FamilyMenu(ctx, family)));
        }

        set(30, Buttons.nav("COMPASS", "Compatibility",
                "Anticheat posture and OldCombatMechanics coordination."),
                click -> navigate(viewer, new CompatibilityMenu(ctx)));
        set(32, Buttons.nav("REPEATER", "Debug",
                "Verbose logging channels for operators."),
                click -> navigate(viewer, new DebugMenu(ctx)));

        set(48, reloadButton(), click -> apply(viewer, () -> ctx.management().reload()));
        set(50, closeButton(), click -> viewer.closeInventory());
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
        boolean ocmPresent = Bukkit.getPluginManager().getPlugin("OldCombatMechanics") != null;
        String ocm = ctx.plugin().snapshot().ocmCoordination().name().toLowerCase(Locale.ROOT)
                + (ocmPresent ? " (present)" : " (absent)");

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
        plate.lore(kv("OCM", ocm));
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
