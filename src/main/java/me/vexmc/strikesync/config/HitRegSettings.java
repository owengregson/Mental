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
 * @param preSendFeedback      when true (and {@code fastPath} is true), the async listener computes
 *                             the knockback vector from the per-tick state cache and sends both the
 *                             velocity packet and the hurt-animation packet to the relevant clients
 *                             directly from the netty thread, before main-thread damage runs. This
 *                             is the headline latency win.
 * @param feedbackMinIntervalMs minimum spacing, in milliseconds, between pre-sent feedback packets
 *                             for the same victim. This is the victim's damage-invulnerability
 *                             window: vanilla only applies a knockback-bearing hit about once per
 *                             {@code 10 ticks (500 ms)}, so without this gate every spam-click would
 *                             ship a fresh velocity packet and the victim would "fly". {@code 0}
 *                             disables the gate (not recommended).
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
        boolean preSendFeedback,
        long feedbackMinIntervalMs,
        boolean simulateCrits,
        boolean resetAttackCooldown) {

    /** Vanilla's knockback re-eligibility window: maxNoDamageTicks/2 = 10 ticks = 500 ms. */
    private static final long DEFAULT_FEEDBACK_MIN_INTERVAL_MS = 500L;

    public static HitRegSettings from(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        int maxCps = Math.max(0, section.getInt("max-cps", 20));
        boolean fastPath = section.getBoolean("fast-path.enabled", true);
        // Accept the legacy `pre-send-velocity` key for one release as a fallback,
        // so users upgrading from the dev v4.0.0 don't lose their tuning.
        boolean preSendFeedback = section.getBoolean(
                "fast-path.pre-send-feedback",
                section.getBoolean("fast-path.pre-send-velocity", true));
        long feedbackMinIntervalMs = Math.max(0L, section.getLong(
                "fast-path.feedback-min-interval-ms", DEFAULT_FEEDBACK_MIN_INTERVAL_MS));
        boolean simulateCrits = section.getBoolean("fast-path.simulate-crits", true);
        boolean resetAttackCooldown = section.getBoolean("fast-path.reset-attack-cooldown", true);
        return new HitRegSettings(enabled, maxCps, fastPath, preSendFeedback,
                feedbackMinIntervalMs, simulateCrits, resetAttackCooldown);
    }

    public boolean rateLimited() {
        return maxCps > 0;
    }
}
