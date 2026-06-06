package me.vexmc.mental.command;

import static me.vexmc.mental.common.command.ArgumentNode.argument;
import static me.vexmc.mental.common.command.LiteralNode.literal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.command.CommandContext;
import me.vexmc.mental.common.command.CommandMessages;
import me.vexmc.mental.common.command.CommandTree;
import me.vexmc.mental.common.command.LiteralNode;
import me.vexmc.mental.common.command.Suggester;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.debug.PlayerDebugSink;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.module.compensation.LatencyCompensationModule;
import me.vexmc.mental.module.ocm.OcmMechanic;
import me.vexmc.mental.text.Brand;
import me.vexmc.mental.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The whole {@code /mental} tree, declared once and rendered by whichever
 * backend this server supports. Every action funnels through the same small
 * helpers, so Brigadier and classic dispatch are behaviorally identical.
 */
public final class MentalCommands {

    private static final String PERMISSION_USE = "mental.command.use";
    private static final String PERMISSION_MODULE = "mental.command.module";
    private static final String PERMISSION_KNOCKBACK = "mental.command.knockback";
    private static final String PERMISSION_RELOAD = "mental.command.reload";
    private static final String PERMISSION_DEBUG = "mental.command.debug";
    private static final String PERMISSION_PING = "mental.command.ping";

    private final MentalPlugin plugin;
    private final MentalServices services;
    private final ModuleRegistry modules;
    private final PlayerDebugSink debugSink;
    private CommandTree tree;

    public MentalCommands(
            @NotNull MentalPlugin plugin,
            @NotNull MentalServices services,
            @NotNull ModuleRegistry modules,
            @NotNull PlayerDebugSink debugSink) {
        this.plugin = plugin;
        this.services = services;
        this.modules = modules;
        this.debugSink = debugSink;
    }

    public @NotNull CommandTree build() {
        Suggester moduleIds = (sender, partial) -> modules.ids();
        Suggester onlinePlayers = (sender, partial) ->
                Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        Suggester categories = (sender, partial) ->
                List.of(DebugCategory.values()).stream().map(DebugCategory::key).toList();
        Suggester profileNames = (sender, partial) ->
                services.knockbackProfiles().names().stream().sorted().toList();

        LiteralNode root = literal("mental")
                .permission(PERMISSION_USE)
                .describe("Interactive combat dashboard.")
                .runs(context -> context.reply(Dashboard.render(services, modules)))
                .then(literal("module")
                        .permission(PERMISSION_MODULE)
                        .describe("Toggle and inspect combat modules.")
                        .then(argument("name", moduleIds)
                                .then(literal("on").describe("Enable the module.")
                                        .runs(context -> toggleModule(context, true)).build())
                                .then(literal("off").describe("Disable the module.")
                                        .runs(context -> toggleModule(context, false)).build())
                                .then(literal("status").describe("Show the module's state.")
                                        .runs(this::moduleStatus).build())
                                .build())
                        .build())
                .then(literal("kb")
                        .permission(PERMISSION_KNOCKBACK)
                        .describe("Knockback profiles — list, inspect, assign.")
                        .runs(this::kbOverview)
                        .then(literal("info")
                                .describe("Show a profile's values.")
                                .then(argument("profile", profileNames)
                                        .runs(this::kbInfo)
                                        .build())
                                .build())
                        .then(literal("set")
                                .describe("Override a player's profile.")
                                .then(argument("profile", profileNames)
                                        .runs(context -> kbSet(context, null))
                                        .then(argument("player", onlinePlayers)
                                                .runs(context -> kbSet(context, context.arg("player")))
                                                .build())
                                        .build())
                                .build())
                        .then(literal("reset")
                                .describe("Clear a player's profile override.")
                                .runs(context -> kbReset(context, null))
                                .then(argument("player", onlinePlayers)
                                        .runs(context -> kbReset(context, context.arg("player")))
                                        .build())
                                .build())
                        .build())
                .then(literal("ping")
                        .permission(PERMISSION_PING)
                        .describe("Measured RTT, jitter, and spike state.")
                        .runs(this::pingSelf)
                        .then(argument("player", onlinePlayers)
                                .runs(this::pingOther)
                                .build())
                        .build())
                .then(literal("debug")
                        .permission(PERMISSION_DEBUG)
                        .describe("Verbose logging controls.")
                        .runs(this::debugStatus)
                        .then(literal("on").describe("Enable debug logging.")
                                .runs(context -> setDebugEnabled(context, true)).build())
                        .then(literal("off").describe("Disable debug logging.")
                                .runs(context -> setDebugEnabled(context, false)).build())
                        .then(literal("category")
                                .describe("Toggle a single debug category.")
                                .then(argument("category", categories)
                                        .then(literal("on").runs(context -> setCategory(context, true)).build())
                                        .then(literal("off").runs(context -> setCategory(context, false)).build())
                                        .build())
                                .build())
                        .then(literal("subscribe")
                                .describe("Receive debug lines in-game.")
                                .runs(this::toggleSubscription)
                                .build())
                        .build())
                .then(literal("reload")
                        .permission(PERMISSION_RELOAD)
                        .describe("Reload the configuration atomically.")
                        .runs(this::reload)
                        .build())
                .then(literal("version")
                        .describe("Version, platform, and capability report.")
                        .runs(this::version)
                        .build())
                .then(literal("help")
                        .describe("Show every command.")
                        .runs(this::help)
                        .build())
                .build();

        this.tree = new CommandTree(root, new CommandMessages() {
            @Override
            public @NotNull Component noPermission() {
                return Messages.noPermission();
            }

            @Override
            public @NotNull Component unknownSubcommand() {
                return Messages.unknownSubcommand();
            }

            @Override
            public @NotNull Component usage(@NotNull String usage) {
                return Messages.usage(usage);
            }
        });
        return tree;
    }

    private void toggleModule(CommandContext context, boolean enabled) {
        String id = context.arg("name").toLowerCase(Locale.ROOT);
        CombatModule module = modules.byId(id).orElse(null);
        if (module == null) {
            context.reply(Messages.moduleUnknown(id, modules.ids()));
            return;
        }
        plugin.getConfig().set("modules." + id, enabled);
        plugin.saveConfig();
        plugin.reloadAll();
        context.reply(Messages.moduleToggled(module.displayName(), module.active()));
    }

    private void moduleStatus(CommandContext context) {
        String id = context.arg("name").toLowerCase(Locale.ROOT);
        CombatModule module = modules.byId(id).orElse(null);
        if (module == null) {
            context.reply(Messages.moduleUnknown(id, modules.ids()));
            return;
        }
        context.reply(Messages.moduleStatus(module.displayName(), module.active(), module.description()));
    }

    private void kbOverview(CommandContext context) {
        var knockback = services.config().knockback();
        var builder = Component.text()
                .append(Brand.prefix())
                .append(Component.text(" Knockback profiles", Brand.TEXT))
                .append(Component.text("  default: ", Brand.MUTED))
                .append(Component.text(knockback.defaultProfile(), Brand.ACCENT));
        if (!knockback.perWorld().isEmpty()) {
            builder.append(Component.text("  per-world: ", Brand.MUTED))
                    .append(Component.text(knockback.perWorld().toString(), Brand.ACCENT));
        }
        knockback.profiles().keySet().stream().sorted().forEach(name -> {
            var profile = knockback.byName(name);
            boolean selected = name.equals(knockback.defaultProfile());
            builder.append(Component.newline())
                    .append(Component.text("  " + (selected ? "●" : "○") + " ",
                            selected ? Brand.SUCCESS : Brand.MUTED))
                    .append(Component.text(name, Brand.SECONDARY)
                            .clickEvent(ClickEvent.suggestCommand("/mental kb info " + name)))
                    .append(Component.text(" — ", Brand.MUTED))
                    .append(Component.text(profile == null || profile.description().isEmpty()
                            ? profile == null ? "?" : profile.displayName()
                            : profile.description(), Brand.TEXT));
        });
        // The question every "profile feels wrong" report starts with: is the
        // profile even shaping melee hits, or has OCM's modeset claimed them?
        if (services.ocmGate().handles(OcmMechanic.MELEE_KNOCKBACK, null)
                || services.ocmGate().coordinated().contains(OcmMechanic.MELEE_KNOCKBACK)) {
            builder.append(Component.newline())
                    .append(Component.text("  ⚠ ", Brand.FAILURE))
                    .append(Component.text("OCM's old-player-knockback can own melee knockback here"
                            + " (modeset-decided) — profiles do not shape OCM-owned hits."
                            + " Remove it from OldCombatMechanics/config.yml modesets to let"
                            + " these profiles apply.", Brand.MUTED));
        }
        context.reply(builder.build());
    }

    private void kbInfo(CommandContext context) {
        String name = context.arg("profile").toLowerCase(Locale.ROOT);
        var profile = services.config().knockback().byName(name);
        if (profile == null) {
            context.reply(Brand.failure("Unknown profile '" + name + "'. Available: "
                    + String.join(", ", services.knockbackProfiles().names().stream().sorted().toList())));
            return;
        }
        context.reply(Brand.line(Component.text()
                .append(Component.text(profile.displayName(), Brand.SECONDARY))
                .append(Component.text(" (" + profile.name() + ")", Brand.MUTED))
                .append(profile.description().isEmpty()
                        ? Component.empty()
                        : Component.text(" — " + profile.description(), Brand.TEXT))
                .append(Component.newline())
                .append(Component.text("  base ", Brand.MUTED))
                .append(Component.text(profile.base().horizontal() + "/" + profile.base().vertical(),
                        Brand.ACCENT))
                .append(Component.text("  vertical ", Brand.MUTED))
                .append(Component.text(profile.verticalMode().name().toLowerCase(Locale.ROOT), Brand.ACCENT))
                .append(Component.text("  extra ", Brand.MUTED))
                .append(Component.text(profile.extra().horizontal() + "/" + profile.extra().vertical(),
                        Brand.ACCENT))
                .append(Component.text("  friction ", Brand.MUTED))
                .append(Component.text(String.valueOf(profile.friction().x()), Brand.ACCENT))
                .append(Component.text("  combos ", Brand.MUTED))
                .append(Component.text(String.valueOf(profile.combos()),
                        profile.combos() ? Brand.SUCCESS : Brand.FAILURE))
                .append(Component.text("  taper ", Brand.MUTED))
                .append(Component.text(profile.rangeReduction().enabled() ? "on" : "off",
                        profile.rangeReduction().enabled() ? Brand.SUCCESS : Brand.FAILURE))
                .build()));
    }

    private void kbSet(CommandContext context, String playerName) {
        Player target = resolveKbTarget(context, playerName);
        if (target == null) {
            return;
        }
        String profile = context.arg("profile").toLowerCase(Locale.ROOT);
        if (!services.knockbackProfiles().setOverride(target, profile)) {
            context.reply(Brand.failure("Unknown profile '" + profile + "'. Available: "
                    + String.join(", ", services.knockbackProfiles().names().stream().sorted().toList())));
            return;
        }
        context.reply(Brand.success(target.getName() + " now uses the '" + profile
                + "' knockback profile (override)."));
    }

    private void kbReset(CommandContext context, String playerName) {
        Player target = resolveKbTarget(context, playerName);
        if (target == null) {
            return;
        }
        services.knockbackProfiles().setOverride(target, null);
        context.reply(Brand.success(target.getName() + " follows the world/default profile again ("
                + services.knockbackProfiles().resolve(target).name() + ")."));
    }

    private Player resolveKbTarget(CommandContext context, String playerName) {
        if (playerName == null) {
            Player self = context.playerSender();
            if (self == null) {
                context.reply(Messages.playersOnly());
            }
            return self;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            context.reply(Messages.playerNotFound(playerName));
        }
        return target;
    }

    private void pingSelf(CommandContext context) {
        Player self = context.playerSender();
        if (self == null) {
            context.reply(Messages.playersOnly());
            return;
        }
        context.reply(renderPing(self));
    }

    private void pingOther(CommandContext context) {
        Player target = Bukkit.getPlayerExact(context.arg("player"));
        if (target == null) {
            context.reply(Messages.playerNotFound(context.arg("player")));
            return;
        }
        context.reply(renderPing(target));
    }

    private Component renderPing(Player target) {
        UUID id = target.getUniqueId();
        LatencyCompensationModule compensation = compensation();
        LatencyCompensationModule.PingStats stats = compensation != null
                ? compensation.pingStats(id)
                : new LatencyCompensationModule.PingStats(null, null, 0.0, false);

        String measured = stats.pingMillis() != null
                ? Math.round(stats.pingMillis()) + "ms"
                : target.getPing() + "ms (vanilla — no probe yet)";
        String previous = stats.previousPingMillis() != null
                ? Math.round(stats.previousPingMillis()) + "ms"
                : "—";

        return Brand.line(Component.text()
                .append(Component.text(target.getName(), Brand.SECONDARY))
                .append(Component.text("  rtt=", Brand.MUTED))
                .append(Component.text(measured, Brand.ACCENT))
                .append(Component.text("  previous=", Brand.MUTED))
                .append(Component.text(previous, Brand.ACCENT))
                .append(Component.text("  jitter=", Brand.MUTED))
                .append(Component.text(String.format(Locale.ROOT, "%.1fms", stats.jitterMillis()), Brand.ACCENT))
                .append(Component.text("  spike=", Brand.MUTED))
                .append(Component.text(String.valueOf(stats.spike()),
                        stats.spike() ? Brand.FAILURE : Brand.SUCCESS))
                .append(Component.text("  strategy=", Brand.MUTED))
                .append(Component.text(services.config().compensation().probeStrategy().name(), Brand.ACCENT))
                .build());
    }

    private void debugStatus(CommandContext context) {
        var debug = services.debug();
        StringBuilder active = new StringBuilder();
        for (DebugCategory category : debug.activeCategories()) {
            if (!active.isEmpty()) {
                active.append(", ");
            }
            active.append(category.key());
        }
        context.reply(Brand.line(Component.text()
                .append(Component.text("debug ", Brand.TEXT))
                .append(Component.text(debug.enabled() ? "ENABLED" : "DISABLED",
                        debug.enabled() ? Brand.SUCCESS : Brand.FAILURE))
                .append(Component.text("  categories: ", Brand.MUTED))
                .append(Component.text(active.isEmpty() ? "none" : active.toString(), Brand.ACCENT))
                .build()));
    }

    private void setDebugEnabled(CommandContext context, boolean enabled) {
        plugin.getConfig().set("debug.enabled", enabled);
        plugin.saveConfig();
        services.debug().enabled(enabled);
        context.reply(enabled
                ? Brand.success("Debug logging enabled.")
                : Brand.failure("Debug logging disabled."));
    }

    private void setCategory(CommandContext context, boolean enabled) {
        String key = context.arg("category").toLowerCase(Locale.ROOT);
        DebugCategory category = DebugCategory.byKey(key).orElse(null);
        if (category == null) {
            context.reply(Brand.failure("Unknown debug category '" + key + "'."));
            return;
        }
        plugin.getConfig().set("debug.categories." + category.key(), enabled);
        plugin.saveConfig();
        services.debug().activate(category, enabled);
        context.reply(enabled
                ? Brand.success("Debug category '" + category.key() + "' enabled.")
                : Brand.failure("Debug category '" + category.key() + "' disabled."));
    }

    private void toggleSubscription(CommandContext context) {
        Player player = context.playerSender();
        if (player == null) {
            context.reply(Messages.playersOnly());
            return;
        }
        boolean subscribed = debugSink.toggle(player.getUniqueId());
        context.reply(subscribed
                ? Brand.success("Subscribed to in-game debug output.")
                : Brand.failure("Unsubscribed from in-game debug output."));
    }

    private void reload(CommandContext context) {
        long started = System.nanoTime();
        try {
            List<String> warnings = plugin.reloadAll();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            context.reply(Messages.reloadSucceeded(elapsedMillis, warnings));
        } catch (Exception failure) {
            context.reply(Messages.reloadFailed(failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage()));
        }
    }

    private void version(CommandContext context) {
        context.reply(Brand.line(Component.text()
                .append(Component.text("Mental " + plugin.getDescription().getVersion(), Brand.SECONDARY))
                .append(Component.newline())
                .append(Component.text("  server: ", Brand.MUTED))
                .append(Component.text(services.environment().describe(), Brand.TEXT))
                .append(Component.newline())
                .append(Component.text("  scheduling: ", Brand.MUTED))
                .append(Component.text(services.scheduling().describe(), Brand.TEXT))
                .append(Component.newline())
                .append(Component.text("  capabilities: ", Brand.MUTED))
                .append(Component.text(services.capabilities().describe(), Brand.TEXT))
                .append(Component.newline())
                .append(Component.text("  anticheat: ", Brand.MUTED))
                .append(Component.text(services.anticheatGate().describe(), Brand.TEXT))
                .build()));
    }

    private void help(CommandContext context) {
        var builder = Component.text()
                .append(Brand.prefix())
                .append(Component.text(" — latency-compensated 1.7.10 combat", Brand.MUTED));
        appendHelpRows(builder, "/mental", tree.root(), context);
        context.reply(builder.build());
    }

    private void appendHelpRows(
            net.kyori.adventure.text.TextComponent.Builder builder,
            String prefix,
            LiteralNode node,
            CommandContext context) {
        for (var child : node.children()) {
            if (!(child instanceof LiteralNode literal) || !literal.allowed(context.sender())) {
                continue;
            }
            String command = prefix + " " + literal.name();
            builder.append(Component.newline())
                    .append(Component.text("  " + command, Brand.SECONDARY)
                            .clickEvent(ClickEvent.suggestCommand(command)))
                    .append(Component.text(" — ", Brand.MUTED))
                    .append(Component.text(literal.description(), Brand.TEXT));
        }
    }

    private LatencyCompensationModule compensation() {
        return modules.byId("latency-compensation")
                .filter(LatencyCompensationModule.class::isInstance)
                .map(LatencyCompensationModule.class::cast)
                .orElse(null);
    }
}
