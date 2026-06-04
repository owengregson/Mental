package me.vexmc.mental.module.hitreg;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The pre-send composition rules, as a pure plan — the regression anchor for
 * the packet-ordering hazard class.
 *
 * <p>Invariants the plan encodes (and the unit tests pin):</p>
 * <ul>
 *   <li><b>Velocity before hurt</b>, always — the gameplay-critical packet
 *       leads the cosmetic one.</li>
 *   <li><b>Bundling wraps the burst on 1.19.4+</b> so the client applies
 *       velocity and hurt animation in the same frame — atomic feedback, the
 *       delivery lever none of the knockback forks ship. Below 1.19.4 the
 *       packets go out back-to-back unwrapped (the bundle delimiter does not
 *       exist on the wire).</li>
 *   <li><b>No single-packet bundles.</b> A hurt-only burst (velocity
 *       suppressed by the anticheat gate, OCM ownership, or a pending legacy
 *       resistance roll) is sent bare — wrapping one packet buys nothing and
 *       costs a delimiter pair.</li>
 *   <li>Bundle delimiters are always balanced: OPEN appears iff CLOSE does.</li>
 * </ul>
 */
enum FeedbackBurst {
    BUNDLE_OPEN,
    VELOCITY,
    HURT,
    BUNDLE_CLOSE;

    /**
     * Plans the victim-bound burst.
     *
     * @param includeVelocity whether the knockback velocity pre-send is eligible
     * @param bundleWanted    the {@code fast-path.bundle-feedback} setting
     * @param bundleCapable   whether this server's protocol has the bundle
     *                        delimiter (1.19.4+)
     */
    static @NotNull List<FeedbackBurst> plan(
            boolean includeVelocity, boolean bundleWanted, boolean bundleCapable) {
        if (!includeVelocity) {
            return List.of(HURT);
        }
        if (bundleWanted && bundleCapable) {
            return List.of(BUNDLE_OPEN, VELOCITY, HURT, BUNDLE_CLOSE);
        }
        return List.of(VELOCITY, HURT);
    }
}
