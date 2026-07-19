package me.vexmc.mental.kernel.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * The per-(victim, attacker) override registry. Every case feeds explicit
 * {@link TickStamp}s so expiry, refresh-not-stack and hygiene are pinned without
 * a live clock.
 */
class OverrideTableTest {

    private static final UUID VICTIM = new UUID(0, 1);
    private static final UUID ATTACKER = new UUID(0, 2);
    private static final UUID OTHER_ATTACKER = new UUID(0, 3);
    private static final UUID OTHER_VICTIM = new UUID(0, 4);

    private static TickStamp at(int tick) {
        return new TickStamp(tick);
    }

    @Test
    void aLiveOverrideReadsItsClampedFactorThenLapsesToTheNoOp() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, at(100)); // live for [100, 105)

        assertEquals(0.5, table.factorFor(VICTIM, ATTACKER, at(100)), "live at the registration tick");
        assertEquals(0.5, table.factorFor(VICTIM, ATTACKER, at(104)), "live one tick before the lapse");
        assertTrue(table.isActive(VICTIM, ATTACKER, at(104)), "isActive tracks the same window");

        assertEquals(1.0, table.factorFor(VICTIM, ATTACKER, at(105)), "lapsed at t + d reads the 1.0 no-op");
        assertFalse(table.isActive(VICTIM, ATTACKER, at(105)), "and isActive is false once lapsed");
        assertEquals(0, table.size(), "the lazy read evicted the lapsed entry");
    }

    @Test
    void anAbsentPairIsTheNoOp() {
        OverrideTable table = new OverrideTable();
        assertEquals(1.0, table.factorFor(VICTIM, ATTACKER, at(0)), "no override ⇒ factor 1.0");
        assertFalse(table.isActive(VICTIM, ATTACKER, at(0)), "no override ⇒ not active");
    }

    @Test
    void aFactorAtTheCeilingIsStillAnActiveOverride() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 1.0, 5, at(0));
        assertEquals(1.0, table.factorFor(VICTIM, ATTACKER, at(1)), "a 1.0 override reads 1.0");
        assertTrue(table.isActive(VICTIM, ATTACKER, at(1)),
                "but isActive distinguishes a registered 1.0 from an absent pair");
    }

    @Test
    void theFactorIsClampedAtRegistration() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.05, 5, at(0)); // below the floor
        assertEquals(WindowPricing.MIN_FACTOR, table.factorFor(VICTIM, ATTACKER, at(1)),
                "a below-floor request is stored clamped to 0.25");
    }

    @Test
    void refreshReplacesFactorAndClockNeverStacks() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, at(100)); // would lapse at 105
        table.register(VICTIM, ATTACKER, 0.25, 5, at(120)); // refresh: new factor, new clock → lapses at 125

        assertEquals(1, table.size(), "refresh never stacks — one entry for the pair");
        assertEquals(0.25, table.factorFor(VICTIM, ATTACKER, at(121)), "the new factor is in force");
        assertTrue(table.isActive(VICTIM, ATTACKER, at(124)), "live against the refreshed clock");
        assertFalse(table.isActive(VICTIM, ATTACKER, at(125)), "and lapses at the refreshed t + d, not the old one");
    }

    @Test
    void twoAttackersOnOneVictimAreIndependentPairs() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, at(0));
        table.register(VICTIM, OTHER_ATTACKER, 0.25, 100, at(0));

        assertEquals(0.5, table.factorFor(VICTIM, ATTACKER, at(3)), "the first attacker's pair");
        assertEquals(0.25, table.factorFor(VICTIM, OTHER_ATTACKER, at(3)), "the second attacker's pair");
        // The first lapses; the second — the one a third party's math must never see — is untouched.
        assertEquals(1.0, table.factorFor(VICTIM, ATTACKER, at(10)), "the first has lapsed");
        assertEquals(0.25, table.factorFor(VICTIM, OTHER_ATTACKER, at(10)), "the second still holds");
    }

    @Test
    void clearDropsOnePairAndClearVictimDropsAllForThatVictim() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 100, at(0));
        table.register(VICTIM, OTHER_ATTACKER, 0.5, 100, at(0));
        table.register(OTHER_VICTIM, ATTACKER, 0.5, 100, at(0));

        table.clear(VICTIM, ATTACKER);
        assertFalse(table.isActive(VICTIM, ATTACKER, at(1)), "the cleared pair is gone");
        assertTrue(table.isActive(VICTIM, OTHER_ATTACKER, at(1)), "the victim's other pair survives a single clear");

        table.clearVictim(VICTIM);
        assertFalse(table.isActive(VICTIM, OTHER_ATTACKER, at(1)), "clearVictim drops every pair on the victim");
        assertTrue(table.isActive(OTHER_VICTIM, ATTACKER, at(1)), "another victim's pair is untouched");
    }

    @Test
    void sweepEvictsOnlyTheLapsedEntries() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, at(0)); // lapses at 5
        table.register(VICTIM, OTHER_ATTACKER, 0.5, 100, at(0)); // lapses at 100

        table.sweep(at(50));
        assertEquals(1, table.size(), "the short pair is swept, the long one kept");
        assertTrue(table.isActive(VICTIM, OTHER_ATTACKER, at(50)), "and the survivor is still live");
    }

    @Test
    void registrationUnderAnUnknownTickIsDeclined() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, TickStamp.NO_TICK);
        assertEquals(0, table.size(), "no tick frame ⇒ the override is not registered");
    }

    @Test
    void anUnknownTickNeverEvictsALiveEntry() {
        OverrideTable table = new OverrideTable();
        table.register(VICTIM, ATTACKER, 0.5, 5, at(100));
        // A stalled counter reading NO_TICK must not judge the entry lapsed.
        assertEquals(0.5, table.factorFor(VICTIM, ATTACKER, TickStamp.NO_TICK),
                "an unknown now holds the entry rather than dropping it");
        assertEquals(1, table.size(), "and nothing is evicted under an unknown tick");
    }
}
