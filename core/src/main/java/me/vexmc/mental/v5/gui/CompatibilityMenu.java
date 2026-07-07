package me.vexmc.mental.v5.gui;

import java.util.Locale;
import me.vexmc.mental.v5.config.AnticheatMode;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Coexistence posture: how Mental behaves alongside movement-prediction
 * anticheats. It is not a {@code modules.*} toggle — it is always-on
 * infrastructure driven by a config enum ({@code anticheat.mode}).
 *
 * <p>The tile cycles the enum by writing its <em>machine-overlay</em> key and
 * reloading through {@link me.vexmc.mental.v5.manage.Management}; the human YAML
 * is never re-serialized (Task 6.0 constraint 2). The overlay wins over the file,
 * so the reload picks up the change atomically.</p>
 */
public final class CompatibilityMenu extends Menu {

    private static final String ANTICHEAT_KEY = "anticheat.mode";

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
        set(22, anticheatTile(), click -> {
            AnticheatMode next = cycle(ctx.plugin().snapshot().anticheat().mode());
            apply(viewer, () -> {
                ctx.plugin().overlaySet(ANTICHEAT_KEY, enumKey(next));
                ctx.management().reload();
            });
        });
        set(31, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull ItemStack anticheatTile() {
        AnticheatMode mode = ctx.plugin().snapshot().anticheat().mode();
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

    private static void option(@NotNull Icon icon, @NotNull String name, boolean selected, @NotNull String note) {
        icon.lore(Component.text()
                .append(Component.text((selected ? "● " : "○ ") + name,
                        selected ? Brand.SUCCESS : Brand.MUTED).decoration(TextDecoration.BOLD, selected))
                .append(Component.text("  — " + note, Brand.MUTED))
                .build());
    }

    private static @NotNull AnticheatMode cycle(@NotNull AnticheatMode mode) {
        AnticheatMode[] values = AnticheatMode.values();
        return values[(mode.ordinal() + 1) % values.length];
    }

    /** The config token for an enum value — lower-cased, dash-separated (config.yml style). */
    private static @NotNull String enumKey(@NotNull Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
