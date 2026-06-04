package me.vexmc.mental.compat.brigadier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.common.command.ArgumentNode;
import me.vexmc.mental.common.command.CommandAction;
import me.vexmc.mental.common.command.CommandContext;
import me.vexmc.mental.common.command.CommandNode;
import me.vexmc.mental.common.command.CommandTree;
import me.vexmc.mental.common.command.LiteralNode;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Renders the declarative command tree as native Brigadier nodes
 * (Paper 1.20.6+): clients get red/green argument validation and live
 * completions, and permission-gated branches vanish from their tree
 * entirely. Loaded only behind the Brigadier capability; Java 21 bytecode
 * is safe here because every server with this API runs JVM 21+.
 */
public final class BrigadierBridge {

    private BrigadierBridge() {}

    public static void register(
            @NotNull JavaPlugin plugin,
            @NotNull CommandTree tree,
            @NotNull String description,
            @NotNull List<String> aliases) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        ((LiteralArgumentBuilder<CommandSourceStack>) toBrigadier(tree.root(), List.of())).build(),
                        description,
                        aliases));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> toBrigadier(
            CommandNode spec, List<String> argumentPath) {
        ArgumentBuilder<CommandSourceStack, ?> builder;
        List<String> path = argumentPath;

        if (spec instanceof ArgumentNode argument) {
            path = new ArrayList<>(argumentPath);
            path.add(argument.name());
            builder = Commands.argument(argument.name(), StringArgumentType.word())
                    .suggests((context, suggestions) -> {
                        String partial = suggestions.getRemaining().toLowerCase(Locale.ROOT);
                        for (String candidate : argument.suggester()
                                .suggest(context.getSource().getSender(), partial)) {
                            if (candidate.toLowerCase(Locale.ROOT).startsWith(partial)) {
                                suggestions.suggest(candidate);
                            }
                        }
                        return suggestions.buildFuture();
                    });
        } else {
            builder = Commands.literal(spec.name());
        }

        builder.requires(stack -> spec.allowed(stack.getSender()));

        CommandAction action = spec.action();
        if (action != null) {
            List<String> captured = List.copyOf(path);
            builder.executes(context -> {
                Map<String, String> arguments = new HashMap<>();
                for (String name : captured) {
                    arguments.put(name, StringArgumentType.getString(context, name));
                }
                action.run(new CommandContext(context.getSource().getSender(), Map.copyOf(arguments)));
                return Command.SINGLE_SUCCESS;
            });
        }

        for (CommandNode child : spec.children()) {
            builder.then(toBrigadier(child, path));
        }
        return builder;
    }
}
