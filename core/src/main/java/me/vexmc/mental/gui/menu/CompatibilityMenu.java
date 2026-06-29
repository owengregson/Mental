package me.vexmc.mental.gui.menu;

import me.vexmc.mental.config.AnticheatMode;
import me.vexmc.mental.config.OcmCoordination;
import me.vexmc.mental.gui.Icon;
import me.vexmc.mental.gui.Menu;
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Coexistence posture: how Mental behaves alongside movement-prediction
 * anticheats, and how it splits combat with OldCombatMechanics. Both are
 * always-on compat modules driven by config enums (not {@code modules.*}), so
 * these tiles cycle the enum, write config.yml, and reload.
 */
public final class CompatibilityMenu extends Menu {

    public CompatibilityMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Compatibility", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        set(20, anticheatTile(), click -> {
            AnticheatMode next = cycle(ctx.services().config().anticheat().mode());
            apply(viewer, () -> ctx.management().setAnticheatMode(next));
        });
        set(24, ocmTile(), click -> {
            OcmCoordination next = ctx.services().config().compatibility().oldCombatMechanics()
                    == OcmCoordination.AUTO ? OcmCoordination.IGNORE : OcmCoordination.AUTO;
            apply(viewer, () -> ctx.management().setOcmCoordination(next));
        });
        set(31, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull org.bukkit.inventory.ItemStack anticheatTile() {
        AnticheatMode mode = ctx.services().config().anticheat().mode();
        Icon icon = Buttons.title("IRON_BARS", "Anticheat coexistence");
        Buttons.wrap("How Mental coexists with movement-prediction anticheats "
                + "(GrimAC, Vulcan). AUTO suppresses pre-sent velocity only while one is installed.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        option(icon, "auto", mode == AnticheatMode.AUTO, "coordinate only when one is present");
        option(icon, "force-safe", mode == AnticheatMode.FORCE_SAFE, "always behave as if one is present");
        option(icon, "off", mode == AnticheatMode.OFF, "never adjust");
        icon.blank();
        icon.lore(Component.text("▸ Click to cycle", Brand.SECONDARY));
        return icon.build();
    }

    private @NotNull org.bukkit.inventory.ItemStack ocmTile() {
        OcmCoordination mode = ctx.services().config().compatibility().oldCombatMechanics();
        boolean present = Bukkit.getPluginManager().getPlugin("OldCombatMechanics") != null;
        Icon icon = Buttons.title("DIAMOND_SWORD", "OldCombatMechanics");
        Buttons.wrap("OCM owns combat rules, Mental owns knockback and hit delivery. "
                + "AUTO yields the mechanics OCM is configured to handle.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(kv("Installed", present ? "yes" : "no"));
        option(icon, "auto", mode == OcmCoordination.AUTO, "coordinate (recommended)");
        option(icon, "ignore", mode == OcmCoordination.IGNORE, "pretend OCM is absent");
        icon.blank();
        icon.lore(Component.text("▸ Click to toggle", Brand.SECONDARY));
        return icon.build();
    }

    private static void option(@NotNull Icon icon, @NotNull String name, boolean selected, @NotNull String note) {
        icon.lore(Component.text()
                .append(Component.text((selected ? "● " : "○ ") + name,
                        selected ? Brand.SUCCESS : Brand.MUTED).decoration(TextDecoration.BOLD, selected))
                .append(Component.text("  — " + note, Brand.MUTED))
                .build());
    }

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }

    private static @NotNull AnticheatMode cycle(@NotNull AnticheatMode mode) {
        AnticheatMode[] values = AnticheatMode.values();
        return values[(mode.ordinal() + 1) % values.length];
    }
}
