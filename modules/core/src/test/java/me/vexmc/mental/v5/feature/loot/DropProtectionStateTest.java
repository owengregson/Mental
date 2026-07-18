package me.vexmc.mental.v5.feature.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;
import org.junit.jupiter.api.Test;

/**
 * The pure protection registry: killer-only pickup, per-tick expiry, and the
 * forget/drain bookkeeping — all with no Bukkit or PacketEvents in sight.
 */
class DropProtectionStateTest {

    private static final UUID KILLER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Test
    void onlyTheKillerMayPickUpAProtectedDrop() {
        DropProtectionState state = new DropProtectionState();
        state.protect(7, UUID.randomUUID(), KILLER, 100, GlowColor.GOLD);

        assertTrue(state.mayPickup(7, KILLER), "the killer may pick up their reserved loot");
        assertFalse(state.mayPickup(7, VICTIM), "the victim is blocked during the window");
        assertFalse(state.mayPickup(7, THIRD), "a third party is blocked during the window");
    }

    @Test
    void anUnprotectedDropIsPickableByAnyone() {
        DropProtectionState state = new DropProtectionState();
        assertTrue(state.mayPickup(999, VICTIM), "an untracked item is free for all");
        assertFalse(state.isProtected(999));
    }

    @Test
    void expireReturnsAndRemovesOnlyElapsedEntries() {
        DropProtectionState state = new DropProtectionState();
        state.protect(1, UUID.randomUUID(), KILLER, 50, GlowColor.GOLD);
        state.protect(2, UUID.randomUUID(), KILLER, 150, GlowColor.YELLOW);

        List<DropProtectionState.Protected> expired = state.expire(100);
        assertEquals(1, expired.size(), "only the entry due at/under tick 100 expires");
        assertEquals(1, expired.get(0).entityId());
        assertFalse(state.isProtected(1), "the expired entry is forgotten");
        assertTrue(state.isProtected(2), "the later entry stays protected");
    }

    @Test
    void forgetRemovesAndReturnsTheEntry() {
        DropProtectionState state = new DropProtectionState();
        state.protect(5, UUID.randomUUID(), KILLER, 100, GlowColor.GOLD);
        DropProtectionState.Protected gone = state.forget(5);
        assertEquals(KILLER, gone.killerId());
        assertFalse(state.isProtected(5));
        assertNull(state.forget(5), "forgetting again returns null");
    }

    @Test
    void drainRemovesEverythingForTeardown() {
        DropProtectionState state = new DropProtectionState();
        state.protect(1, UUID.randomUUID(), KILLER, 100, GlowColor.GOLD);
        state.protect(2, UUID.randomUUID(), KILLER, 100, GlowColor.GOLD);
        assertEquals(2, state.drain().size());
        assertEquals(0, state.size(), "drained clean");
    }
}
