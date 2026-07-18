package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins for the fast-pots item classification — which material name read off a
 * {@link org.bukkit.entity.ThrownPotion} counts as a splash potion.
 *
 * <p>The load-bearing pin is AIR: on 1.16.x–1.20.4 the server stores the
 * projectile's item only when it differs from the entity's default item or
 * carries an NBT tag ({@code EntityProjectileThrowable#setItem}), while
 * {@code CraftThrownPotion#getItem} reads the raw datawatcher without the
 * empty→default fallback — so a tagless splash potion reads back as AIR on
 * exactly that band, and an empty item on a ThrownPotion can only mean the
 * unstored splash default. Everywhere else the item round-trips honestly and
 * the AIR acceptance is a dead branch (gate evidence: the 1.16.5 PotsSuite
 * redirect failure of 2026-07-04, bytecode-confirmed against the 1.15.2,
 * 1.16.5, 1.17.1 and 1.20.6 servers).</p>
 */
class FastPotsUnitTest {

    @Test
    void splashPotionIsSplash() {
        assertTrue(FastPotsUnit.splashItem("SPLASH_POTION"), "the real item name is splash");
    }

    @Test
    void airIsTheUnstoredSplashDefaultOnTheDatawatcherSkipBand() {
        assertTrue(FastPotsUnit.splashItem("AIR"),
                "an empty ThrownPotion item can only be the unstored splash default (1.16.x–1.20.4)");
    }

    @Test
    void lingeringAndDrinkablePotionsAreNotSplash() {
        assertFalse(FastPotsUnit.splashItem("LINGERING_POTION"),
                "lingering potions are excluded — the redirect aids the instant self-heal");
        assertFalse(FastPotsUnit.splashItem("POTION"), "a drinkable potion is not a splash potion");
    }

    @Test
    void nearMissNamesAreNotSplash() {
        assertFalse(FastPotsUnit.splashItem("CAVE_AIR"), "only exactly AIR marks the unstored default");
        assertFalse(FastPotsUnit.splashItem(""), "an empty name is not a splash potion");
    }
}
