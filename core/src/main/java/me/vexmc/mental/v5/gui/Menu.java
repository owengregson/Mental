package me.vexmc.mental.v5.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base of every Mental management screen (the retired {@code gui/Menu}
 * re-expressed on the v5 seams).
 *
 * <p>A menu <em>is</em> its own {@link InventoryHolder}: the inventory is
 * created with {@code this} as holder, so {@code event.getInventory().getHolder()
 * instanceof Menu} is the click router's identity test — robust across every
 * version and immune to slot-index or display-name matching. Each menu instance
 * serves a single viewer, so its slot/handler state never races another
 * player's.</p>
 *
 * <p>Threading is Folia-correct by construction. {@link #open} and {@link
 * #refresh} run their inventory work on the viewer's owning region thread via
 * {@code Scheduling.runOn}; {@link #apply} runs a config mutation (which reaches
 * {@code reloadAll()}) on the global thread via {@code Scheduling.runGlobal}, then
 * repaints the viewer. On Paper both collapse to the main thread.</p>
 */
public abstract class Menu implements InventoryHolder {

    protected final MenuContext ctx;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private @Nullable Inventory inventory;

    protected Menu(@NotNull MenuContext ctx) {
        this.ctx = ctx;
    }

    /** The menu title; rendered through Adventure (cross-version safe). */
    protected abstract @NotNull Component title();

    /** Height in rows (1–6); the inventory is {@code rows * 9} slots. */
    protected abstract int rows();

    /** Populates items and click handlers. Runs on the viewer's region thread. */
    protected abstract void draw(@NotNull Player viewer);

    public final void open(@NotNull Player viewer) {
        ctx.scheduling().runOn(viewer, () -> {
            // String-title path (TextPort): createInventory(holder,int,Component)
            // is Paper 1.16.5+; the (holder,int,String) overload exists since 1.9.
            inventory = TextPort.createInventory(this, rows() * 9, title());
            redraw(viewer);
            viewer.openInventory(inventory);
        }, () -> {});
    }

    /** Repaints in place for a viewer who already has this menu open. */
    public final void refresh(@NotNull Player viewer) {
        ctx.scheduling().runOn(viewer, () -> {
            if (inventory != null) {
                redraw(viewer);
            }
        }, () -> {});
    }

    private void redraw(@NotNull Player viewer) {
        handlers.clear();
        if (inventory != null) {
            inventory.clear();
        }
        draw(viewer);
        fillEmpty();
    }

    protected final void set(int slot, @NotNull ItemStack item) {
        set(slot, item, null);
    }

    protected final void set(int slot, @NotNull ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (onClick != null) {
            handlers.put(slot, onClick);
        } else {
            handlers.remove(slot);
        }
    }

    /** Fills every still-empty slot with the decorative border pane. */
    private void fillEmpty() {
        if (inventory == null) {
            return;
        }
        ItemStack filler = Icon.of(MenuMaterials.of("GRAY_STAINED_GLASS_PANE"))
                .name(Component.empty())
                .build();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /**
     * Runs a config-mutating action where {@code reloadAll()} is safe (the
     * global thread on Folia), then repaints the menu for the viewer. The
     * viewer's open inventory updates in place.
     */
    protected final void apply(@NotNull Player viewer, @NotNull Runnable mutation) {
        ctx.scheduling().runGlobal(() -> {
            mutation.run();
            refresh(viewer);
        });
    }

    /** Opens another screen for the viewer (navigation). */
    protected final void navigate(@NotNull Player viewer, @NotNull Menu destination) {
        destination.open(viewer);
    }

    /** One placed tile: an icon and an optional click handler. */
    public record Tile(@NotNull ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
        public static @NotNull Tile of(@NotNull ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
            return new Tile(item, onClick);
        }
    }

    /**
     * Places {@code tiles} contiguously and centred within the nine-wide row
     * whose first slot is {@code rowBase} — the one shared layout primitive the
     * screens build on, retiring the per-screen centring math. Extra tiles past
     * the row width are dropped (the caller sizes its rows).
     */
    protected final void placeCentered(int rowBase, @NotNull List<Tile> tiles) {
        int count = tiles.size();
        int start = rowBase + Math.max(0, (9 - count) / 2);
        for (int i = 0; i < count && start + i <= rowBase + 8; i++) {
            Tile tile = tiles.get(i);
            set(start + i, tile.item(), tile.onClick());
        }
    }

    final void handleClick(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }

    void handleClose(@NotNull InventoryCloseEvent event) {}

    @Override
    public @NotNull Inventory getInventory() {
        // Never observed before open() has created it (Bukkit only routes events
        // for an inventory that exists); fall back to an empty stub for the
        // pathological pre-open getHolder().getInventory() call.
        Inventory current = inventory;
        return current != null ? current : TextPort.createInventory(this, rows() * 9, title());
    }

    /**
     * Boot self-test seam: an empty inventory built through the universal
     * {@code createInventory(holder,int,String)} overload + the {@link TextPort}
     * title, with no viewer and no scheduler hop. The tester calls this on every
     * version (including 1.9.4) so a legacy Adventure/String-API classload break
     * surfaces at boot rather than on the first live open. Not part of the live
     * open path.
     */
    public final @NotNull Inventory selfTestInventory() {
        return TextPort.createInventory(this, rows() * 9, title());
    }
}
