package me.vexmc.mental.config;

/**
 * The sword-blocking module switch (config.yml, {@code modules} map — no
 * tunables of its own).
 *
 * <p>When enabled, Mental restores 1.7-style right-click sword blocking via
 * modern item data components, on the servers/clients that support them:</p>
 *
 * <ul>
 *   <li><b>Tier A</b> ({@code BLOCKS_ATTACKS} present, ~1.21.5+): the held sword
 *       gets a {@code BLOCKS_ATTACKS} component whose single {@code DamageReduction}
 *       ({@code base=-0.5, factor=0.5, horizontalBlockingAngle=180}) reproduces
 *       the 1.8 {@code (damage-1)*0.5} reduction NATIVELY, and the server natively
 *       reports {@code isBlocking()} and runs the blocked-damage pipeline. No
 *       software reduction is applied on this tier.</li>
 *   <li><b>Tier B</b> ({@code CONSUMABLE} present, {@code BLOCKS_ATTACKS} absent,
 *       1.21.0–1.21.4): the held sword gets a {@code CONSUMABLE} component with a
 *       {@code BLOCK} use animation so the server enters the active-use /
 *       {@code isBlocking()} state. The component does NOT reduce damage on this
 *       tier, so the {@code (damage-1)*0.5} reduction is applied in software (the
 *       damage event) — see {@link me.vexmc.mental.module.block.SwordBlockReduction}.</li>
 *   <li><b>Tier C</b> (neither component, 1.17.1–1.20.6, and any client below the
 *       component version): OUT OF SCOPE for this module — it is a complete NO-OP.
 *       The legacy offhand-shield fallback is a separate module.</li>
 * </ul>
 *
 * <p>Era truth: a blocked hit still knocks the victim FULL — only DAMAGE is
 * reduced. Mental owns knockback; the block never cancels or zeroes velocity.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant. When
 * off, OR on a tier-C server, the module registers no listeners, writes no
 * component, and changes nothing.</p>
 */
public record SwordBlockingSettings(boolean enabled) {

    /** Default: module OFF — vanilla right-click behaviour for swords. */
    public static final SwordBlockingSettings DEFAULTS = new SwordBlockingSettings(false);
}
