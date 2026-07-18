package me.vexmc.mental.v5.text;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The single seam where Adventure {@link Component}s meet Bukkit.
 *
 * <p>Mental builds every user-facing string as a {@link Component} internally (the
 * GUI, the brand prefix, the off-hand denial line), but the Paper-native
 * {@code Component}-taking sinks — {@code ItemMeta#displayName}, {@code #lore},
 * {@code Bukkit.createInventory(holder,int,Component)}, {@code
 * CommandSender#sendMessage(Component)} — only exist from Paper 1.16.5. Below that
 * they are absent, and so is {@code net.kyori} itself. So every Component is
 * serialized to a {@code §}-encoded legacy string HERE and handed to the
 * universal String overloads ({@code setDisplayName}, {@code setLore},
 * {@code createInventory(holder,int,String)}, {@code sendMessage(String)}), which
 * have existed since 1.9. Nothing else in core/platform passes a Component across
 * a Bukkit boundary — pinned by an architecture grep — so on modern servers the
 * relocated Adventure copy is inert (it never touches Paper's native Adventure)
 * and on legacy servers the sinks never reference a class that isn't there.</p>
 *
 * <p>Adventure is shaded into the plugin jar (relocated to {@code
 * me.vexmc.mental.lib.adventure}), so {@link LegacyComponentSerializer} is present
 * and self-contained on every supported version; {@link #legacy(Component)} is the
 * pure, unit-pinned conversion the sinks are built on.</p>
 */
public final class TextPort {

    /** Section-code (§) serializer — the vanilla wire encoding every version renders. */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private TextPort() {}

    /** The section-encoded legacy string for {@code component} (the pure core; unit-pinned). */
    public static @NotNull String legacy(@NotNull Component component) {
        return SECTION.serialize(component);
    }

    /** Each component serialized to its legacy string, order preserved. */
    public static @NotNull List<String> legacy(@NotNull List<Component> components) {
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(SECTION.serialize(component));
        }
        return lines;
    }

    // The String overloads below are @Deprecated on modern Paper (superseded by
    // the Component ones), but they are the ONLY ones present across the whole
    // range — that is the entire point of this seam.

    /** Sets an item's display name via the universal {@code setDisplayName(String)} sink. */
    @SuppressWarnings("deprecation")
    public static void displayName(@NotNull ItemMeta meta, @NotNull Component name) {
        meta.setDisplayName(legacy(name));
    }

    /** Sets an item's lore via the universal {@code setLore(List<String>)} sink. */
    @SuppressWarnings("deprecation")
    public static void lore(@NotNull ItemMeta meta, @NotNull List<Component> lore) {
        meta.setLore(legacy(lore));
    }

    /**
     * Creates an inventory via the universal {@code createInventory(holder,int,String)}
     * overload (present since 1.9; the Component overload is Paper 1.16.5+).
     */
    @SuppressWarnings("deprecation")
    public static @NotNull Inventory createInventory(
            @NotNull InventoryHolder holder, int size, @NotNull Component title) {
        return Bukkit.createInventory(holder, size, legacy(title));
    }

    /** Sends a chat line via the universal {@code sendMessage(String)} sink. */
    @SuppressWarnings("deprecation")
    public static void send(@NotNull CommandSender sender, @NotNull Component message) {
        sender.sendMessage(legacy(message));
    }
}
