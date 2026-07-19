/**
 * Mental's temporary hit-timing override surface (API generation 3).
 *
 * <p>One service — {@link me.vexmc.mental.api.timing.HitTimingOverrides} — through
 * which a third party may TEMPORARILY re-price a victim's hit-admission window for
 * ONE attacker, inside Mental's own timing math. It exists so no consumer ever
 * writes {@code noDamageTicks} around Mental's model and fights the pipeline that
 * owns it. The window pricing is per-(victim, attacker): a third attacker's
 * admission math is untouched, so nothing global is erased and no third-party
 * fairness guard is required.</p>
 *
 * <p>The service is discovered through Bukkit's {@code ServicesManager} — the
 * registration is the capability signal (consumers probe the registration, never
 * a class or version string). All descriptor types are Java-8 (UUID, primitives),
 * so the surface crosses a cross-plugin API boundary cleanly.</p>
 */
package me.vexmc.mental.api.timing;
