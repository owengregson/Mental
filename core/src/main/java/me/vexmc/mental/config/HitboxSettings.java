package me.vexmc.mental.config;

/**
 * The old-hitboxes module switch (config.yml, {@code modules} map — no tunables of
 * its own; the era values are the defaults).
 *
 * <p>When enabled, Mental restores the 1.7/1.8-era melee reach ({@code 3.0}
 * blocks) and hitbox margin ({@code 0.1}) as far as the modern server allows,
 * using whichever per-version lever the server exposes (chosen by capability,
 * never a version number):</p>
 *
 * <ul>
 *   <li><b>1.20.5+</b> — the {@code ENTITY_INTERACTION_RANGE} attribute base is
 *       pinned to era {@code 3.0} (and restored on quit/disable). Since the
 *       vanilla base is already {@code 3.0}, the meaningful effect is resetting any
 *       third-party inflation; the server's leniency added on top cannot be removed
 *       from this surface.</li>
 *   <li><b>1.21.5+</b> — additionally, the held weapon gets the era
 *       {@code ATTACK_RANGE} item data component ({@code max_reach=3.0},
 *       {@code hitbox_margin=0.1}, creative {@code 4.0}), stripped again on every
 *       way it can leave the hand and on disable.</li>
 *   <li><b>1.17.1–1.20.4</b> — neither lever exists (the gate is a hardcoded 6
 *       blocks); the module is a complete NO-OP.</li>
 * </ul>
 *
 * <p><b>Hard limit:</b> the CLIENT picks the melee target and sends a fixed entity
 * id that the server resolves verbatim (no server-side raytrace on the attack
 * path), so true 1.7 target-selection geometry is unreachable server-side. This
 * tunes the reach gate and the targeting margin only; target selection stays
 * whatever the modern client does (already close to 1.7 for melee).</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant: no
 * attribute write, no item component, nothing.</p>
 */
public record HitboxSettings(boolean enabled) {

    /** Default: module OFF — vanilla reach/hitbox behaviour. */
    public static final HitboxSettings DEFAULTS = new HitboxSettings(false);
}
