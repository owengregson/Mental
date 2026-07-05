package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dynamic {@link Command} registration against the live server command map, at
 * the platform seam so the version-fragile reflection is resolved ONCE and cached
 * (the presence-probe idiom, never a version parse).
 *
 * <p>Why dynamic and not {@code plugin.yml}: a command declared statically in
 * {@code plugin.yml} always exists in the command map and always tab-completes,
 * even when its owning feature is off — that would violate zero-touch. Registering
 * on enable and unregistering on disable leaves a disabled feature with NO
 * command-map or tab-complete trace.</p>
 *
 * <h2>Cross-version mechanics</h2>
 * <ul>
 *   <li><b>The map</b> — {@code Server#getCommandMap()} is on the Paper interface
 *       from 1.13+ but the {@code CraftServer} implementation has always exposed
 *       it; we resolve the getter reflectively off the running server class, so it
 *       works on every version without compiling against CraftBukkit.</li>
 *   <li><b>Register</b> — {@link CommandMap#register(String, Command)} with the
 *       plugin name as the fallback prefix, which also creates the
 *       {@code prefix:label} namespaced forms and every alias's forms.</li>
 *   <li><b>Unregister</b> — {@link Command#unregister(CommandMap)} plus a sweep of
 *       the {@code knownCommands} map removing every key that maps to this command
 *       instance (label, aliases, and all their namespaced forms), so nothing is
 *       left behind.</li>
 *   <li><b>Client refresh</b> — {@code Player#updateCommands()} (1.13+, name-probed)
 *       is pushed to online players after a change so the client command tree
 *       matches; below 1.13 it is a no-op (legacy tab-complete is server-driven).</li>
 * </ul>
 */
public final class CommandMaps {

    private static final @Nullable Method GET_COMMAND_MAP = resolveGetCommandMap();
    private static final @Nullable Method UPDATE_COMMANDS = resolveUpdateCommands();

    private CommandMaps() {}

    /** Whether the server exposes a command map at all (true on every supported version). */
    public static boolean supported() {
        return commandMap() != null;
    }

    /**
     * Registers {@code command} with {@code plugin}'s name as the fallback prefix,
     * then refreshes online clients. Returns false when no command map is reachable
     * (the feature then logs and stays inert). Idempotent-safe: a re-register of the
     * same label simply overwrites the aliases.
     */
    public static boolean register(@NotNull Plugin plugin, @NotNull Command command) {
        CommandMap map = commandMap();
        if (map == null) {
            return false;
        }
        map.register(plugin.getName().toLowerCase(java.util.Locale.ROOT), command);
        updateCommandsForOnlinePlayers();
        return true;
    }

    /**
     * Unregisters {@code command} from the map — deactivating it and stripping every
     * {@code knownCommands} key that maps to it (label, aliases, namespaced forms) —
     * then refreshes online clients. Safe to call when it was never registered.
     */
    public static void unregister(@NotNull Command command) {
        CommandMap map = commandMap();
        if (map == null) {
            return;
        }
        command.unregister(map);
        Map<String, Command> known = knownCommands(map);
        if (known != null) {
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, Command> entry : known.entrySet()) {
                if (entry.getValue() == command) {
                    stale.add(entry.getKey());
                }
            }
            for (String key : stale) {
                known.remove(key);
            }
        }
        updateCommandsForOnlinePlayers();
    }

    /** The command currently bound to {@code label} in the map, or {@code null} — the zero-touch probe. */
    public static @Nullable Command find(@NotNull String label) {
        CommandMap map = commandMap();
        if (map == null) {
            return null;
        }
        Map<String, Command> known = knownCommands(map);
        if (known != null) {
            Command byKey = known.get(label.toLowerCase(java.util.Locale.ROOT));
            if (byKey != null) {
                return byKey;
            }
        }
        return map.getCommand(label);
    }

    /* ------------------------------------------------------------------ */

    private static @Nullable CommandMap commandMap() {
        if (GET_COMMAND_MAP == null) {
            return null;
        }
        try {
            Object result = GET_COMMAND_MAP.invoke(Bukkit.getServer());
            return result instanceof CommandMap map ? map : null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Command> knownCommands(CommandMap map) {
        // getKnownCommands() exists on newer SimpleCommandMap; fall back to the field.
        try {
            Method getter = map.getClass().getMethod("getKnownCommands");
            Object result = getter.invoke(map);
            if (result instanceof Map<?, ?> knownMap) {
                return (Map<String, Command>) knownMap;
            }
        } catch (ReflectiveOperationException noGetter) {
            // Fall through to the field walk.
        }
        Field field = findField(map.getClass(), "knownCommands");
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(map);
            return value instanceof Map<?, ?> knownMap ? (Map<String, Command>) knownMap : null;
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return null;
        }
    }

    private static void updateCommandsForOnlinePlayers() {
        if (UPDATE_COMMANDS == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                UPDATE_COMMANDS.invoke(player);
            } catch (ReflectiveOperationException ignored) {
                // Best effort — a client that cannot be refreshed keeps its old tree.
            }
        }
    }

    private static @Nullable Method resolveGetCommandMap() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException absent) {
            // Walk the hierarchy for a declared getCommandMap (older CraftServer shapes).
            Class<?> type = Bukkit.getServer().getClass();
            while (type != null) {
                try {
                    Method declared = type.getDeclaredMethod("getCommandMap");
                    declared.setAccessible(true);
                    return declared;
                } catch (NoSuchMethodException next) {
                    type = type.getSuperclass();
                } catch (RuntimeException inaccessible) {
                    return null;
                }
            }
            return null;
        }
    }

    private static @Nullable Method resolveUpdateCommands() {
        try {
            return Player.class.getMethod("updateCommands");
        } catch (NoSuchMethodException belowThirteen) {
            return null;
        }
    }

    private static @Nullable Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException next) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
