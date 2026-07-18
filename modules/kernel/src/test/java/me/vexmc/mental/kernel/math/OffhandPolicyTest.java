package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OffhandPolicy#isAllowedInOffhand}.
 *
 * <p>The method is pure — no Bukkit server is needed — so these run in the
 * plain JUnit environment without MockBukkit. Materials arrive as enum-name
 * strings (the kernel's Material seam).</p>
 */
class OffhandPolicyTest {

    /* ------------------------------------------------------------------ */
    /*  AIR / null — always allowed regardless of mode                     */
    /* ------------------------------------------------------------------ */

    @Test
    void air_alwaysAllowed_whitelist() {
        // Whitelist with nothing in it would normally block everything, but
        // AIR (clearing the slot) must always be permitted.
        assertTrue(OffhandPolicy.isAllowedInOffhand("AIR", true, Set.of()));
    }

    @Test
    void air_alwaysAllowed_blacklist() {
        // AIR stays allowed even when the whole Material universe is blacklisted.
        assertTrue(OffhandPolicy.isAllowedInOffhand("AIR", false, Set.of("AIR")));
    }

    @Test
    void null_alwaysAllowed() {
        // null slot contents (same as empty/unset) must always be allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand(null, true, Set.of()));
        assertTrue(OffhandPolicy.isAllowedInOffhand(null, false, Set.of("SHIELD")));
    }

    /* ------------------------------------------------------------------ */
    /*  Whitelist mode                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void whitelist_listedItemIsAllowed() {
        // SHIELD appears in the allow-list → allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand("SHIELD", true, Set.of("SHIELD")));
    }

    @Test
    void whitelist_unlistedItemIsBlocked() {
        // CROSSBOW does NOT appear in a SHIELD-only allow-list → blocked.
        assertFalse(OffhandPolicy.isAllowedInOffhand("CROSSBOW", true, Set.of("SHIELD")));
    }

    @Test
    void whitelist_emptyList_blocksEverything() {
        // An empty allow-list allows nothing (except AIR/null, tested above).
        assertFalse(OffhandPolicy.isAllowedInOffhand("SHIELD", true, Set.of()));
        assertFalse(OffhandPolicy.isAllowedInOffhand("TOTEM_OF_UNDYING", true, Set.of()));
    }

    /* ------------------------------------------------------------------ */
    /*  Blacklist mode                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void blacklist_listedItemIsBlocked() {
        // SHIELD appears in the deny-list → blocked.
        assertFalse(OffhandPolicy.isAllowedInOffhand("SHIELD", false, Set.of("SHIELD")));
    }

    @Test
    void blacklist_unlistedItemIsAllowed() {
        // TOTEM is NOT in a SHIELD-only deny-list → allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand("TOTEM_OF_UNDYING", false, Set.of("SHIELD")));
    }

    @Test
    void blacklist_emptyList_allowsEverything() {
        // An empty deny-list blocks nothing.
        assertTrue(OffhandPolicy.isAllowedInOffhand("SHIELD", false, Set.of()));
        assertTrue(OffhandPolicy.isAllowedInOffhand("CROSSBOW", false, Set.of()));
    }
}
