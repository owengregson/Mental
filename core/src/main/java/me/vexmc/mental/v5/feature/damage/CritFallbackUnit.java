package me.vexmc.mental.v5.feature.damage;

import java.util.function.Supplier;
import me.vexmc.mental.platform.CritPosture;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The 1.8 critical-hit rule for hits the fast path does NOT compose (the retired
 * {@code module.damage.CritFallbackModule} on the v5 seams). A hit the fast path
 * MINTED already carries the era crit — its {@link DamageShaper} composed it
 * inline — so this unit yields to exactly those hits, PER HIT (interaction audit
 * C2: the old global fast-path-enabled read also skipped every vanilla-landing
 * melee while the knob was on — Folia mob combat, stale/absent-view victims,
 * packetless attackers — leaving vanilla's sprint-excluded, cooldown-gated 1.9
 * crit in charge with this feature explicitly enabled). Any melee that reaches
 * the vanilla {@code Player#attack} path gets the era ×1.5 fold here, whether
 * the fast path is globally on or not.
 *
 * <p>Crit/tool-damage ownership resolves through the SAME {@link DamageOwnership}
 * the fast path holds (mandate §4.6): the forgotten-gate bug — the retired
 * fallback module omitted the OCM gate the fast path had — is structurally dead,
 * because both paths read one verdict source. When OCM owns crits for the
 * attacker, this unit yields so OCM stays the single source of truth.</p>
 */
public final class CritFallbackUnit implements FeatureUnit, Listener {

    private final DamageOwnership ownership;
    private final Supplier<Snapshot> snapshot;
    private final SessionService sessions;

    public CritFallbackUnit(DamageOwnership ownership, Supplier<Snapshot> snapshot, SessionService sessions) {
        this.ownership = ownership;
        this.snapshot = snapshot;
        this.sessions = sessions;
    }

    /** The shared crit/tool-damage verdict source — identical instance to the fast-path shaper. */
    public DamageOwnership ownership() {
        return ownership;
    }

    @Override
    public Feature descriptor() {
        return Feature.CRIT_FALLBACK;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // the granular BASE modifier is the only pre-armour hook Bukkit exposes
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        // Per-hit fast-path gate (audit C2): yield ONLY when THIS event is a hit
        // the fast path minted (DamageShaper composed it, era crit included). The
        // truth is the session transaction slots the fast path brackets its
        // damage() calls with — never the global config knob, whose coverage is
        // per-hit in reality.
        if (fastPathMinted(event, attacker)) {
            return;
        }
        // The shared verdict: OCM owns crits for this attacker → yield entirely.
        if (!ownership.mentalOwnsCriticalHits(attacker.getUniqueId())) {
            return;
        }
        // Era crit posture (shared predicate with the fast path). Sprinting does
        // not exclude a legacy crit.
        if (!DamageShaper.isLegacyCritical(attacker)) {
            return;
        }
        // Vanilla already applied its ×1.5 (cooldown ≥ 0.9 AND not sprinting) —
        // do not double-crit. CritPosture.attackCharge, not attacker.getAttackCooldown(): the Bukkit
        // accessor floors at 1.15.2 (absent 1.9.4–1.13.2), so a direct call throws there; the resolver
        // uses the NMS getCooledAttackStrength delegate where it resolves (1.13.2) and a fully-charged
        // default below (defers to vanilla's crit for non-sprint hits — never double-crits).
        if (CritPosture.attackCharge(attacker) > 0.9f && !attacker.isSprinting()) {
            return;
        }
        double base = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        event.setDamage(EntityDamageEvent.DamageModifier.BASE, base * 1.5);
        // Order-independence with the era armour cascade (interaction audit): both
        // units share the LOWEST bucket, whose intra-priority order is registration
        // order — Feature declaration order on first boot, silently REORDERED by a
        // GUI toggle (unregisterAll + re-append). If ArmourStrengthUnit already ran,
        // its defensive modifiers were sized against the un-critted BASE and the
        // era rule is crit BEFORE armour (1.8 EntityHuman.attack multiplies before
        // damageEntity's armour) — so re-run the cascade over the raised BASE here.
        // Idempotent: when this unit ran first, the armour listener recomputes the
        // identical values afterwards. Gated on the feature so a crit with
        // old-armour-strength OFF composes with vanilla's model exactly as before.
        Snapshot current = snapshot.get();
        if (current != null && current.enabled(Feature.ARMOUR_STRENGTH)) {
            ArmourStrengthUnit.applyEraCascade(event);
        }
    }

    /**
     * Whether THIS event's damage was minted by the fast path (a {@link
     * HitSource.Melee} transaction). Player victims: the {@code DamageRouter}
     * (LOWEST, registered before every feature unit) has already established the
     * event's transaction from the victim session's {@code activeInbound} bracket
     * — a packetless/NPC melee arrives as {@code Vanilla(ENTITY_ATTACK)} and
     * falls through to the era fold. Non-player victims have no session, so the
     * fast path brackets the ATTACKER's session around its {@code damage()} call
     * (Paper mob hits are fast-path-composed too); no bracket ⇒ a genuine
     * vanilla {@code Player#attack} landing ⇒ the era crit applies.
     */
    private boolean fastPathMinted(EntityDamageByEntityEvent event, Player attacker) {
        if (event.getEntity() instanceof Player victim) {
            CombatSession session = sessions.sessionFor(victim.getUniqueId());
            HitTransaction tx = session == null ? null : session.currentEventTransaction();
            return tx != null && tx.context().source() instanceof HitSource.Melee;
        }
        CombatSession attackerSession = sessions.sessionFor(attacker.getUniqueId());
        HitTransaction inbound = attackerSession == null ? null : attackerSession.activeInbound();
        return inbound != null && inbound.context().source() instanceof HitSource.Melee;
    }
}
