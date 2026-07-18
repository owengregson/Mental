package me.vexmc.mental.kernel.fx;

/**
 * The indicator's pop-off flight: an item-drop-like arc — up for a few ticks,
 * then a dragged gravity fall — integrated as position += velocity, THEN
 * vy' = (vy − gravity) × drag with horizontal velocity × drag. The driver owns
 * ground truth: {@code groundY} is frozen once at spawn (the only place block
 * reads are region-legal), so the per-tick step performs zero world reads.
 */
public final class IndicatorBallistics {

    /** How far above the ground plane the text may hover before counting as landed. */
    public static final double LANDING_EPSILON = 0.05;

    public record Params(double launchVertical, double launchOutward, double gravity, double drag) {}

    public record State(double x, double y, double z, double vx, double vy, double vz) {}

    private IndicatorBallistics() {}

    /** The launch state: outward along the ring bearing, upward at launchVertical. */
    public static State launch(IndicatorPlacement.Spawn spawn, Params params) {
        return new State(
                spawn.x(), spawn.y(), spawn.z(),
                Math.cos(spawn.bearing()) * params.launchOutward(),
                params.launchVertical(),
                Math.sin(spawn.bearing()) * params.launchOutward());
    }

    public static State step(State s, Params params) {
        double x = s.x() + s.vx();
        double y = s.y() + s.vy();
        double z = s.z() + s.vz();
        return new State(
                x, y, z,
                s.vx() * params.drag(),
                (s.vy() - params.gravity()) * params.drag(),
                s.vz() * params.drag());
    }

    public static boolean landed(State s, double groundY) {
        return s.y() <= groundY + LANDING_EPSILON;
    }
}
