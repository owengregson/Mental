package me.vexmc.mental.module.rules.offhand;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OffhandPolicy#isAllowedInOffhand}.
 *
 * <p>The method is pure — no Bukkit server is needed — so these run in the
 * plain JUnit environment without MockBukkit.</p>
 */
class OffhandPolicyTest {

    /* ------------------------------------------------------------------ */
    /*  AIR / null — always allowed regardless of mode                     */
    /* ------------------------------------------------------------------ */

    @Test
    void air_alwaysAllowed_whitelist() {
        // Whitelist with nothing in it would normally block everything, but
        // AIR (clearing the slot) must always be permitted.
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.AIR, true, Set.of()));
    }

    @Test
    void air_alwaysAllowed_blacklist() {
        // AIR stays allowed even when the whole Material universe is blacklisted.
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.AIR, false, Set.of(Material.AIR)));
    }

    @Test
    void null_alwaysAllowed() {
        // null slot contents (same as empty/unset) must always be allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand(null, true, Set.of()));
        assertTrue(OffhandPolicy.isAllowedInOffhand(null, false, Set.of(Material.SHIELD)));
    }

    /* ------------------------------------------------------------------ */
    /*  Whitelist mode                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void whitelist_listedItemIsAllowed() {
        // SHIELD appears in the allow-list → allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.SHIELD, true, Set.of(Material.SHIELD)));
    }

    @Test
    void whitelist_unlistedItemIsBlocked() {
        // CROSSBOW does NOT appear in a SHIELD-only allow-list → blocked.
        assertFalse(OffhandPolicy.isAllowedInOffhand(Material.CROSSBOW, true, Set.of(Material.SHIELD)));
    }

    @Test
    void whitelist_emptyList_blocksEverything() {
        // An empty allow-list allows nothing (except AIR/null, tested above).
        assertFalse(OffhandPolicy.isAllowedInOffhand(Material.SHIELD, true, Set.of()));
        assertFalse(OffhandPolicy.isAllowedInOffhand(Material.TOTEM_OF_UNDYING, true, Set.of()));
    }

    /* ------------------------------------------------------------------ */
    /*  Blacklist mode                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void blacklist_listedItemIsBlocked() {
        // SHIELD appears in the deny-list → blocked.
        assertFalse(OffhandPolicy.isAllowedInOffhand(Material.SHIELD, false, Set.of(Material.SHIELD)));
    }

    @Test
    void blacklist_unlistedItemIsAllowed() {
        // TOTEM is NOT in a SHIELD-only deny-list → allowed.
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.TOTEM_OF_UNDYING, false, Set.of(Material.SHIELD)));
    }

    @Test
    void blacklist_emptyList_allowsEverything() {
        // An empty deny-list blocks nothing.
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.SHIELD, false, Set.of()));
        assertTrue(OffhandPolicy.isAllowedInOffhand(Material.CROSSBOW, false, Set.of()));
    }
}
