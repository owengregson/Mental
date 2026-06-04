package me.vexmc.mental.common.command;

import static me.vexmc.mental.common.command.ArgumentNode.argument;
import static me.vexmc.mental.common.command.LiteralNode.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class CommandTreeTest {

    /** Records which failure message was sent, as a plain marker string. */
    private static final CommandMessages MESSAGES = new CommandMessages() {
        @Override
        public Component noPermission() {
            return Component.text("no-permission");
        }

        @Override
        public Component unknownSubcommand() {
            return Component.text("unknown");
        }

        @Override
        public Component usage(String usage) {
            return Component.text("usage:" + usage);
        }
    };

    private final List<String> received = new ArrayList<>();
    private final Set<String> permissions = new HashSet<>();

    private CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> args != null
                            && args[0] instanceof String permission
                            && permissions.contains(permission);
                    case "sendMessage" -> {
                        if (args != null && args[0] instanceof Component component
                                && component instanceof net.kyori.adventure.text.TextComponent text) {
                            received.add(text.content());
                        }
                        yield null;
                    }
                    case "getName", "name" -> "tester";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeSender";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }

    private CommandTree tree(AtomicReference<CommandContext> executed) {
        LiteralNode root = literal("mental")
                .describe("Root")
                .runs(context -> received.add("dashboard"))
                .then(literal("reload")
                        .permission("mental.command.reload")
                        .describe("Reload")
                        .runs(context -> received.add("reloaded"))
                        .build())
                .then(literal("module")
                        .permission("mental.command.module")
                        .describe("Modules")
                        .then(argument("name", (sender, partial) -> List.of("knockback", "hit-registration"))
                                .then(literal("on").runs(executed::set).build())
                                .then(literal("status").runs(executed::set).build())
                                .build())
                        .build())
                .then(literal("ping").describe("Ping").runs(executed::set).build())
                .build();
        return new CommandTree(root, MESSAGES);
    }

    @Test
    void bareRootRunsTheDashboard() {
        tree(new AtomicReference<>()).execute(sender(), new String[0]);
        assertEquals(List.of("dashboard"), received);
    }

    @Test
    void routesLiteralsAndRunsActions() {
        permissions.add("mental.command.reload");
        tree(new AtomicReference<>()).execute(sender(), new String[] {"reload"});
        assertEquals(List.of("reloaded"), received);
    }

    @Test
    void capturesArgumentsAlongThePath() {
        permissions.add("mental.command.module");
        AtomicReference<CommandContext> executed = new AtomicReference<>();

        tree(executed).execute(sender(), new String[] {"module", "knockback", "on"});

        assertEquals("knockback", executed.get().arg("name"));
    }

    @Test
    void deniedLiteralSaysNoPermission() {
        tree(new AtomicReference<>()).execute(sender(), new String[] {"reload"});
        assertEquals(List.of("no-permission"), received);
    }

    @Test
    void unknownSubcommandIsReported() {
        tree(new AtomicReference<>()).execute(sender(), new String[] {"bogus"});
        assertEquals(List.of("unknown"), received);
    }

    @Test
    void nonExecutableNodeSynthesizesUsage() {
        permissions.add("mental.command.module");
        tree(new AtomicReference<>()).execute(sender(), new String[] {"module"});
        assertEquals(1, received.size());
        assertTrue(received.get(0).startsWith("usage:/mental module"), received.get(0));
        assertTrue(received.get(0).contains("<name>"), received.get(0));
    }

    @Test
    void completionFiltersByPrefixAndPermission() {
        CommandTree tree = tree(new AtomicReference<>());

        assertEquals(List.of("ping"), tree.complete(sender(), new String[] {"p"}));
        assertEquals(List.of(), tree.complete(sender(), new String[] {"rel"})); // hidden without permission

        permissions.add("mental.command.reload");
        assertEquals(List.of("reload"), tree.complete(sender(), new String[] {"rel"}));
    }

    @Test
    void completionSuggestsArgumentValues() {
        permissions.add("mental.command.module");
        CommandTree tree = tree(new AtomicReference<>());

        assertEquals(List.of("knockback", "hit-registration"),
                tree.complete(sender(), new String[] {"module", ""}));
        assertEquals(List.of("knockback"),
                tree.complete(sender(), new String[] {"module", "kno"}));
        assertEquals(List.of("on", "status"),
                tree.complete(sender(), new String[] {"module", "knockback", ""}));
    }
}
