package me.vexmc.mental.kernel.coexist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure startup-warning derivation (mandate §4.11). Two classes of warning:
 * the feel-burying OCM defaults (its {@code old-player-knockback} in the
 * default modeset, and an {@code attack-frequency} playerDelay ≠ the era 20),
 * and one line per Mental-owned rule enabled in both plugins (which would
 * double-apply, since the ported rules are OCM-agnostic).
 *
 * <p>Arbitrated tokens never produce a double-enable line: their ownership is
 * settled by {@link ArbiterCore}, so enabling both is coordination, not
 * conflict. When OCM is absent there is nothing to warn about.</p>
 */
public final class CoexistWarnings {

    /** The era-truth hurt-immunity window OCM's {@code attack-frequency} playerDelay should match. */
    private static final int ERA_PLAYER_DELAY = 20;

    /** The facts an OCM config scan surfaces about the running OCM install. */
    public record OcmFacts(
            boolean present,
            boolean oldPlayerKnockbackInDefaultModeset,
            Integer playerDelay,
            Set<String> enabledModuleKeys) {}

    private CoexistWarnings() {}

    /**
     * @return human-readable warnings: the modeset default (when set), the
     *     playerDelay default (when non-null and ≠ 20), then one line per
     *     Mental-owned rule whose OCM module is also enabled — empty when OCM
     *     is absent.
     */
    public static List<String> derive(OcmFacts facts, Set<MechanicToken> mentalEnabled) {
        List<String> warnings = new ArrayList<>();
        if (!facts.present()) {
            return warnings;
        }
        if (facts.oldPlayerKnockbackInDefaultModeset()) {
            warnings.add("OldCombatMechanics has 'old-player-knockback' in its default modeset — it will"
                    + " own melee knockback for players on that modeset, burying Mental's era feel."
                    + " Move it out of the default modeset, or set compatibility.old-combat-mechanics: ignore.");
        }
        Integer playerDelay = facts.playerDelay();
        if (playerDelay != null && playerDelay != ERA_PLAYER_DELAY) {
            warnings.add("OldCombatMechanics attack-frequency playerDelay is " + playerDelay
                    + " (era truth is " + ERA_PLAYER_DELAY + ") — this reshapes the hurt-immunity window"
                    + " and shifts combo timing away from 1.7/1.8.");
        }
        // One line per Mental-owned rule that OCM also enables — a silent double-apply.
        Set<String> ocmKeys = facts.enabledModuleKeys();
        for (MechanicToken token : MechanicToken.values()) {
            if (token.arbitrated() || token.ocmKey() == null) {
                continue; // arbitrated tokens coordinate; keyless tokens have no OCM peer
            }
            if (mentalEnabled.contains(token) && ocmKeys.contains(token.ocmKey())) {
                warnings.add("Both Mental and OldCombatMechanics enable " + token
                        + " (OCM module '" + token.ocmKey() + "') — the ported rules are OCM-agnostic,"
                        + " so enabling the same rule in both double-applies it. Disable one.");
            }
        }
        return warnings;
    }
}
