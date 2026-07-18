package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.ToIntFunction;
import me.vexmc.mental.platform.CleavingRegistrar;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Task INT wire 1: the Cleaving handle feeds {@code Ct8cShieldUnit}'s axe-disable
 * scaling through the {@code ToIntFunction<ItemStack>} constructor. This pins the
 * degrade contract the wiring depends on — where Cleaving is unresolved (below its
 * ~1.21.3 registry floor, or, as here, a serverless context) the lookup must
 * resolve to level {@code 0} and NEVER throw, so the shield-disable stays at the
 * flat 32-tick base. The exact lambda {@code MentalPluginV5.registerUnits} passes
 * is reproduced here so a regression to a non-degrading expression is caught.
 */
class Ct8cShieldCleavingWiringTest {

    /** The exact live lookup the registration wires into the shield unit. */
    private static final ToIntFunction<ItemStack> LOOKUP =
            stack -> CleavingRegistrar.handle().map(handle -> handle.levelOf(stack)).orElse(0);

    @Test
    void theCleavingLookupDegradesToZeroWhenNoHandleIsResolved() {
        // No live server in a unit context ⇒ CleavingRegistrar resolves no handle,
        // so the lookup is the level-0 degrade for any stack (even a null one).
        assertDoesNotThrow(() -> LOOKUP.applyAsInt(null));
        assertEquals(0, LOOKUP.applyAsInt(null),
                "an unresolved Cleaving handle must read level 0, never throw");
    }

    @Test
    void aLevelZeroCleavingKeepsTheShieldDisableAtTheFlatBase() {
        // The 32 + 10·level disable (spec §2.6/§2.9): the degraded level-0 lookup
        // keeps a blocked axe hit disabling the shield the flat 1.6s base, exactly the
        // documented spec §5 gap where Cleaving is absent.
        assertEquals(32, Ct8cShieldUnit.shieldDisableTicks(LOOKUP.applyAsInt(null)));
    }
}
