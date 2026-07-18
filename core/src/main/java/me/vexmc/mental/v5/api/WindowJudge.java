package me.vexmc.mental.v5.api;

/**
 * The gen-3 §6 pinned hurt-window admit test. ONE expression, frozen as the
 * public contract — deliberately NOT PlayerView.damageImmune()'s "+1
 * staleness" read or any other internal fast-path variant. Only a Capability
 * bump may change it.
 */
public final class WindowJudge {

    private WindowJudge() {
    }

    public static boolean clear(int noDamageTicks, int maximumNoDamageTicks) {
        return noDamageTicks <= maximumNoDamageTicks / 2;
    }
}
