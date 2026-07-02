package me.vexmc.mental.platform.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DebugLogTest {

    @Test
    void supplierNeverInvokedWhenDisabled() {
        DebugLog log = new DebugLog();
        AtomicInteger evaluations = new AtomicInteger();

        log.log(DebugCategory.HITREG, () -> {
            evaluations.incrementAndGet();
            return "expensive";
        });

        log.enabled(true); // enabled but category inactive
        log.log(DebugCategory.HITREG, () -> {
            evaluations.incrementAndGet();
            return "expensive";
        });

        assertEquals(0, evaluations.get());
    }

    @Test
    void rendersOnceAndFansOutWhenActive() {
        DebugLog log = new DebugLog();
        List<String> received = new CopyOnWriteArrayList<>();
        log.addSink((category, message) -> received.add(category.key() + ":" + message));
        log.addSink((category, message) -> received.add("second:" + message));

        log.enabled(true);
        log.activate(DebugCategory.KNOCKBACK, true);

        AtomicInteger evaluations = new AtomicInteger();
        log.log(DebugCategory.KNOCKBACK, () -> "v=" + evaluations.incrementAndGet());

        assertEquals(1, evaluations.get());
        assertEquals(List.of("knockback:v=1", "second:v=1"), received);
    }

    @Test
    void categoryTogglingIsIndependent() {
        DebugLog log = new DebugLog();
        log.enabled(true);
        log.activate(DebugCategory.FISHING, true);
        log.activate(DebugCategory.PACKETS, true);
        log.activate(DebugCategory.FISHING, false);

        assertFalse(log.active(DebugCategory.FISHING));
        assertTrue(log.active(DebugCategory.PACKETS));
        assertFalse(log.scoped(DebugCategory.FISHING).active());
        assertTrue(log.scoped(DebugCategory.PACKETS).active());
    }

    @Test
    void categoryKeysRoundTrip() {
        for (DebugCategory category : DebugCategory.values()) {
            assertEquals(category, DebugCategory.byKey(category.key()).orElseThrow());
        }
        assertTrue(DebugCategory.byKey("nonsense").isEmpty());
    }
}
