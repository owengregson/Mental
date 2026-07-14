package me.vexmc.mental.v5.gui;

import java.util.Locale;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The dedicated Loot Protection screen: the module toggle plus the protection
 * window (stepper) and the glow colour (cycle). Both write a
 * {@code drop-protection.<field>} overlay key through Management. The pickup rule
 * (killer-only) is fixed and shown as read-only copy.
 */
public final class LootProtectionMenu extends Menu {

    private static final String SECONDS_KEY = "drop-protection.seconds";
    private static final String GLOW_KEY = "drop-protection.glow-color";
    private static final int SECONDS_MAX = 3600;

    public LootProtectionMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Loot Protection", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        DropProtectionSettings settings = settings();

        Icon header = Buttons.title("CHEST", "Loot Protection");
        Buttons.wrap("When a player kills another player, the victim's drops are locked to the"
                + " killer for a window and glow to the killer alone. Only PvP kills trigger it.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        boolean enabled = ctx.plugin().featureActive(Feature.DROP_PROTECTION);
        set(19, Buttons.toggle("LEVER", "Drop Protection module", enabled,
                "Master switch — off means drops behave exactly as vanilla."),
                click -> apply(viewer,
                        () -> ctx.management().setModuleEnabled(Feature.DROP_PROTECTION, !enabled)));

        set(22, Buttons.stepper("CLOCK", "Protection window", settings.seconds() + "s",
                "How long the drops stay locked to the killer."),
                click -> step(viewer, click, settings.seconds()));

        set(25, Buttons.cycle("GOLD_INGOT", "Glow colour",
                settings.glowColor().name().toLowerCase(Locale.ROOT),
                "The colour the killer (and only the killer) sees on the reserved loot."),
                click -> apply(viewer, () -> ctx.management().setOverlay(GLOW_KEY, next(settings.glowColor()).name())));

        set(31, infoTile());

        set(40, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull ItemStack infoTile() {
        Icon icon = Buttons.title("PLAYER_HEAD", "Pickup rule", Brand.MUTED);
        Buttons.wrap("During the window only the KILLER can pick up the drops — the victim and"
                + " everyone else are blocked until it elapses, then the loot is free for all.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        return icon.build();
    }

    private void step(@NotNull Player viewer, @NotNull InventoryClickEvent click, int current) {
        int delta = (click.isShiftClick() ? 10 : 1) * (click.isRightClick() ? -1 : 1);
        int next = Math.max(1, Math.min(SECONDS_MAX, current + delta));
        if (next != current) {
            apply(viewer, () -> ctx.management().setOverlay(SECONDS_KEY, next));
        }
    }

    private static @NotNull GlowColor next(@NotNull GlowColor color) {
        GlowColor[] values = GlowColor.values();
        return values[(color.ordinal() + 1) % values.length];
    }

    @SuppressWarnings("unchecked")
    private @NotNull DropProtectionSettings settings() {
        return ctx.plugin().snapshot().settings(
                (SettingsKey<DropProtectionSettings>) Feature.DROP_PROTECTION.settingsKey());
    }
}
