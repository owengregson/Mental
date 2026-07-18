/**
 * Mental's public combat-integration surface (API generation 3).
 *
 * <p>The division of ownership is: <b>Mental publishes combat truth; integrators
 * defer/shape their own effects.</b> Mental never holds, delays, or re-mints a
 * foreign plugin's events or damage. The following policy contracts are part of
 * the surface, not incidental behaviour.</p>
 *
 * <h2>1. Era-authenticity stance</h2>
 * <p>Vanilla periodic damage (fire tick, poison, wither, drowning…) interacts
 * with combos exactly as 1.8 vanilla — it CAN break them, and Mental will never
 * defer or suppress it. Custom plugin damage is the plugin's own responsibility,
 * using the combo events, the {@link me.vexmc.mental.api.MentalCombat} queries,
 * and the ordering contract below.</p>
 *
 * <h2>2. Foreign-window behaviour</h2>
 * <p>Foreign damage landed mid-combo re-arms the victim's hurt window; the next
 * era knock is then window-swallowed and the combo's motion chain breaks. That
 * failure chain is precisely WHY integrators defer their own effects: Mental
 * publishes combat truth, and the integrator defers or shapes its own effects
 * around it — the ownership split. An integrator that fires its damage/effects
 * blindly mid-combo re-arms the window itself and breaks the very chain it meant
 * to reward.</p>
 *
 * <h2>3. The D1/D2 feed rulings</h2>
 * <p><b>D1:</b> combo advancement is fed only from a <em>confirmed-shipped</em>
 * knock — the MONITOR confirm of the victim's {@code PlayerVelocityEvent}, gated
 * on the event not being cancelled. A foreign plugin that cancels that velocity
 * event advances no chain: the client never received the knock, so no combo
 * event fires for it. <b>D2:</b> a natively blocked melee hit that still ships an
 * era knock DOES qualify — it re-delivers through the same authoritative velocity
 * seam, so blocked cadence forms and sustains a combo exactly like an unblocked
 * one.</p>
 *
 * <h2>4. The ordering contract</h2>
 * <p>Combo advancement is fed at the MONITOR confirm of the victim's
 * {@code PlayerVelocityEvent}, after the victim's {@code EntityDamageEvent} for
 * that hit has completed its entire handler chain; therefore <b>a query made from
 * inside any {@code EntityDamageEvent} handler observes the pre-hit combo
 * state</b>. All combo events for that hit fire later in the same tick, on the
 * same thread.</p>
 *
 * <p><b>Fold-eligibility.</b> At fold time, {@code comboOn(victim)} answering
 * ACTIVE <em>or</em> DEVELOPING with {@code attackerId()} equal to this hit's
 * attacker means the hit continues (or is about to promote) that chain, and
 * banked damage belonging to that attacker is fold-eligible into this hit. The
 * DEVELOPING arm matters precisely on the promotion hit (its pre-hit state is
 * DEVELOPING — {@code active} flips only after the feed); a consumer folding only
 * on ACTIVE would starve its developing-window bank into the end-release path.
 * Only NONE, or a different attacker, is not fold-eligible.</p>
 */
package me.vexmc.mental.api;
