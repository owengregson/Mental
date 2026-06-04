package me.vexmc.mental.config;

/**
 * How the base vertical component treats the victim's existing motion.
 *
 * <p>{@code ADD} is the vanilla rule on every version: friction survives a
 * fraction of the victim's vertical motion and the base vertical is added on
 * top, so a rising victim launches higher and a falling one lower. {@code SET}
 * assigns the base vertical outright — the "static knockback" lever the
 * improved-knockback forks popularized: every hit launches identically no
 * matter what the victim was doing, which is what makes their combos read as
 * deterministic. With {@code SET}, vertical friction and the
 * latency-compensation vertical hint are irrelevant by definition.</p>
 */
public enum VerticalMode {
    /** Vanilla accumulation: {@code y = vy × friction.y + base.vertical}. */
    ADD,
    /** Assignment: {@code y = base.vertical}, the victim's motion ignored. */
    SET
}
