package me.vexmc.mental.kernel.combo;

/**
 * Why an active combo ended — the kernel's own vocabulary for the
 * {@link ComboTracker}'s terminal transitions (combo-hold, design 2026-07-04).
 * Kept pure (no api dependency): the core maps this onto the public
 * {@code ComboEndEvent.Reason} at the fire site, so the kernel stays Bukkit-free
 * and the api enum can evolve independently.
 *
 * <ul>
 *   <li>{@link #EXPIRED} — the inter-hit gap exceeded {@code maxGapTicks}, or a
 *       different attacker took over the victim (the old chain is abandoned).</li>
 *   <li>{@link #RETALIATION} — the victim landed a melee hit of their own.</li>
 *   <li>{@link #GROUNDED} — {@code groundedRunTicks} consecutive grounded ticks
 *       (a real touchdown, not a brief ground-skim).</li>
 *   <li>{@link #BLOWOUT} — separation exceeded {@code blowoutBlocks}.</li>
 *   <li>{@link #RETIRED} — a party retired (session forget/quit).</li>
 *   <li>{@link #DISABLED} — the module was turned off (reload).</li>
 * </ul>
 */
public enum ComboEndReason {
    EXPIRED,
    RETALIATION,
    GROUNDED,
    BLOWOUT,
    RETIRED,
    DISABLED
}
