package me.vexmc.mental.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * Centralised visual identity. Every user-facing line carries the same
 * prefix so operators can attribute output at a glance.
 */
public final class Brand {

    public static final NamedTextColor PRIMARY = NamedTextColor.GOLD;
    public static final NamedTextColor SECONDARY = NamedTextColor.YELLOW;
    public static final NamedTextColor TEXT = NamedTextColor.WHITE;
    public static final NamedTextColor MUTED = NamedTextColor.GRAY;
    public static final NamedTextColor SUCCESS = NamedTextColor.GREEN;
    public static final NamedTextColor FAILURE = NamedTextColor.RED;
    public static final NamedTextColor ACCENT = NamedTextColor.AQUA;

    private static final Component PREFIX =
            Component.text("Mental", PRIMARY, TextDecoration.BOLD);

    private Brand() {}

    public static @NotNull Component prefix() {
        return PREFIX;
    }

    public static @NotNull Component line(@NotNull Component body) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" » ", MUTED))
                .append(body)
                .build();
    }

    public static @NotNull Component line(@NotNull String body) {
        return line(Component.text(body, TEXT));
    }

    public static @NotNull Component success(@NotNull String body) {
        return line(Component.text(body, SUCCESS));
    }

    public static @NotNull Component failure(@NotNull String body) {
        return line(Component.text(body, FAILURE));
    }

    public static @NotNull Component info(@NotNull String body) {
        return line(Component.text(body, TEXT));
    }
}
