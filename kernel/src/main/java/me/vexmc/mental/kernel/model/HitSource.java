package me.vexmc.mental.kernel.model;

/**
 * Typed provenance minted at a hit's origin — B6's answer to "what caused
 * this hit?". The delivery core dispatches on the concrete type rather than
 * re-deriving cause from damage flags, so a synthetic {@link RodPull} can
 * never be mistaken for {@link Melee}.
 */
public sealed interface HitSource {

    /** A direct melee attack. */
    record Melee() implements HitSource {}

    /** The synthetic {@code victim.damage(rodder)} a fishing pull provokes. */
    record RodPull() implements HitSource {}

    /** An arrow hit; {@code punchLevel} is the bow's Punch enchant level. */
    record Arrow(int punchLevel) implements HitSource {}

    /** A thrown projectile (snowball, egg, ender pearl, …) keyed by its type name. */
    record Thrown(String projectileType) implements HitSource {}

    /** A fishing bobber's own impact. */
    record Bobber() implements HitSource {}

    /** A hit Mental did not originate (fast path off, another plugin, environment). */
    record Vanilla(String damageCause) implements HitSource {}
}
