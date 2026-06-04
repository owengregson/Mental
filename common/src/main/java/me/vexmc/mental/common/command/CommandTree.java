package me.vexmc.mental.common.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The whole command, defined once.
 *
 * <p>Dispatch walks literals first (exact, case-insensitive), then falls to
 * the first permitted argument node, capturing its value. Running out of
 * arguments executes the deepest node's action when present, otherwise
 * replies with a usage line synthesized from that node's permitted children.
 * Completion follows the same walk and is permission-filtered at every
 * step — a player who cannot run a branch never sees it.</p>
 */
public final class CommandTree {

    private final LiteralNode root;
    private final CommandMessages messages;

    public CommandTree(@NotNull LiteralNode root, @NotNull CommandMessages messages) {
        this.root = root;
        this.messages = messages;
    }

    public @NotNull LiteralNode root() {
        return root;
    }

    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!root.allowed(sender)) {
            sender.sendMessage(messages.noPermission());
            return;
        }

        CommandNode node = root;
        Map<String, String> captured = new HashMap<>();
        List<String> path = new ArrayList<>(List.of(root.name()));

        for (String arg : args) {
            CommandNode next = matchLiteral(node, arg);
            if (next == null) {
                next = firstArgument(node, sender);
                if (next != null) {
                    captured.put(next.name(), arg);
                }
            } else if (!next.allowed(sender)) {
                sender.sendMessage(messages.noPermission());
                return;
            }
            if (next == null) {
                sender.sendMessage(node == root
                        ? messages.unknownSubcommand()
                        : messages.usage(usageOf(node, path, sender)));
                return;
            }
            node = next;
            path.add(next instanceof ArgumentNode ? "<" + next.name() + ">" : next.name());
        }

        CommandAction action = node.action();
        if (action != null) {
            action.run(new CommandContext(sender, Map.copyOf(captured)));
        } else {
            sender.sendMessage(messages.usage(usageOf(node, path, sender)));
        }
    }

    public @NotNull List<String> complete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!root.allowed(sender) || args.length == 0) {
            return List.of();
        }

        CommandNode node = root;
        for (int i = 0; i < args.length - 1; i++) {
            CommandNode next = matchLiteral(node, args[i]);
            if (next == null || !next.allowed(sender)) {
                next = firstArgument(node, sender);
            }
            if (next == null) {
                return List.of();
            }
            node = next;
        }

        String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (CommandNode child : node.children()) {
            if (!child.allowed(sender)) {
                continue;
            }
            if (child instanceof LiteralNode literal) {
                if (literal.name().startsWith(partial)) {
                    suggestions.add(literal.name());
                }
            } else if (child instanceof ArgumentNode argument) {
                for (String suggestion : argument.suggester().suggest(sender, partial)) {
                    if (suggestion.toLowerCase(Locale.ROOT).startsWith(partial)) {
                        suggestions.add(suggestion);
                    }
                }
            }
        }
        return suggestions;
    }

    private static @Nullable CommandNode matchLiteral(CommandNode node, String arg) {
        for (CommandNode child : node.children()) {
            if (child instanceof LiteralNode literal && literal.name().equalsIgnoreCase(arg)) {
                return literal;
            }
        }
        return null;
    }

    private static @Nullable ArgumentNode firstArgument(CommandNode node, CommandSender sender) {
        for (CommandNode child : node.children()) {
            if (child instanceof ArgumentNode argument && argument.allowed(sender)) {
                return argument;
            }
        }
        return null;
    }

    private static @NotNull String usageOf(CommandNode node, List<String> path, CommandSender sender) {
        StringJoiner options = new StringJoiner("|");
        for (CommandNode child : node.children()) {
            if (!child.allowed(sender)) {
                continue;
            }
            options.add(child instanceof ArgumentNode ? "<" + child.name() + ">" : child.name());
        }
        String base = "/" + String.join(" ", path);
        return options.length() == 0 ? base : base + " <" + options + ">";
    }
}
