package me.vexmc.mental.config;

/**
 * When the victim's client receives the knockback relative to the victim's
 * physics tick — the wire-level half of era accuracy.
 *
 * <p>{@link #TRACKER} is how 1.7.10 shipped every knock (and how both eras
 * shipped rod and projectile knocks): the velocity packet left with the
 * end-of-tick entity tracker, <em>after</em> the victim's connection tick had
 * already decayed the just-written fields once — ground hits lost a full
 * ground-friction step ({@code × 0.546} horizontal!) before the client ever
 * saw them. Measured on vanilla 1.7.10: a standing sprint hit ships
 * {@code (0.491, 0.373)}, not the formula's {@code (0.9, 0.461)}.</p>
 *
 * <p>{@link #IMMEDIATE} is the 1.8.9 melee path: {@code attack()} sent the
 * packet directly, before any decay, then restored the pre-hit fields.</p>
 */
public enum KnockbackDelivery {
    TRACKER,
    IMMEDIATE
}
