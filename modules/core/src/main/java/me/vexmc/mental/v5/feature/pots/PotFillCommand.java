package me.vexmc.mental.v5.feature.pots;

import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code /potfill} command (aliases {@code /pots}, {@code /pf}) — fills the
 * empty storage slots of the running player with splash Instant Health II. A raw
 * {@link Command} (not a {@code plugin.yml} entry) so {@code PotFillUnit} can add
 * and remove it dynamically; a disabled feature leaves no command-map trace.
 *
 * <p>The command reads its {@link PotFillSettings} LIVE from the snapshot each run
 * (a reload's changed permission/cost takes effect immediately) and gates in
 * order: a player-only check, the configurable permission, and — when a cost is
 * set — economy availability. The permission is checked here (not on the Command's
 * own {@code permission}) precisely because it is reconfigurable without
 * re-registering. The inventory mutation hops to the player's owning region thread
 * (Folia-correct); every reply routes through {@link TextPort}.</p>
 */
public final class PotFillCommand extends Command {

    private final Supplier<PotFillSettings> settings;
    private final EconomyPort economy;
    private final PotFiller filler;
    private final Scheduling scheduling;

    public PotFillCommand(
            @NotNull Supplier<PotFillSettings> settings,
            @NotNull EconomyPort economy,
            @NotNull PotFiller filler,
            @NotNull Scheduling scheduling) {
        super("potfill",
                "Fill empty inventory slots with splash Instant Health II.",
                "/potfill",
                List.of("pots", "pf"));
        this.settings = settings;
        this.economy = economy;
        this.filler = filler;
        this.scheduling = scheduling;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        PotFillSettings config = settings.get();
        if (!(sender instanceof Player player)) {
            send(sender, "&cOnly a player can fill their own inventory.");
            return true;
        }
        if (!player.hasPermission(config.permission())) {
            send(sender, "&cYou do not have permission to fill potions (" + config.permission() + ").");
            return true;
        }
        if (config.costPerPotion() > 0.0 && !economy.present()) {
            send(sender, "&cPot fill needs an economy plugin (Vault) to charge "
                    + trim(config.costPerPotion()) + " per potion, but none is available.");
            return true;
        }
        scheduling.runOn(player, () -> reportOutcome(player, filler.fill(player, config, economy), config),
                () -> {});
        return true;
    }

    private void reportOutcome(Player player, PotFiller.Outcome outcome, PotFillSettings config) {
        switch (outcome.reason()) {
            case FILLED -> {
                String base = "&aFilled &f" + outcome.filled()
                        + "&a slot" + (outcome.filled() == 1 ? "" : "s") + " with Instant Health II";
                if (outcome.charged() > 0.0) {
                    base += " for &f" + trim(outcome.charged());
                }
                base += "&a.";
                if (outcome.filled() < outcome.emptyBefore()) {
                    base += " &7(afforded " + outcome.filled() + " of " + outcome.emptyBefore()
                            + " empty slots)";
                }
                send(player, base);
            }
            case NOTHING_TO_FILL ->
                    send(player, "&eYour inventory has no empty slots to fill.");
            case CANNOT_AFFORD ->
                    send(player, "&cYou cannot afford a single potion (cost &f"
                            + trim(config.costPerPotion()) + "&c each).");
            case CHARGE_FAILED ->
                    send(player, "&cThe economy charge was declined — nothing was filled.");
            case ITEM_UNAVAILABLE ->
                    send(player, "&cThis server cannot build the heal potion — pot fill is unavailable.");
            default -> { /* exhaustive */ }
        }
    }

    private static void send(CommandSender sender, String ampersandText) {
        Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(ampersandText);
        TextPort.send(sender, message);
    }

    /** Trims a trailing ".0" so an integer cost/charge reads cleanly (e.g. "10", "2.5"). */
    private static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
