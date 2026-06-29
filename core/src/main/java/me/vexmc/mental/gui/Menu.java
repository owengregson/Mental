package me.vexmc.mental.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Base of every Mental management screen.
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
 * {@code reloadAll()}) on the global thread, then repaints the viewer. On Paper
 * both collapse to the main thread.</p>
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
        ctx.services().scheduling().runOn(viewer, () -> {
            inventory = Bukkit.createInventory(this, rows() * 9, title());
            redraw(viewer);
            viewer.openInventory(inventory);
        }, () -> {});
    }

    /** Repaints in place for a viewer who already has this menu open. */
    public final void refresh(@NotNull Player viewer) {
        ctx.services().scheduling().runOn(viewer, () -> {
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
        ItemStack filler = Icon.of(Materials.of("GRAY_STAINED_GLASS_PANE"))
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
        ctx.services().scheduling().runGlobal(() -> {
            mutation.run();
            refresh(viewer);
        });
    }

    /** Opens another screen for the viewer (navigation). */
    protected final void navigate(@NotNull Player viewer, @NotNull Menu destination) {
        destination.open(viewer);
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
        return current != null ? current : Bukkit.createInventory(this, rows() * 9, title());
    }
}
