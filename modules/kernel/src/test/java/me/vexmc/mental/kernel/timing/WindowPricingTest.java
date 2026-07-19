package me.vexmc.mental.kernel.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The pure window re-pricing arithmetic (the {@code HitTimingOverrides} API).
 * Hand-computed pins across the canonical windows: the vanilla 20, the ct8c
 * sword 7 and axe 10, and the projectile 0.
 */
class WindowPricingTest {

    @Test
    void oneIsTheEraExactNoOpForEveryWindow() {
        // round(w * 1.0) == w — the un-priced path is byte-identical.
        assertEquals(20, WindowPricing.price(20, 1.0), "vanilla window unchanged at 1.0");
        assertEquals(7, WindowPricing.price(7, 1.0), "ct8c sword window unchanged at 1.0");
        assertEquals(0, WindowPricing.price(0, 1.0), "a projectile 0-window stays 0");
    }

    @Test
    void halfIsTwiceAsFast() {
        // round(20 * 0.5) = 10; round(7 * 0.5) = round(3.5) = 4 (HALF_UP);
        // round(10 * 0.5) = 5; round(0 * 0.5) = 0.
        assertEquals(10, WindowPricing.price(20, 0.5), "vanilla 20 → 10 at half");
        assertEquals(4, WindowPricing.price(7, 0.5), "ct8c sword 7 → round(3.5) = 4 at half");
        assertEquals(5, WindowPricing.price(10, 0.5), "ct8c axe 10 → 5 at half");
        assertEquals(0, WindowPricing.price(0, 0.5), "a projectile 0-window stays 0 at half");
    }

    @Test
    void theFloorIsTheMachineGunCeiling() {
        // 0.25 is four-times-as-fast, the hard floor: round(20 * 0.25) = 5,
        // round(7 * 0.25) = round(1.75) = 2. Any request below 0.25 clamps up.
        assertEquals(5, WindowPricing.price(20, 0.25), "vanilla 20 → 5 at the 0.25 floor");
        assertEquals(2, WindowPricing.price(7, 0.25), "ct8c sword 7 → round(1.75) = 2 at the floor");
        assertEquals(5, WindowPricing.price(20, 0.05),
                "a below-floor 0.05 clamps to 0.25 → 5, never a machine-gun window");
        assertEquals(WindowPricing.MIN_FACTOR, WindowPricing.clampFactor(-3.0),
                "a negative factor clamps to the floor");
    }

    @Test
    void aboveOneClampsToTheNoOp() {
        // A consumer may never price a SLOWER window: 1.5 clamps to 1.0.
        assertEquals(20, WindowPricing.price(20, 1.5), "1.5 clamps to 1.0 — window unchanged");
        assertEquals(WindowPricing.MAX_FACTOR, WindowPricing.clampFactor(9.0), "above-ceiling clamps to 1.0");
    }

    @Test
    void nanDegradesToTheNoOp() {
        // The one value Math.max/min cannot bound must not accelerate.
        assertEquals(WindowPricing.MAX_FACTOR, WindowPricing.clampFactor(Double.NaN), "NaN → the 1.0 no-op");
        assertEquals(7, WindowPricing.price(7, Double.NaN), "a NaN factor leaves the window unchanged");
    }
}
