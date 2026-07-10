package me.vexmc.mental.kernel.model;

/**
 * The horizontal geometry actually fed to the knockback engine's base() for one
 * hit — the ring/live positions and the attacker yaw the direction was computed
 * from. Journal attribution only (F9): lets a live capture separate a wrong-
 * geometry knock from a verdict flip or an eaten delivery. All components are
 * Java-8 primitives, so the record crosses the tester boundary without a
 * downgraded stub type (D-8).
 */
public record HitGeometry(double attackerX, double attackerZ, float attackerYaw,
                          double victimX, double victimZ) {
}
