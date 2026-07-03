package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-version player-ping accessor.
 *
 * <p>{@code Player#getPing()} is a Bukkit method only from 1.16.5; below it — the legacy backport reaches
 * to 1.9.4 — it is absent. The Spigot audience method {@code player.spigot().getPing()} has existed since
 * 1.9, and the NMS {@code EntityPlayer.ping} int field predates even that. The usable accessor is chosen
 * ONCE at class load (never a version parse) and every call takes the resolved path:</p>
 *
 * <ol>
 *   <li>{@code Player#getPing()} — modern, non-deprecated (1.16.5+);</li>
 *   <li>{@code player.spigot().getPing()} — the Spigot fallback (1.9+, all our targets);</li>
 *   <li>the NMS {@code ping} field on the player handle — last resort.</li>
 * </ol>
 *
 * <p>The unresolved branches' bytecode is never executed, so a method absent on the running server (e.g.
 * {@code Player#getPing()} on 1.9.4) is never linked. If nothing resolves, ping reads 0 — a benign value
 * the latency model treats as "no measured round-trip".</p>
 */
public final class Pings {

    private enum Source { PLAYER_GET_PING, SPIGOT_GET_PING, NMS_FIELD, NONE }

    private static final Source SOURCE = resolve();

    private Pings() {}

    /** The player's ping in milliseconds via the boot-resolved accessor, or 0 when none resolved. */
    public static int of(@NotNull Player player) {
        switch (SOURCE) {
            case PLAYER_GET_PING:
                return player.getPing();
            case SPIGOT_GET_PING:
                return player.spigot().getPing();
            case NMS_FIELD:
                return nmsPing(player);
            case NONE:
            default:
                return 0;
        }
    }

    /** The resolved accessor's name — for the boot report and the chain-selection unit pin. */
    public static @NotNull String describe() {
        return switch (SOURCE) {
            case PLAYER_GET_PING -> "Player#getPing()";
            case SPIGOT_GET_PING -> "player.spigot().getPing()";
            case NMS_FIELD -> "nms handle.ping";
            case NONE -> "none (ping reads 0)";
        };
    }

    private static Source resolve() {
        if (methodPresent(Player.class, "getPing")) {
            return Source.PLAYER_GET_PING;
        }
        if (methodPresent(Player.Spigot.class, "getPing")) {
            return Source.SPIGOT_GET_PING;
        }
        if (nmsPingField() != null) {
            return Source.NMS_FIELD;
        }
        return Source.NONE;
    }

    private static boolean methodPresent(@NotNull Class<?> owner, @NotNull String name) {
        try {
            owner.getMethod(name);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static int nmsPing(@NotNull Player player) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            Field ping = nmsPingField();
            if (handle != null && ping != null) {
                Object value = ping.get(handle);
                if (value instanceof Integer measured) {
                    return measured;
                }
            }
        } catch (ReflectiveOperationException absent) {
            // Best-effort — a mapping we do not know degrades to 0.
        }
        return 0;
    }

    /**
     * The spigot-mapped {@code ping} field on the online player's NMS handle, or {@code null} when the
     * handle class or the field is not where legacy CraftBukkit put it. Resolved by walking a synthetic
     * handle is not possible without a player, so this only inspects the CraftPlayer→handle field lazily
     * at call time; here it just proves the field exists for the boot-time chain selection.
     */
    private static Field nmsPingField() {
        try {
            Class<?> craftPlayer = Class.forName(
                    "org.bukkit.craftbukkit." + nmsPackageVersion() + ".entity.CraftPlayer");
            Method getHandle = craftPlayer.getMethod("getHandle");
            Class<?> handleType = getHandle.getReturnType();
            Field ping = handleType.getField("ping");
            ping.setAccessible(true);
            return ping;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static @NotNull String nmsPackageVersion() {
        String pkg = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
        int lastDot = pkg.lastIndexOf('.');
        return lastDot >= 0 ? pkg.substring(lastDot + 1) : pkg;
    }
}
