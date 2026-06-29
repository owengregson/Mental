package me.vexmc.mental.gui.menu;

import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.gui.Icon;
import me.vexmc.mental.gui.Materials;
import me.vexmc.mental.gui.Menu;
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.module.compensation.LatencyCompensationModule;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The management home screen and single GUI entry point. A live status plate,
 * one navigation tile per section, and the global actions (reload, your ping,
 * close). Knockback opens its own bespoke screen; the five module families open
 * the generic {@link ModuleCategoryMenu}; compatibility and debug open theirs.
 */
public final class DashboardMenu extends Menu {

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

        // Two balanced rows of four navigation tiles.
        set(19, Buttons.nav("PISTON", "Knockback",
                "Choose the server-wide knockback feel; toggle the sources."),
                click -> navigate(viewer, new KnockbackMenu(ctx)));
        int[] slots = {21, 23, 25, 28, 30};
        for (int i = 0; i < Catalog.CATEGORIES.size() && i < slots.length; i++) {
            Catalog.Category category = Catalog.CATEGORIES.get(i);
            set(slots[i], Buttons.nav(category.navMaterial(), category.title(), category.navBlurb()),
                    click -> navigate(viewer, new ModuleCategoryMenu(ctx, category)));
        }
        set(32, Buttons.nav("COMPASS", "Compatibility",
                "Anticheat posture and OldCombatMechanics coordination."),
                click -> navigate(viewer, new CompatibilityMenu(ctx)));
        set(34, Buttons.nav("REPEATER", "Debug",
                "Verbose logging channels and in-game output."),
                click -> navigate(viewer, new DebugMenu(ctx)));

        set(47, reloadButton(), click -> apply(viewer, () -> ctx.management().reload()));
        set(49, pingButton(viewer));
        set(51, closeButton(), click -> viewer.closeInventory());
    }

    private @NotNull org.bukkit.inventory.ItemStack statusPlate() {
        ModuleRegistry registry = ctx.services().plugin().modules();
        int total = 0;
        int enabled = 0;
        for (CombatModule module : registry.all()) {
            total++;
            if (module.active()) {
                enabled++;
            }
        }
        boolean ocmPresent = Bukkit.getPluginManager().getPlugin("OldCombatMechanics") != null;
        String ocm = ctx.services().config().compatibility().oldCombatMechanics()
                .name().toLowerCase(java.util.Locale.ROOT)
                + (ocmPresent ? " (present)" : " (absent)");

        Icon plate = Buttons.title("NETHER_STAR", "Mental");
        plate.lore("Latency-compensated 1.7.10 combat", Brand.MUTED);
        plate.blank();
        plate.lore(kv("Version", ctx.services().plugin().getDescription().getVersion()));
        plate.lore(kv("Server", ctx.services().environment().describe()));
        plate.lore(kv("Scheduling", ctx.services().scheduling().describe()));
        plate.lore(kv("Knockback", ctx.services().knockbackProfiles().defaultProfileName()));
        plate.lore(kv("Modules", enabled + " / " + total + " active"));
        plate.lore(kv("Anticheat", ctx.services().config().anticheat().mode()
                .name().toLowerCase(java.util.Locale.ROOT)));
        plate.lore(kv("OCM", ocm));
        return plate.build();
    }

    private @NotNull org.bukkit.inventory.ItemStack reloadButton() {
        return Buttons.title("LIME_DYE", "Reload configuration", Brand.SUCCESS)
                .lore("Re-read every file and converge modules.", Brand.MUTED)
                .lore("Applied atomically — no hit reads a half-config.", Brand.MUTED)
                .blank()
                .lore(Component.text("▸ Click to reload", Brand.SECONDARY))
                .build();
    }

    private @NotNull org.bukkit.inventory.ItemStack pingButton(@NotNull Player viewer) {
        Icon icon = Buttons.title("CLOCK", "Your ping", Brand.ACCENT);
        String measured = ctx.services().plugin().modules().byId("latency-compensation")
                .filter(LatencyCompensationModule.class::isInstance)
                .map(LatencyCompensationModule.class::cast)
                .map(module -> module.pingStats(viewer.getUniqueId()).pingMillis())
                .map(ping -> ping == null ? null : Math.round(ping) + " ms (probe)")
                .orElse(null);
        if (measured == null) {
            measured = viewer.getPing() + " ms (vanilla — no probe yet)";
        }
        return icon.lore(kv("Round trip", measured)).build();
    }

    private @NotNull org.bukkit.inventory.ItemStack closeButton() {
        return Icon.of(Materials.of("BARRIER"))
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
