package me.vexmc.mental.v5.gui;

import java.util.Map;
import java.util.Set;
import me.vexmc.mental.platform.debug.DebugCategory;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Verbose-logging controls: the master switch and a tile per {@link
 * DebugCategory} channel. The master switch writes the {@code debug.enabled}
 * machine-overlay key; each channel writes {@code debug.categories.<key>}; both
 * reload through {@link me.vexmc.mental.v5.manage.Management} so the human YAML is
 * never re-serialized (Task 6.0 constraint 2). The active set is read back from
 * the live snapshot's {@code debug.categories()}.
 */
public final class DebugMenu extends Menu {

    private static final String ENABLED_KEY = "debug.enabled";
    private static final String CATEGORY_PREFIX = "debug.categories.";

    // Map.ofEntries (not Map.of) — the JOURNAL channel takes the ICONS map to 11
    // pairs, past Map.of's 10-pair ceiling. BOOK exists across the whole material
    // range; WRITABLE_BOOK is already taken by CONFIG.
    private static final Map<DebugCategory, String> ICONS = Map.ofEntries(
            Map.entry(DebugCategory.HITREG, "IRON_SWORD"),
            Map.entry(DebugCategory.KNOCKBACK, "PISTON"),
            Map.entry(DebugCategory.JOURNAL, "BOOK"),
            Map.entry(DebugCategory.COMPENSATION, "CLOCK"),
            Map.entry(DebugCategory.FISHING, "FISHING_ROD"),
            Map.entry(DebugCategory.PROJECTILE, "SNOWBALL"),
            Map.entry(DebugCategory.PACKETS, "PAPER"),
            Map.entry(DebugCategory.ANTICHEAT, "IRON_BARS"),
            Map.entry(DebugCategory.SCHEDULING, "COMPASS"),
            Map.entry(DebugCategory.COMMANDS, "OAK_SIGN"),
            Map.entry(DebugCategory.CONFIG, "WRITABLE_BOOK"));

    private static final int[] CATEGORY_SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33, 38};

    /** Row-4 centre: the "stream to my chat" toggle, set apart from the log-channel grid. */
    private static final int SUBSCRIBE_SLOT = 40;

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
        boolean masterOn = ctx.plugin().snapshot().debug().enabled();
        set(4, Buttons.toggle("REDSTONE_TORCH", "Debug logging", masterOn,
                "Master switch for verbose logging. Enable it, then pick channels below."),
                click -> apply(viewer, () -> ctx.management().setOverlay(ENABLED_KEY, !masterOn)));

        Set<String> active = ctx.plugin().snapshot().debug().categories();
        DebugCategory[] values = DebugCategory.values();
        for (int i = 0; i < values.length && i < CATEGORY_SLOTS.length; i++) {
            DebugCategory category = values[i];
            boolean on = active.contains(category.key());
            set(CATEGORY_SLOTS[i], categoryTile(category, on),
                    click -> apply(viewer,
                            () -> ctx.management().setOverlay(CATEGORY_PREFIX + category.key(), !on)));
        }

        // The player-facing sink toggle: route the active channels above to the
        // viewer's OWN chat (the /mental debug subscribe surface, in menu form).
        // The subscription lives in memory on the PlayerDebugSink — not the config
        // overlay — so this toggles the sink directly and repaints; nothing is
        // written to disk and no reload is needed. Cleared when the viewer quits.
        boolean subscribed = ctx.plugin().playerDebugSink().isSubscribed(viewer);
        set(SUBSCRIBE_SLOT, Buttons.toggle("NAME_TAG", "Stream to my chat", subscribed,
                "Send the active debug channels to your own chat. In-memory and per-session — cleared when you quit."),
                click -> apply(viewer, () -> ctx.plugin().playerDebugSink().toggle(viewer)));

        set(49, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull ItemStack categoryTile(@NotNull DebugCategory category, boolean on) {
        return Buttons.toggle(ICONS.getOrDefault(category, "PAPER"),
                "Channel: " + category.key(), on, "Verbose " + category.key() + " logging.");
    }
}
