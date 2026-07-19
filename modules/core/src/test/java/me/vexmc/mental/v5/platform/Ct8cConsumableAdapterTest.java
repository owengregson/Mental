package me.vexmc.mental.v5.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure material classification and stack-size constants of the CT8c
 * consumable adapter (spec §2.7/§2.8/§2.10). The NMS component writes are exercised
 * live in the tester; here we pin the version-stable decisions that route them.
 */
class Ct8cConsumableAdapterTest {

    @Test
    void stackSizeConstantsMatchTheSpec() {
        assertEquals(16, Ct8cConsumableAdapter.POTION_STACK_SIZE);  // drinkable potions → 16
        assertEquals(64, Ct8cConsumableAdapter.SNOWBALL_STACK_SIZE); // snowballs → 64
    }

    @Test
    void onlyTheDrinkablePotionStacks() {
        assertTrue(Ct8cConsumableAdapter.isDrinkablePotion(Material.POTION));
        // Splash and lingering potions stay 1 (spec §2.8) — never the stacking path.
        assertFalse(Ct8cConsumableAdapter.isDrinkablePotion(Material.SPLASH_POTION));
        assertFalse(Ct8cConsumableAdapter.isDrinkablePotion(Material.LINGERING_POTION));
        assertFalse(Ct8cConsumableAdapter.isDrinkablePotion(null));
    }

    @Test
    void snowballIsClassified() {
        assertTrue(Ct8cConsumableAdapter.isSnowball(Material.SNOWBALL));
        assertFalse(Ct8cConsumableAdapter.isSnowball(Material.POTION));
        assertFalse(Ct8cConsumableAdapter.isSnowball(null));
    }

    @Test
    void drinksAreThePotionMilkAndHoney() {
        assertTrue(Ct8cConsumableAdapter.isDrink(Material.POTION));
        assertTrue(Ct8cConsumableAdapter.isDrink(Material.MILK_BUCKET));
        assertTrue(Ct8cConsumableAdapter.isDrink(Material.HONEY_BOTTLE));
        // A stew is 20-tick too, but on the EAT path (isBowlStew) — not the DRINK path.
        assertFalse(Ct8cConsumableAdapter.isDrink(Material.MUSHROOM_STEW));
        assertFalse(Ct8cConsumableAdapter.isDrink(Material.SNOWBALL));
    }

    @Test
    void bowlStewsAreTheEatDurationSetButNotSuspiciousStew() {
        // 8c BowlFoodItem.getUseDuration = 20 (mushroom/rabbit/beetroot), keeping EAT.
        assertTrue(Ct8cConsumableAdapter.isBowlStew(Material.MUSHROOM_STEW));
        assertTrue(Ct8cConsumableAdapter.isBowlStew(Material.RABBIT_STEW));
        assertTrue(Ct8cConsumableAdapter.isBowlStew(Material.BEETROOT_SOUP));
        // Suspicious stew extends Item, not BowlFoodItem — 8c leaves it at the 32-tick base.
        assertFalse(Ct8cConsumableAdapter.isBowlStew(Material.SUSPICIOUS_STEW));
        assertFalse(Ct8cConsumableAdapter.isBowlStew(Material.POTION));
        assertFalse(Ct8cConsumableAdapter.isBowlStew(null));
    }
}
