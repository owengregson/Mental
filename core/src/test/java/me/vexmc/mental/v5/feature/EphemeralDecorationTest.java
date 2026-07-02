package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.platform.SwordBlockAdapter;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The B12 guaranteed-revert contract for {@link EphemeralDecoration}, unit-side.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li><b>Tier selection.</b> With no component classes on the unit classpath
 *       the adapter probes to {@code NONE}, so the service runs the off-hand
 *       (real-shield) tier — the fallback whose revert logic this class owns.</li>
 *   <li><b>Nothing sticks / nothing throws.</b> Every exit trigger — held-slot
 *       change, swap-hands, drop, off-hand inventory click, death (drop-list and
 *       keep-inventory), world change, quit, {@code disableAll}, {@code forget}
 *       — is a safe no-op when no decoration is tracked. This locks the ordering
 *       that makes the never-stuck guarantee hold: each exit path checks tracked
 *       state BEFORE it would read the temp-shield PDC, so a fresh player is
 *       reverted to itself and no path NPEs.</li>
 * </ul>
 *
 * <h2>Why the "apply-then-revert" proof is not here</h2>
 * <p>Applying a real decoration needs a running server: the off-hand tier mints
 * a PDC-marked shield ({@code ItemStack#getItemMeta} → {@code Bukkit.getItemFactory()}),
 * and the component tiers write item components — both unavailable in the unit
 * environment (no Bukkit server, and this project ships no Mockito/MockBukkit).
 * The end-to-end apply-then-assert-revert proof therefore lives in the live
 * {@code BlockingSuite} on the real matrix, which injects the off-hand shield on
 * a real server and asserts an exit trigger restores the original item. This unit
 * test pins the orchestration/no-leak contract the live suite cannot cheaply
 * enumerate per trigger.</p>
 */
class EphemeralDecorationTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private EphemeralDecoration decoration() {
        Consumer<String> log = message -> {};
        // The floor API resolves the adapter to NONE (no component classes) → the
        // off-hand tier, the fallback whose revert logic is under test.
        SwordBlockAdapter adapter = SwordBlockAdapter.probe(
                ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT"), log);
        return new EphemeralDecoration(plugin(List.of()), noopScheduling(), adapter);
    }

    @Test
    void resolvesToTheOffHandTierWithNoComponentClasses() {
        EphemeralDecoration decoration = decoration();
        assertTrue(decoration.offhandTier(),
                "with no component classes the service runs the off-hand shield fallback");
        assertFalse(decoration.nativeReduction(),
                "the off-hand tier applies software reduction, never native");
    }

    @Test
    void everyExitTriggerRevertsToNothingWhenNoDecorationIsTracked() {
        EphemeralDecoration decoration = decoration();
        Player player = player(PLAYER);
        ItemStack stone = new ItemStack(Material.STONE);
        ItemStack shield = new ItemStack(Material.SHIELD);
        int[] writeBacks = {0};

        // Nothing is decorated to begin with — the never-stuck baseline.
        assertFalse(decoration.isBlockingWithTempShield(player), "a fresh player tracks no temp shield");

        assertDoesNotThrow(() -> {
            decoration.onHeldChange(player, 0, 1);
            decoration.onWorldChange(player);
            decoration.onQuit(player);
            decoration.onDeath(player, false, new ArrayList<>(List.of(stone))); // drop-list rewrite path
            decoration.onDeath(player, true, new ArrayList<>(List.of(stone)));  // keep-inventory path
            decoration.onDrop(player, stone, () -> writeBacks[0]++);            // non-shield drop
            decoration.onDrop(player, shield, () -> writeBacks[0]++);           // shield drop
            decoration.forget(PLAYER);
        }, "every exit trigger must be a safe no-op when no decoration is tracked");

        // The cancel-returning triggers report "not ours" — nothing to cancel or restore.
        assertFalse(decoration.onSwapHands(player), "swap-hands cancels nothing when no temp shield is tracked");
        assertFalse(decoration.onOffhandClick(player), "off-hand click cancels nothing when nothing is tracked");

        // Nothing was written back and nothing stuck.
        assertFalse(decoration.isBlockingWithTempShield(player),
                "no exit path may leave a tracked temp shield behind");
    }

    @Test
    void disableAllAndForgetAreSafeWithNoTrackedState() {
        EphemeralDecoration empty = new EphemeralDecoration(
                plugin(List.of()), noopScheduling(),
                SwordBlockAdapter.probe(ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT"), m -> {}));
        assertDoesNotThrow(empty::disableAll, "disableAll over an empty server is a no-op");

        // With an online player but no tracked decoration, disableAll strips hands
        // (a no-op on the off-hand tier) and touches nothing — never throws.
        Player online = player(PLAYER);
        EphemeralDecoration withPlayer = new EphemeralDecoration(
                plugin(List.of(online)), noopScheduling(),
                SwordBlockAdapter.probe(ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT"), m -> {}));
        assertDoesNotThrow(withPlayer::disableAll,
                "disableAll with an undecorated online player leaves the inventory untouched");
        assertDoesNotThrow(() -> withPlayer.forget(PLAYER), "forget on an untracked player is a no-op");
    }

    /* ------------------------------------------------------------------ */
    /*  Interface stubs (dynamic proxies) — no Bukkit server required.     */
    /* ------------------------------------------------------------------ */

    private static Plugin plugin(List<Player> online) {
        Server server = (Server) Proxy.newProxyInstance(
                EphemeralDecorationTest.class.getClassLoader(), new Class[]{Server.class},
                (proxy, method, args) -> "getOnlinePlayers".equals(method.getName())
                        ? online
                        : defaultValue(method, proxy, args));
        return (Plugin) Proxy.newProxyInstance(
                EphemeralDecorationTest.class.getClassLoader(), new Class[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "MentalTest";
                    case "getServer" -> server;
                    default -> defaultValue(method, proxy, args);
                });
    }

    /** A player whose off-hand starts empty (AIR) — enough for the strip-hands read path. */
    private static Player player(UUID id) {
        ItemStack air = new ItemStack(Material.AIR);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Object inventory = Proxy.newProxyInstance(
                EphemeralDecorationTest.class.getClassLoader(),
                new Class[]{org.bukkit.inventory.PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInMainHand" -> sword;
                    case "getItemInOffHand" -> air;
                    case "getItem" -> air;
                    default -> defaultValue(method, proxy, args);
                });
        return (Player) Proxy.newProxyInstance(
                EphemeralDecorationTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getInventory" -> inventory;
                    default -> defaultValue(method, proxy, args);
                });
    }

    /**
     * A scheduling surface stub. The off-hand exit paths under test short-circuit
     * on tracked state before ever scheduling, so no method here is actually
     * invoked; a proxy keeps the stub from drifting as the interface evolves. Its
     * one entity method that could matter — {@code isOwnedByCurrentRegion} — reads
     * true (single region) via the primitive default.
     */
    private static Scheduling noopScheduling() {
        return (Scheduling) Proxy.newProxyInstance(
                EphemeralDecorationTest.class.getClassLoader(), new Class[]{Scheduling.class},
                (proxy, method, args) -> {
                    if ("isOwnedByCurrentRegion".equals(method.getName())) {
                        return true;
                    }
                    return defaultValue(method, proxy, args);
                });
    }

    /** Sensible defaults for un-stubbed proxy calls (Object identity + typed zeroes). */
    private static Object defaultValue(java.lang.reflect.Method method, Object proxy, Object[] args) {
        switch (method.getName()) {
            case "toString": return method.getDeclaringClass().getSimpleName() + "-stub";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return proxy == args[0];
            default:         return primitiveZero(method.getReturnType());
        }
    }

    private static Object primitiveZero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
