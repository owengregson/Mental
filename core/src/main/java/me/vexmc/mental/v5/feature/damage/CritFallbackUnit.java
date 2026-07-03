package me.vexmc.mental.v5.feature.damage;

import java.util.function.Supplier;
import me.vexmc.mental.platform.CritPosture;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The 1.8 critical-hit rule for hits the fast path does NOT compose (the retired
 * {@code module.damage.CritFallbackModule} on the v5 seams). When the fast path
 * is on, its {@link DamageShaper} already injects the era crit inline (so this
 * unit returns immediately); when it is off, a player's falling melee should
 * still crit for ×1.5 with no sprint/cooldown gate (era rule), which vanilla's
 * 1.9 crit (sprint-excluded, cooldown-gated) would otherwise deny.
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

    public CritFallbackUnit(DamageOwnership ownership, Supplier<Snapshot> snapshot) {
        this.ownership = ownership;
        this.snapshot = snapshot;
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
        // Fast-path-OFF scope only: the fast path's DamageShaper already composed
        // the era crit inline on hits it registered.
        if (fastPathEnabled()) {
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
    }

    @SuppressWarnings("unchecked")
    private boolean fastPathEnabled() {
        Snapshot current = snapshot.get();
        if (current == null) {
            return true; // no snapshot ⇒ assume the fast path owns crits (the safe default)
        }
        HitRegSettings hitReg = current.settings(
                (SettingsKey<HitRegSettings>) Feature.HIT_REGISTRATION.settingsKey());
        return current.enabled(Feature.HIT_REGISTRATION) && hitReg.fastPath();
    }
}
