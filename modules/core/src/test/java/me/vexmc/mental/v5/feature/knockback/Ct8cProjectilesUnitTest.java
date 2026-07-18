package me.vexmc.mental.v5.feature.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the CT8c throw-gate cooldown-item resolution decision. The regression this
 * guards: the unit used a direct {@code Material.SNOWBALL} getstatic, which is a
 * sticky {@code NoSuchFieldError} on every pre-flattening server (1.9.4–1.12.2,
 * where the constant is {@code SNOW_BALL}) the moment the throw gate runs. The fix
 * routes the modern names through the platform inverse material seam ({@code
 * MenuMaterials.of}) once, so on this modern-API test JVM both resolve to the real
 * gameplay items — never the seam's grey-stone fallback. The pre-flattening
 * {@code SNOWBALL → SNOW_BALL} mapping itself is pinned in the platform
 * {@code MenuMaterialsTest} (the seam this decision reuses).
 */
class Ct8cProjectilesUnitTest {

    @Test
    void snowballGatesOnTheSnowballItem() {
        Material resolved = Ct8cProjectilesUnit.throwGateItem(true);
        assertNotNull(resolved, "the snowball throw-gate item must resolve");
        assertEquals(Material.SNOWBALL, resolved, "a snowball gates on the SNOWBALL item on a flattened JVM");
    }

    @Test
    void nonSnowballGatesOnTheEggItem() {
        Material resolved = Ct8cProjectilesUnit.throwGateItem(false);
        assertNotNull(resolved, "the egg throw-gate item must resolve");
        assertEquals(Material.EGG, resolved, "a non-snowball projectile (egg) gates on the EGG item");
    }

    @Test
    void neitherItemFallsBackToTheStoneFallbackOrTheOtherItem() {
        // A miss through the inverse seam would return the STONE glyph fallback (icon semantics) — never
        // acceptable for a gameplay cooldown. SNOWBALL and EGG are both known names, so both resolve.
        assertNotEquals(Material.STONE, Ct8cProjectilesUnit.throwGateItem(true));
        assertNotEquals(Material.STONE, Ct8cProjectilesUnit.throwGateItem(false));
        assertNotEquals(Ct8cProjectilesUnit.throwGateItem(true), Ct8cProjectilesUnit.throwGateItem(false),
                "the snowball and egg gate items are distinct");
    }
}
