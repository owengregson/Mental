package me.vexmc.mental.gui.menu;

import java.util.Map;
import java.util.Set;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.gui.Icon;
import me.vexmc.mental.gui.Menu;
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Verbose-logging controls: the master switch, a tile per channel, and a
 * per-viewer toggle to stream lines into your own chat. The master switch and
 * channels write {@code debug.*} in config.yml and reload; the chat
 * subscription is in-memory for your session only.
 */
public final class DebugMenu extends Menu {

    private static final Map<DebugCategory, String> ICONS = Map.of(
            DebugCategory.HITREG, "IRON_SWORD",
            DebugCategory.KNOCKBACK, "PISTON",
            DebugCategory.COMPENSATION, "CLOCK",
            DebugCategory.FISHING, "FISHING_ROD",
            DebugCategory.PROJECTILE, "SNOWBALL",
            DebugCategory.PACKETS, "PAPER",
            DebugCategory.ANTICHEAT, "IRON_BARS",
            DebugCategory.SCHEDULING, "COMPASS",
            DebugCategory.COMMANDS, "OAK_SIGN",
            DebugCategory.CONFIG, "WRITABLE_BOOK");

    private static final int[] CATEGORY_SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

    public DebugMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Debug", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        boolean masterOn = ctx.services().config().debug().enabled();
        set(4, Buttons.toggle("REDSTONE_TORCH", "Debug logging", masterOn,
                "Master switch for verbose logging. Enable it, then pick channels below."),
                click -> apply(viewer, () -> ctx.management().setDebugEnabled(!masterOn)));

        Set<DebugCategory> active = ctx.services().config().debug().categories();
        DebugCategory[] values = DebugCategory.values();
        for (int i = 0; i < values.length && i < CATEGORY_SLOTS.length; i++) {
            DebugCategory category = values[i];
            boolean on = active.contains(category);
            set(CATEGORY_SLOTS[i], Buttons.toggle(ICONS.getOrDefault(category, "PAPER"),
                    "Channel: " + category.key(), on, "Verbose " + category.key() + " logging."),
                    click -> apply(viewer, () -> ctx.management().setDebugCategory(category.key(), !on)));
        }

        set(48, subscribeTile(viewer), click -> {
            ctx.debugSink().toggle(viewer.getUniqueId());
            refresh(viewer);
        });
        set(50, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull org.bukkit.inventory.ItemStack subscribeTile(@NotNull Player viewer) {
        boolean subscribed = ctx.debugSink().isSubscribed(viewer.getUniqueId());
        Icon icon = Buttons.title("BELL", "Receive in chat");
        Buttons.wrap("Stream debug lines to your chat for this session. Cleared on quit.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text(subscribed ? "● SUBSCRIBED" : "○ NOT SUBSCRIBED",
                subscribed ? Brand.SUCCESS : Brand.FAILURE).decoration(TextDecoration.BOLD, true));
        icon.lore(Component.text("▸ Click to " + (subscribed ? "unsubscribe" : "subscribe"), Brand.SECONDARY));
        return icon.glow(subscribed).build();
    }
}
