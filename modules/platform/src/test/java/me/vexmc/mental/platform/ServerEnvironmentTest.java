package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerEnvironmentTest {

    @Test
    void parsesLegacySnapshotVersion() {
        ServerEnvironment env = ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT");
        assertTrue(env.recognized());
        assertEquals(1, env.major());
        assertEquals(17, env.minor());
        assertEquals(1, env.patch());
        assertFalse(env.yearScheme());
    }

    @Test
    void parsesYearSchemeVersion() {
        ServerEnvironment env = ServerEnvironment.parse("26.1.2-R0.1-SNAPSHOT");
        assertTrue(env.recognized());
        assertEquals(26, env.major());
        assertEquals(1, env.minor());
        assertEquals(2, env.patch());
        assertTrue(env.yearScheme());
    }

    @Test
    void parsesTwoComponentVersion() {
        ServerEnvironment env = ServerEnvironment.parse("1.21-R0.1-SNAPSHOT");
        assertTrue(env.recognized());
        assertEquals(0, env.patch());
    }

    @Test
    void yearSchemeOrdersAboveEveryLegacyVersion() {
        ServerEnvironment year = ServerEnvironment.parse("26.0");
        assertTrue(year.isAtLeast(1, 21, 11));
        assertTrue(year.isAtLeast(1, 17, 0));

        ServerEnvironment legacy = ServerEnvironment.parse("1.21.11");
        assertFalse(legacy.isAtLeast(26, 0, 0));
    }

    @Test
    void isAtLeastComparesFullTriple() {
        ServerEnvironment env = ServerEnvironment.parse("1.20.6");
        assertTrue(env.isAtLeast(1, 20, 6));
        assertTrue(env.isAtLeast(1, 20, 4));
        assertTrue(env.isAtLeast(1, 19, 4));
        assertFalse(env.isAtLeast(1, 20, 7));
        assertFalse(env.isAtLeast(1, 21, 0));
    }

    @Test
    void malformedInputIsUnrecognizedFloor() {
        ServerEnvironment env = ServerEnvironment.parse("paperweight");
        assertFalse(env.recognized());
        assertFalse(env.isAtLeast(1, 0, 0));
        assertEquals("unrecognized (paperweight)", env.describe());
    }

    @Test
    void parsesEveryLegacyBackportVersionString() {
        // The seven viable legacy targets, in their Bukkit.getBukkitVersion() shape (Q5). Each must parse
        // to its exact triple, recognized and on the legacy scheme — the backport's version-band decisions
        // (e.g. the 1.13 flattening floor, the 1.14 PDC floor) read these.
        assertLegacy("1.9.4-R0.1-SNAPSHOT", 1, 9, 4);
        assertLegacy("1.10.2-R0.1-SNAPSHOT", 1, 10, 2);
        assertLegacy("1.11.2-R0.1-SNAPSHOT", 1, 11, 2);
        assertLegacy("1.12.2-R0.1-SNAPSHOT", 1, 12, 2);
        assertLegacy("1.13.2-R0.1-SNAPSHOT", 1, 13, 2);
        assertLegacy("1.15.2-R0.1-SNAPSHOT", 1, 15, 2);
        assertLegacy("1.16.5-R0.1-SNAPSHOT", 1, 16, 5);
    }

    @Test
    void legacyVersionsBandAgainstTheFlatteningAndPdcFloors() {
        // 1.13 flattening floor and 1.14 PDC floor — the two bands Phase 1 gates on.
        assertFalse(ServerEnvironment.parse("1.9.4-R0.1-SNAPSHOT").isAtLeast(1, 13, 0));
        assertFalse(ServerEnvironment.parse("1.12.2-R0.1-SNAPSHOT").isAtLeast(1, 13, 0));
        assertTrue(ServerEnvironment.parse("1.13.2-R0.1-SNAPSHOT").isAtLeast(1, 13, 0));
        assertFalse(ServerEnvironment.parse("1.13.2-R0.1-SNAPSHOT").isAtLeast(1, 14, 0));
        assertTrue(ServerEnvironment.parse("1.15.2-R0.1-SNAPSHOT").isAtLeast(1, 14, 0));
        assertTrue(ServerEnvironment.parse("1.16.5-R0.1-SNAPSHOT").isAtLeast(1, 14, 0));
    }

    private static void assertLegacy(String bukkitVersion, int major, int minor, int patch) {
        ServerEnvironment env = ServerEnvironment.parse(bukkitVersion);
        assertTrue(env.recognized(), bukkitVersion + " should be recognized");
        assertEquals(major, env.major(), bukkitVersion + " major");
        assertEquals(minor, env.minor(), bukkitVersion + " minor");
        assertEquals(patch, env.patch(), bukkitVersion + " patch");
        assertFalse(env.yearScheme(), bukkitVersion + " is legacy scheme");
    }
}
