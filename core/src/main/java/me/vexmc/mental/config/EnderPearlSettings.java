package me.vexmc.mental.config;

/**
 * The disable-enderpearl-cooldown module switch (config.yml, {@code modules} map).
 *
 * <p>Era truth: 1.8.9 had no ender-pearl throw cooldown. The 20-tick (1 s)
 * cooldown was added in 1.9 ({@code EnderpearlItem.use} sets it before spawning
 * the projectile). On 1.21.2+ the cooldown was migrated to a {@code use_cooldown}
 * data component. The cross-version-stable lever is
 * {@link org.bukkit.entity.HumanEntity#setCooldown(org.bukkit.Material, int)},
 * which CraftBukkit resolves to the right cooldown group internally on every
 * supported version.</p>
 *
 * <p>Era-exact no-op when disabled (default OFF) — zero-touch invariant.</p>
 */
public record EnderPearlSettings(boolean enabled) {

    /** Default: module OFF — modern 1-second pearl cooldown is unchanged. */
    public static final EnderPearlSettings DEFAULTS = new EnderPearlSettings(false);
}
