package me.vexmc.mental.v5.debug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.debug.DebugCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The registry + zero-touch contract for {@link PlayerDebugSink}, unit-side.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li><b>Empty-set zero-touch.</b> With nobody subscribed, {@code accept} returns
 *       before it renders a line, resolves a player, or schedules anything — so a
 *       live debug channel with no subscribers is byte-identical to having no
 *       player sink at all. Proven by a scheduling spy that is never invoked and by
 *       the call never reaching {@code Bukkit.getPlayer} (which would NPE with no
 *       server), so it does not throw.</li>
 *   <li><b>Selection / registry.</b> {@code toggle} adds then removes a UUID and
 *       reports the new state; {@code isSubscribed} reflects exactly the set
 *       {@code accept} fans out over (a subscribed UUID is selected, an unrelated
 *       one is not); {@code forget} drops a subscription (the quit contract).</li>
 * </ul>
 *
 * <h2>Why the delivery hop is not asserted here</h2>
 * <p>The actual per-line fan-out resolves each subscriber via {@code
 * Bukkit.getPlayer(UUID)} and hops onto the owning region thread via {@code
 * Scheduling.runOn} — both need a running server (this project ships no
 * Mockito/MockBukkit and does not touch the static {@code Bukkit} holder in unit
 * tests). The end-to-end "a subscribed admin receives the line in chat" proof
 * therefore belongs to the live matrix; this unit test pins the selection/registry
 * logic and the empty-set short-circuit that the live suite cannot cheaply
 * enumerate.</p>
 */
class PlayerDebugSinkTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void acceptOverAnEmptySetSchedulesNothingAndNeverThrows() {
        AtomicInteger scheduleCalls = new AtomicInteger();
        PlayerDebugSink sink = new PlayerDebugSink(countingScheduling(scheduleCalls));

        // No subscribers ⇒ the early return fires before any render, player
        // resolution, or scheduling. Reaching Bukkit.getPlayer would NPE (no server
        // in the unit env), so "does not throw" also proves it short-circuited.
        assertDoesNotThrow(() -> sink.accept(DebugCategory.KNOCKBACK, "grounded launch v=0.42"));
        assertEquals(0, scheduleCalls.get(), "an empty subscriber set must never schedule a delivery hop");
    }

    @Test
    void toggleSubscribesThenUnsubscribesAndReportsTheNewState() {
        PlayerDebugSink sink = new PlayerDebugSink(countingScheduling(new AtomicInteger()));
        Player alice = player(ALICE);

        assertFalse(sink.isSubscribed(alice), "a fresh player is not subscribed");
        assertTrue(sink.toggle(alice), "the first toggle subscribes and returns the new (subscribed) state");
        assertTrue(sink.isSubscribed(alice), "the UUID is now selected for fan-out");
        assertTrue(sink.isSubscribed(ALICE), "the UUID overload agrees with the Player overload");

        assertFalse(sink.toggle(alice), "the second toggle unsubscribes and returns the new (off) state");
        assertFalse(sink.isSubscribed(alice), "the UUID is no longer selected for fan-out");
    }

    @Test
    void isSubscribedTracksExactlyTheSelectedUuids() {
        PlayerDebugSink sink = new PlayerDebugSink(countingScheduling(new AtomicInteger()));
        sink.toggle(player(ALICE));

        // The subscribed UUID is the one accept() would fan out to; an unrelated
        // UUID is not — the selection is exactly the registry membership.
        assertTrue(sink.isSubscribed(ALICE), "the subscribed UUID is selected for fan-out");
        assertFalse(sink.isSubscribed(BOB), "an unsubscribed UUID is never selected");
    }

    @Test
    void forgetClearsASubscription() {
        PlayerDebugSink sink = new PlayerDebugSink(countingScheduling(new AtomicInteger()));
        sink.toggle(player(ALICE));
        assertTrue(sink.isSubscribed(ALICE));

        sink.forget(ALICE);
        assertFalse(sink.isSubscribed(ALICE), "forget (the per-quit hook) drops the subscription");
        // Forgetting an already-absent UUID is a harmless no-op (the quit race).
        assertDoesNotThrow(() -> sink.forget(BOB));
    }

    /* ------------------------------------------------------------------ */
    /*  Interface stubs (dynamic proxies) — no Bukkit server required.     */
    /* ------------------------------------------------------------------ */

    /** A scheduling surface that counts {@code runOn} invocations; nothing else is exercised. */
    private static Scheduling countingScheduling(AtomicInteger runOnCalls) {
        return (Scheduling) Proxy.newProxyInstance(
                PlayerDebugSinkTest.class.getClassLoader(), new Class[]{Scheduling.class},
                (proxy, method, args) -> {
                    if ("runOn".equals(method.getName())) {
                        runOnCalls.incrementAndGet();
                        return null;
                    }
                    return defaultValue(method, proxy, args);
                });
    }

    /** A player whose only exercised method is {@code getUniqueId()}. */
    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                PlayerDebugSinkTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> "getUniqueId".equals(method.getName())
                        ? id
                        : defaultValue(method, proxy, args));
    }

    private static Object defaultValue(Method method, Object proxy, Object[] args) {
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
