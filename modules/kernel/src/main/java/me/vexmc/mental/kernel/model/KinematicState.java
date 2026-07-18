package me.vexmc.mental.kernel.model;

/**
 * The victim kinematics the compensation query reads from the published view
 * (spec §5): the current y, the distance to the ground below, and the
 * client-reported on-ground flag. Refreshed by the session tick; a stateless
 * per-hit query consumes it (no probe cadence, no slot, no TTL).
 */
public record KinematicState(double y, double distanceToGround, boolean clientOnGround) {
}
