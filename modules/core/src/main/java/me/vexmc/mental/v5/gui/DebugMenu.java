package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import me.vexmc.mental.platform.debug.DebugCategory;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Verbose-logging controls: the master switch and a tile per {@link
 * DebugCategory} channel, laid out through {@link Layout#contentRow} (no
 * hand-synced slot array). The master switch writes the {@code debug.enabled}
 * machine-overlay key; each channel writes {@code debug.categories.<key>}; both
 * reload through {@link me.vexmc.mental.v5.manage.Management} so the human YAML is
 * never re-serialized, and both carry the ⚑/Q-reset affordance. The active set is
 * read back from the live snapshot's {@code debug.categories()}.
 *
 * <p>The "stream to my chat" tile flips the in-memory {@link
 * me.vexmc.mental.v5.debug.PlayerDebugSink} subscription directly on the viewer's
 * region thread — a per-session convenience with no config write and no reload.</p>
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

    /** First seven channels ride row 1; the rest ride row 2 — capacity 14 inside the frame. */
    private static final int CHANNELS_PER_ROW = 7;

    private static final int MASTER_SLOT = 4;
    private static final int FIRST_ROW_BASE = 9;
    private static final int SECOND_ROW_BASE = 18;
    private static final int SUBSCRIBE_SLOT = 31;
    private static final int BACK_SLOT = 40;

    public DebugMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text("Debug", Palette.system().accent()));
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(Palette.system().pane());

        boolean masterOn = ctx.plugin().snapshot().debug().enabled();
        set(MASTER_SLOT, Buttons.toggle("REDSTONE_TORCH", "Debug logging", masterOn,
                "Master switch for verbose logging. Enable it, then pick channels below.",
                ctx.plugin().overlayHas(ENABLED_KEY)),
                toggleClick(viewer, ENABLED_KEY, masterOn));

        Set<String> active = ctx.plugin().snapshot().debug().categories();
        DebugCategory[] categories = DebugCategory.values();
        int firstRow = Math.min(CHANNELS_PER_ROW, categories.length);
        int[] row1 = Layout.contentRow(FIRST_ROW_BASE, firstRow);
        for (int i = 0; i < firstRow; i++) {
            placeChannel(viewer, categories[i], active, row1[i]);
        }
        int remainder = categories.length - firstRow;
        if (remainder > 0) {
            // Layout.contentRow rejects more than seven tiles per row, so the second
            // row caps the surface at 14 channels — DebugCategory has 11.
            int[] row2 = Layout.contentRow(SECOND_ROW_BASE, remainder);
            for (int i = 0; i < remainder; i++) {
                placeChannel(viewer, categories[firstRow + i], active, row2[i]);
            }
        }

        // The player-facing sink toggle: route the active channels above to the
        // viewer's OWN chat. The subscription lives in memory on the concurrent
        // PlayerDebugSink — not the config overlay — so this flips the sink directly
        // on the viewer's region thread and repaints; nothing is written to disk and
        // no reload is needed. Cleared when the viewer quits.
        boolean subscribed = ctx.plugin().playerDebugSink().isSubscribed(viewer);
        set(SUBSCRIBE_SLOT, subscribeTile(subscribed), click -> {
            ctx.plugin().playerDebugSink().toggle(viewer);
            refresh(viewer);
        });

        set(BACK_SLOT, Buttons.back(Category.SYSTEM.displayName()),
                click -> navigate(viewer, new CategoryMenu(ctx, Category.SYSTEM)));
    }

    /**
     * Boot self-test seam: the master toggle, all eleven channel tiles, the
     * subscribe tile (rendered unsubscribed — there is no viewer), and back, as
     * pure Bukkit stacks with no scheduler hop.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        boolean masterOn = ctx.plugin().snapshot().debug().enabled();
        icons.add(Buttons.toggle("REDSTONE_TORCH", "Debug logging", masterOn,
                "Master switch for verbose logging. Enable it, then pick channels below.",
                ctx.plugin().overlayHas(ENABLED_KEY)));
        Set<String> active = ctx.plugin().snapshot().debug().categories();
        for (DebugCategory category : DebugCategory.values()) {
            icons.add(channelTile(category, active.contains(category.key())));
        }
        icons.add(subscribeTile(false));
        icons.add(Buttons.back(Category.SYSTEM.displayName()));
        return icons;
    }

    private void placeChannel(
            @NotNull Player viewer, @NotNull DebugCategory category, @NotNull Set<String> active, int slot) {
        boolean on = active.contains(category.key());
        set(slot, channelTile(category, on), toggleClick(viewer, CATEGORY_PREFIX + category.key(), on));
    }

    private @NotNull ItemStack channelTile(@NotNull DebugCategory category, boolean on) {
        return Buttons.toggle(ICONS.getOrDefault(category, "PAPER"), "Channel: " + category.key(),
                on, "Verbose " + category.key() + " logging.",
                ctx.plugin().overlayHas(CATEGORY_PREFIX + category.key()));
    }

    private @NotNull ItemStack subscribeTile(boolean subscribed) {
        return Buttons.toggle("NAME_TAG", "Stream to my chat", subscribed,
                "Send the active debug channels to your own chat. Per-session — cleared when you quit.",
                false);
    }

    /**
     * A boolean-overlay click with the Q-reset affordance: the drop key clears the
     * key back to its file value when it is overridden; any other click flips it.
     */
    private @NotNull Consumer<InventoryClickEvent> toggleClick(
            @NotNull Player viewer, @NotNull String key, boolean current) {
        return event -> {
            if (consumeReset(viewer, event, key)) {
                return;
            }
            apply(viewer, () -> ctx.management().setOverlay(key, !current));
        };
    }
}
