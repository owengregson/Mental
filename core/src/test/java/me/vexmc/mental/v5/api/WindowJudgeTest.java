package me.vexmc.mental.v5.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the gen-3 §6 pinned hurt-window admit expression
 * ({@code noDamageTicks <= maximumNoDamageTicks / 2}, integer division). The
 * cell {@code (11, 20)} is the divergence point: the pinned contract says NOT
 * clear, while the internal "+1 staleness" variant ({@code PlayerView.damageImmune})
 * would still read admissible there — the exact mirror-drift H4 exists to kill.
 */
class WindowJudgeTest {

    @Test
    void pinsTheContractCells() {
        assertEquals(true, WindowJudge.clear(0, 20));
        assertEquals(true, WindowJudge.clear(10, 20));
        assertEquals(false, WindowJudge.clear(11, 20)); // the +1-staleness divergence cell
        assertEquals(false, WindowJudge.clear(12, 20));
        assertEquals(false, WindowJudge.clear(20, 20));
        assertEquals(true, WindowJudge.clear(5, 10));
        assertEquals(false, WindowJudge.clear(6, 10));
        assertEquals(true, WindowJudge.clear(0, 0));
    }
}
