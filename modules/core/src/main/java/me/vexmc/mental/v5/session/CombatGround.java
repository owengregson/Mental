package me.vexmc.mental.v5.session;

/**
 * The single grounded-truth authority the combat/combo path consumes (spec §2,
 * D2 "one source of truth"). Pure and Bukkit-free so the packetless-fallback
 * decision is unit-pinned without a live server.
 *
 * <p><b>Why this exists.</b> A connected player's ground state is the
 * client-reported {@code isOnGround()} flag — era-correct and packet-FSM-fed, so
 * that flag is trusted verbatim. A <em>packetless</em> player (a synthetic test
 * player or an in-process bot) sends no movement packets; on the 1.9/1.10 NMS its
 * {@code isOnGround()} reads {@code false} forever after a send-then-restore knock
 * even while it rests on the floor. The combo detector's grounded-run end and the
 * pocket-servo precision inputs (launch state, the grounded-tick tail) would then
 * never see the truth, so a held combo never releases and a stationary victim is
 * read as perpetually airborne. This routes those combat reads through the same
 * physical fallback the live suite pins against ({@code
 * KnockbackSuite.physicallyGrounded}): a solid surface directly under the feet
 * with the vertical settled. On every version where the flag is already correct
 * the fallback agrees with it, so no real-client behaviour changes (zero-touch).</p>
 */
final class CombatGround {

    /**
     * Feet-to-ground distance (blocks) at or below which a packetless player is
     * standing — mirrors the live suite's 0.05 support-probe depth.
     */
    static final double GROUNDED_DISTANCE_EPSILON = 0.05;

    /**
     * Vertical speed below which motion has settled — mirrors the live suite's
     * settle epsilon so production and the suite judge the same physical truth.
     */
    static final double SETTLE_VELOCITY_EPSILON = 0.1;

    private CombatGround() {}

    /**
     * The combat grounded truth for a player this tick.
     *
     * @param connected        whether a connection domain is present (a real client
     *                         fed by the packet FSM); packetless players are not
     * @param onGroundFlag     the live {@code Player#isOnGround()} client flag
     * @param distanceToGround the physical feet-to-ground distance (used only for a
     *                         packetless player whose flag lies airborne)
     * @param velocityY        the live vertical velocity (same gate as above)
     * @return {@code true} when the player is grounded for combat purposes
     */
    static boolean grounded(
            boolean connected, boolean onGroundFlag, double distanceToGround, double velocityY) {
        // A connected client — and any player the flag already reports grounded —
        // is trusted verbatim: that flag is the era-correct, packet-fed source.
        if (connected || onGroundFlag) {
            return onGroundFlag;
        }
        // A packetless player whose flag lies airborne: fall back to the physical
        // read — a solid surface under the feet with the vertical settled.
        return distanceToGround <= GROUNDED_DISTANCE_EPSILON
                && Math.abs(velocityY) < SETTLE_VELOCITY_EPSILON;
    }
}
