package me.vexmc.mental.v5.feature.feedback;

import org.bukkit.entity.LivingEntity;

/**
 * Detects vanilla's mid-invulnerability UPGRADE branch — the "delta hit". A
 * stronger hit landing while the victim's half-open invulnerability window is
 * still more than half full deals only the DIFFERENCE (amount − lastHurt) and
 * zeroes the fresh-hit flag that gates every client effect: no hurt sound, no
 * flinch, no knockback (era-exact per the compendium). The server's own branch
 * selector is exactly this predicate, and it is version-blind: the fresh branch
 * re-arms {@code noDamageTicks} only AFTER its event returns on every band
 * 1.9.4→26.x (bytecode-verified 2026-07-11), so {@code noDamageTicks > max/2}
 * DURING an EDBEE identifies the delta branch with no version gate.
 *
 * <p>The strict {@code >} is load-bearing. A boundary-adopted fast-path hit sets
 * {@code noDamageTicks} to EXACTLY {@code max/2}
 * ({@code HitRegistrationUnit#adoptBoundary}), which {@code >} excludes — an
 * adopted hit is FRESH, never a phantom delta. The integer division
 * ({@code max/2}) mirrors the server's own arithmetic verbatim.
 *
 * <p>The one shared source of the predicate so {@link HitFeedbackListener}'s
 * era-silence and {@link DamageIndicatorsListener}'s window folding can never
 * drift apart.
 */
final class UpgradeWindow {

    private UpgradeWindow() {}

    /** The raw predicate, pinned on ints so it needs no live server. */
    static boolean isDelta(int noDamageTicks, int maxNoDamageTicks) {
        return noDamageTicks > maxNoDamageTicks / 2;
    }

    /** Reads the victim's live counters and applies {@link #isDelta(int, int)}. */
    static boolean isDelta(LivingEntity victim) {
        return isDelta(victim.getNoDamageTicks(), victim.getMaximumNoDamageTicks());
    }
}
