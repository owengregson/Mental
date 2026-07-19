package me.vexmc.mental.api.timing;

import java.util.UUID;

/**
 * Temporary per-(victim, attacker) hit-window re-pricing, applied inside
 * Mental's own admission math (API generation 3). While an override is active,
 * every place Mental prices the victim's hit-admission window FOR THAT ATTACKER
 * reads {@code round(effectiveWindow * factor)} instead of {@code effectiveWindow}
 * — the ct8c per-hit maxima and the hit-registration fast path alike. Third-party
 * attackers, environment damage and Mental's own cosmetics are untouched: nothing
 * global is erased, so no fairness guard is needed and no knock-without-damage
 * ghost appears.
 *
 * <h2>The division of ownership</h2>
 * <p><b>Mental owns hit timing; integrators request a priced deviation and Mental
 * applies it wherever IT reads windows.</b> A consumer must never write
 * {@code noDamageTicks} around Mental's model — that fights the pipeline that owns
 * it (it degrades to a no-op steal on a window-shrinking profile, and arms the
 * 1.16.5–1.20.6 spawn-invulnerability trap). This service replaces every such
 * write: the day it registers, a consumer stops touching vanilla i-frames
 * entirely on Mental servers.
 *
 * <h2>Discovery</h2>
 * <p>The service is published on Bukkit's {@code ServicesManager} while Mental is
 * enabled — the registration IS the capability. Probe the registration
 * ({@code getServicesManager().getRegistration(HitTimingOverrides.class)}), never
 * a class-presence or version-string check. If a later generation changes
 * semantics it registers a new interface alongside this one; this interface's
 * method contracts are never repurposed.
 *
 * <h2>Threading</h2>
 * <p>Call the mutators from the VICTIM's owning region thread — the thread a
 * damage event for the victim fires on, whose natural call site is a MONITOR
 * {@code EntityDamageByEntityEvent} listener. {@link #isActive} is safe from any
 * thread.
 */
public interface HitTimingOverrides {

    /**
     * Registers (or refreshes — never stacks) an override: hits from
     * {@code attacker} on {@code victim} admit at {@code factor} times the
     * profile's effective window for {@code durationTicks} server ticks.
     * {@code factor} is clamped to {@code [0.25, 1.0]} — a floor Mental owns so
     * no consumer can price a machine-gun window, and a ceiling so none can price
     * a SLOWER window; {@code 0.5} is the canonical "twice as fast". A
     * non-positive {@code durationTicks} registers an already-lapsed (no-op)
     * window. Re-registering the same pair replaces the previous factor AND its
     * clock (refresh-not-stack); two DIFFERENT attackers may each hold an
     * override on one victim as independent pairs.
     *
     * <p>Call from the victim's owning region thread.
     */
    void overrideWindow(UUID victim, UUID attacker, double factor, int durationTicks);

    /** Whether an override is live for the pair right now. Any thread. */
    boolean isActive(UUID victim, UUID attacker);

    /** Drops one pair's override early (a consumer's own cancel path). */
    void clear(UUID victim, UUID attacker);

    /**
     * Drops every override touching {@code victim} — death/quit hygiene on the
     * victim side. Mental clears the victim's pairs itself on the victim's death
     * or quit; a consumer calls this only for its own early cancels.
     */
    void clearVictim(UUID victim);
}
