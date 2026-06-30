package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the clock-free per-victim carrier that bridges the HIGH apply handler and
 * the MONITOR ledger-record handler of one {@code PlayerVelocityEvent}.
 *
 * <p>The carrier replaces a 25&nbsp;ms wall-clock TTL that could strand the tag
 * across a longer GC pause between the two handlers. It also keeps the
 * per-victim scoping the TTL'd map had, which a per-thread {@code ThreadLocal}
 * would have lost (a nested velocity event for a different victim would clobber
 * it).</p>
 */
class AppliedTagStoreTest {

    @Test
    void emptyByDefault() {
        assertNull(new AppliedTagStore().takeFor(UUID.randomUUID()));
    }

    @Test
    void carriesAcrossThePriorityGapWithoutAClock() {
        // HIGH sets; MONITOR (same thread, any wall-clock later — there is no TTL)
        // takes. A GC pause between the two handlers cannot strand the tag.
        AppliedTagStore store = new AppliedTagStore();
        UUID victim = UUID.randomUUID();
        AppliedTag tag = new AppliedTag(KnockbackPipeline.Cause.MELEE, true);
        store.setFor(victim, tag);
        assertSame(tag, store.takeFor(victim));
    }

    @Test
    void takeConsumesSoASecondReadIsEmpty() {
        AppliedTagStore store = new AppliedTagStore();
        UUID victim = UUID.randomUUID();
        store.setFor(victim, new AppliedTag(KnockbackPipeline.Cause.ROD, false));
        store.takeFor(victim);
        assertNull(store.takeFor(victim), "the MONITOR handler consumes the tag exactly once");
    }

    @Test
    void clearAtHandlerEntryDropsAStaleTag() {
        // A tag leaked by a prior event cancelled between HIGH and MONITOR is
        // dropped when this victim's next velocity event passes HIGH (clear),
        // before its MONITOR could read it.
        AppliedTagStore store = new AppliedTagStore();
        UUID victim = UUID.randomUUID();
        store.setFor(victim, new AppliedTag(KnockbackPipeline.Cause.MELEE, true));
        store.clearFor(victim);
        assertNull(store.takeFor(victim));
    }

    @Test
    void perVictimScopingSurvivesACrossVictimNestedEvent() {
        // Outer event for victim A reaches HIGH and sets tag_A. A third-party
        // HIGHEST handler calls victimB.setVelocity, firing a NESTED velocity
        // event whose HIGH clears+sets and whose MONITOR takes tag_B. Back in the
        // outer event's MONITOR, victim A's tag must still be present — the
        // regression a per-thread ThreadLocal would have introduced.
        AppliedTagStore store = new AppliedTagStore();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AppliedTag tagA = new AppliedTag(KnockbackPipeline.Cause.MELEE, true);
        store.setFor(a, tagA);

        store.clearFor(b);
        AppliedTag tagB = new AppliedTag(KnockbackPipeline.Cause.ARROW, false);
        store.setFor(b, tagB);
        assertSame(tagB, store.takeFor(b), "the nested event consumes its own victim's tag");

        assertSame(tagA, store.takeFor(a), "the outer victim's tag is untouched by the nested event");
    }
}
