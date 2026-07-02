package me.vexmc.mental.v5.feature.damage;

import java.util.UUID;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.model.HitContext;

/**
 * The single crit/tool-damage ownership verdict source, resolved from ONE
 * {@link HitContext} token so the fast path and the vanilla EDBEE path can never
 * disagree (mandate §4.6 — the forgotten-gate-on-one-path bug class is
 * structurally dead: both paths hold the same instance and read the same token).
 *
 * <p>The retired {@code HitApplier.damage} computed {@code ocmShapesDamage}
 * ad-hoc as {@code handles(TOOL_DAMAGE) || handles(CRITICAL_HITS)} for the
 * attacker, while the retired {@code CritFallbackModule} forgot the OCM gate
 * entirely — the two paths could disagree. v5 threads ONE {@code DamageOwnership}
 * to the {@link DamageShaper} (fast path) and the {@code CritFallbackUnit}
 * (vanilla path); both resolve through {@link #ocmShapesDamage} /
 * {@link #mentalOwnsCriticalHits} over the hit's {@link HitContext}.</p>
 *
 * <p>Ownership is read through the {@link Oracle} seam ({@code OcmBinding::mentalOwns}
 * in production; a stub in tests) on the resolving thread — the owning region
 * thread for both paths, matching the retired {@code OcmGate} owning-thread rule.
 * A null attacker (no damager UUID) is treated as Mental-owned, since there is no
 * decider whose OCM modeset could claim it.</p>
 */
public final class DamageOwnership {

    /** The ownership read seam — {@code OcmBinding::mentalOwns} in production. */
    @FunctionalInterface
    public interface Oracle {
        boolean mentalOwns(MechanicToken token, UUID decider);
    }

    private final Oracle oracle;

    public DamageOwnership(Oracle oracle) {
        this.oracle = oracle;
    }

    /** Whether Mental (not OCM) owns critical hits for {@code attacker} (null ⇒ Mental-owned). */
    public boolean mentalOwnsCriticalHits(UUID attacker) {
        return attacker == null || oracle.mentalOwns(MechanicToken.CRITICAL_HITS, attacker);
    }

    /** Whether Mental (not OCM) owns tool damage for {@code attacker} (null ⇒ Mental-owned). */
    public boolean mentalOwnsToolDamage(UUID attacker) {
        return attacker == null || oracle.mentalOwns(MechanicToken.TOOL_DAMAGE, attacker);
    }

    /** {@link #mentalOwnsCriticalHits(UUID)} over the hit's {@link HitContext} token (fast path). */
    public boolean mentalOwnsCriticalHits(HitContext context) {
        return mentalOwnsCriticalHits(context.attackerId());
    }

    /** {@link #mentalOwnsToolDamage(UUID)} over the hit's {@link HitContext} token (fast path). */
    public boolean mentalOwnsToolDamage(HitContext context) {
        return mentalOwnsToolDamage(context.attackerId());
    }

    /**
     * Whether OCM shapes the melee damage for {@code attacker} — true when OCM
     * owns EITHER tool damage or critical hits (the retired {@code HitApplier} OR).
     * When true the fast path hands vanilla-shaped damage and the crit fallback
     * yields, so OCM is the single source of truth and the round-trip is lossless.
     */
    public boolean ocmShapesDamage(UUID attacker) {
        return !mentalOwnsToolDamage(attacker) || !mentalOwnsCriticalHits(attacker);
    }

    /** {@link #ocmShapesDamage(UUID)} over the hit's {@link HitContext} token (fast path). */
    public boolean ocmShapesDamage(HitContext context) {
        return ocmShapesDamage(context.attackerId());
    }
}
