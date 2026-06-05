package me.vexmc.mental.config;

/**
 * When the victim's client receives the knockback relative to the victim's
 * physics tick — the wire-level half of era accuracy.
 *
 * <p>1.7.10 shipped every knock (and both eras shipped rod and projectile
 * knocks) with the next tick's entity tracker — and the tracker ran
 * <em>before</em> the per-connection network phase, whose slots execute in
 * JOIN ORDER with each player's physics fused to their own slot. Whether the
 * just-written knock decayed once before the packet left therefore depended
 * on connection order, measured on real vanilla 1.7.10 (re-measured
 * 2026-06-05, both orders, deterministic): a victim who joined BEFORE their
 * attacker received the full stamp — {@code (0.9, 0.4607)} for a standing
 * sprint hit, byte-identical to 1.8.9 — while a victim who joined AFTER
 * received it one ground-friction step late, {@code (0.4914, 0.3731)}.
 * (This is the era's "relog for less knockback" folklore: relogging appends
 * you to the connection list.)</p>
 *
 * <p>{@link #TRACKER} ships the full stamp — the dominant era experience and
 * the half of the bimodal wire players remember as "how knockback works";
 * for single hits it is identical to 1.8.9. {@link #TRACKER_DECAYED} opts
 * into the later-joiner wire: one victim physics tick of decay, ground hits
 * losing {@code × 0.546} horizontal before the client ever sees them.
 * Determinism is the product, so Mental never replicates the coin flip —
 * a profile picks one mode and every hit obeys it.</p>
 *
 * <p>{@link #IMMEDIATE} is the 1.8.9 melee path: {@code attack()} sent the
 * packet directly, before any decay, then restored the pre-hit fields —
 * order-independent, which is why 1.8.9 knockback was remembered as
 * consistent.</p>
 */
public enum KnockbackDelivery {
    TRACKER,
    TRACKER_DECAYED,
    IMMEDIATE
}
