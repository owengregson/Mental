package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.attribute.Attribute;
import org.junit.jupiter.api.Test;

/**
 * Runs against the 1.17 paper-api on the test classpath, where Attribute is
 * still the GENERIC_-prefixed enum — exercising the legacy resolution branch.
 * The modern branch is exercised by the integration matrix on 1.21.4+.
 */
class AttributesTest {

    @Test
    void resolvesLegacyEnumConstants() {
        assertEquals(Attribute.GENERIC_ATTACK_DAMAGE, Attributes.attackDamage());
        assertEquals(Attribute.GENERIC_ATTACK_SPEED, Attributes.attackSpeed());
        assertEquals(Attribute.GENERIC_KNOCKBACK_RESISTANCE, Attributes.knockbackResistance());
        assertNotNull(Attributes.attackDamage());
    }

    @Test
    void unknownNamesResolveEmpty() {
        assertTrue(Attributes.lookup("NOT_A_THING", "ALSO_NOT_A_THING").isEmpty());
    }

    @Test
    void lookupPrefersModernThenLegacy() {
        assertEquals(
                Attribute.GENERIC_ARMOR,
                Attributes.lookup("ARMOR_DOES_NOT_EXIST_YET", "GENERIC_ARMOR").orElseThrow());
    }
}
