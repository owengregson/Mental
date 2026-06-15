package me.vexmc.mental.config;

/**
 * The old-tool-durability module switch (config.yml, {@code modules} map —
 * no tunables of its own).
 *
 * <p>When enabled, Mental restores the weapon-durability loss on its fast-path
 * melee hits. The fast path applies damage by calling {@code damage(amount)}
 * directly, which does NOT run vanilla's attack-time durability — so a weapon
 * never wears on a Mental-registered hit unless this is on. With it on, the
 * attacker's main-hand weapon loses <em>1</em> durability per accepted hit
 * (era 1.7→modern attack case), modified by the standard tool/weapon Unbreaking
 * skip ({@code 1/(level+1)} damage chance — see {@code ToolDurabilityMath}), and
 * breaks like vanilla when its durability is exhausted. Lives in the fast-path
 * {@code me.vexmc.mental.module.hitreg.HitApplier}, gated on this flag.</p>
 *
 * <p>This is durability only — the damage VALUES are already era-correct in
 * {@code DamageCalculator} and are not touched here.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant. When
 * off, HitApplier behaviour is byte-identical to the durability-omitting fast
 * path it has always shipped.</p>
 */
public record ToolDurabilitySettings(boolean enabled) {

    /** Default: module OFF — the fast path omits weapon durability as before. */
    public static final ToolDurabilitySettings DEFAULTS = new ToolDurabilitySettings(false);
}
