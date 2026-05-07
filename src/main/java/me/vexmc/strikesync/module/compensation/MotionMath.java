package me.vexmc.strikesync.module.compensation;

/**
 * Vertical-motion simulation primitives borrowed from KnockbackSync
 * (caseload/knockbacksync — GPL-3.0; see README for attribution).
 *
 * <p>
 * Vanilla MC ticks gravity as:
 * 
 * <pre>
 *   v ← (v − g) × 0.98
 *   v ← min(v, terminal)
 * </pre>
 * 
 * Every method here simulates that loop forward to estimate either the time
 * required to reach a state, or the position/velocity at a future tick. The
 * functions return {@code -1} when the simulation exceeds {@link #MAX_TICKS},
 * which is the caller's signal to refuse a prediction (we don't want to
 * confidently rewrite knockback for a player who's been hit by an Elytra-rocket
 * launch and is going to be in the air for half a second).
 */
final class MotionMath {

	static final double TERMINAL_VELOCITY = 3.92D;
	static final double DRAG_MULTIPLIER = 0.98D;
	static final int MAX_TICKS = 30;

	private MotionMath() {
	}

	/**
	 * Simulate vanilla vertical decay forward by {@code ticks} ticks and return
	 * the final velocity. Positive {@code velocity} means upward; gravity is
	 * subtracted each tick.
	 */
	static double simulateVerticalVelocity(double velocity, double gravity, int ticks) {
		for (int i = 0; i < ticks; i++) {
			velocity = (velocity - gravity) * DRAG_MULTIPLIER;
			if (velocity < -TERMINAL_VELOCITY)
				velocity = -TERMINAL_VELOCITY;
		}
		return velocity;
	}

	/**
	 * Total upward distance travelled over {@code ticks} ticks starting at
	 * {@code velocity} (positive = upward). Used as part of the on-ground
	 * prediction.
	 */
	static double distanceTraveled(double velocity, int ticks, double gravity) {
		double total = 0.0D;
		for (int i = 0; i < ticks; i++) {
			total += velocity;
			velocity = (velocity - gravity) * DRAG_MULTIPLIER;
			if (velocity > TERMINAL_VELOCITY)
				velocity = TERMINAL_VELOCITY;
		}
		return total;
	}

	/** Ticks required for an upward {@code velocity} to decay to ≤ 0. */
	static int ticksToApex(double velocity, double gravity) {
		int ticks = 0;
		while (velocity > 0) {
			if (ticks > MAX_TICKS)
				return -1;
			velocity = (velocity - gravity) * DRAG_MULTIPLIER;
			if (velocity > TERMINAL_VELOCITY)
				velocity = TERMINAL_VELOCITY;
			ticks++;
		}
		return ticks;
	}

	/**
	 * Ticks required to fall {@code distance} blocks starting from a (possibly
	 * negative) {@code initialVelocity}. Gravity here is signed positive
	 * downward, since this models the falling phase only.
	 */
	static int ticksToFall(double initialVelocity, double distance, double gravity) {
		double v = Math.abs(initialVelocity);
		int ticks = 0;
		while (distance > 0) {
			if (ticks > MAX_TICKS)
				return -1;
			v += gravity;
			if (v > TERMINAL_VELOCITY)
				v = TERMINAL_VELOCITY;
			v *= DRAG_MULTIPLIER;
			distance -= v;
			ticks++;
		}
		return ticks;
	}
}
