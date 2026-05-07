package me.vexmc.strikesync.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view over the {@code async-hitreg} config section.
 *
 * @param enabled              master switch for the async listener.
 * @param maxCps               maximum sustained clicks-per-second per player; {@code 0} disables the cap.
 * @param fastPath             when true, the async listener cancels the vanilla packet and applies the
 *                             hit through the plugin's fast pipeline. When false, validation runs but
 *                             vanilla still owns damage application.
 * @param preSendVelocity      when true (and {@code fastPath} is true), the async listener computes
 *                             the knockback vector from the per-tick state cache and sends a velocity
 *                             packet to the victim directly from the netty thread, before main-thread
 *                             damage runs. This is the headline latency win.
 * @param simulateCrits        when true, the fast-path damage applier multiplies damage by 1.5 when
 *                             the attacker satisfies the 1.8 critical-hit conditions (falling, not
 *                             sprinting, not in water, etc.).
 * @param resetAttackCooldown  when true, the fast-path damage applier resets the attacker's attack
 *                             cooldown attribute after a successful hit, replicating what vanilla
 *                             {@code Player#attack} does at the end.
 */
public record HitRegSettings(
        boolean enabled,
        int maxCps,
        boolean fastPath,
        boolean preSendVelocity,
        boolean simulateCrits,
        boolean resetAttackCooldown) {

    public static HitRegSettings from(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        int maxCps = Math.max(0, section.getInt("max-cps", 20));
        boolean fastPath = section.getBoolean("fast-path.enabled", true);
        boolean preSendVelocity = section.getBoolean("fast-path.pre-send-velocity", false);
        boolean simulateCrits = section.getBoolean("fast-path.simulate-crits", true);
        boolean resetAttackCooldown = section.getBoolean("fast-path.reset-attack-cooldown", true);
        return new HitRegSettings(enabled, maxCps, fastPath, preSendVelocity, simulateCrits, resetAttackCooldown);
    }

    public boolean rateLimited() {
        return maxCps > 0;
    }
}
