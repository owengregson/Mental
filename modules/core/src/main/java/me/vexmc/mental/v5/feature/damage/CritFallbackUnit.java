package me.vexmc.mental.v5.feature.damage;

import java.util.function.Supplier;
import me.vexmc.mental.platform.CritPosture;
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
 */
public final class CritFallbackUnit implements FeatureUnit, Listener {

    private final Supplier<Snapshot> snapshot;
    private final SessionService sessions;

    public CritFallbackUnit(Supplier<Snapshot> snapshot, SessionService sessions) {
        this.snapshot = snapshot;
        this.sessions = sessions;
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
        if (DamageShaper.fastPathMinted(sessions, event, attacker)) {
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

}
