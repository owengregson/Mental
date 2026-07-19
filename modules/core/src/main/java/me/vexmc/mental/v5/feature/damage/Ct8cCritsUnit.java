package me.vexmc.mental.v5.feature.damage;

import java.util.function.Supplier;
import me.vexmc.mental.kernel.math.DamageTables;
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
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

/**
 * Combat Test 8c critical hits (spec §2.3) — the {@code CritFallbackUnit} twin,
 * CT8c policy. A crit is a flat ×1.5 on the WHOLE BASE, so — because the enchant
 * additive is already inside the BASE ({@code Ct8cDamageUnit} folded Sharpness /
 * Cleaving / Impaling in) — the enchant is multiplied too, the exact inverse of
 * the era rule (spec §2.3: "vanilla adds it after"). The CT8c crit posture is the
 * era one <em>with sprint allowed</em>, which is precisely {@link
 * DamageShaper#isLegacyCritical} (falling, off-ground, not climbing, not in
 * water, not blind, not riding — no sprint exclusion).
 *
 * <p>Runs at {@link EventPriority#LOW}, one bucket after {@code Ct8cDamageUnit}'s
 * {@code LOWEST} BASE write, so the ×1.5 always lands on the finished CT8c base
 * regardless of listener registration order. Standalone (ct8c-crits without
 * ct8c-damage) it multiplies whatever BASE exists — the vanilla weapon damage.</p>
 *
 * <p><b>Not double-critting vanilla — the v2.9.0 inversion fixed.</b> The only
 * hit whose BASE can still carry vanilla's ×1.5 by the time this unit runs is a
 * genuine vanilla {@code Player#attack} landing (fully charged, NOT sprinting)
 * with {@code ct8c-damage} OFF — so the yield takes all three reads: a fast-path
 * minted hit never ran {@code Player#attack} (its BASE carries no vanilla crit),
 * and a {@code ct8c-damage} BASE was overwritten crit-free at {@code LOWEST}
 * (its write explicitly defers the crit here). The shipped guard checked only
 * charge + sprint, so on the always-on fast path — and in the whole ct8c bundle
 * — it yielded on exactly the canonical non-sprint jump crit and applied only
 * sprint crits: the inverse of the spec. Enabling both ct8c-crits and the era
 * {@code crit-fallback}, or the fast-path {@code simulate-crits}, double-crits;
 * the bundles keep them mutually exclusive.</p>
 *
 * <p>Zero-touch: assembled only when enabled; touches only player melee.</p>
 */
public final class Ct8cCritsUnit implements FeatureUnit, Listener {

    private final Supplier<Snapshot> snapshot;
    private final SessionService sessions;

    public Ct8cCritsUnit(Supplier<Snapshot> snapshot, SessionService sessions) {
        this.snapshot = snapshot;
        this.sessions = sessions;
    }

    @Override
    public Feature descriptor() {
        return Feature.CT8C_CRITS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // the granular BASE modifier is the only pre-armour amount hook Bukkit exposes
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        // CT8c crit posture = the era posture with sprint allowed (spec §2.3).
        if (!DamageShaper.isLegacyCritical(attacker)) {
            return;
        }
        // Yield ONLY where vanilla's ×1.5 can actually still be inside BASE: a
        // genuine vanilla Player#attack landing (never a fast-path minted hit —
        // damage() bypasses vanilla's crit entirely) whose BASE ct8c-damage did
        // NOT overwrite crit-free at LOWEST, on vanilla's own crit terms (fully
        // charged AND not sprinting). CritPosture.attackCharge is the
        // boot-resolved accessor (fully-charged default on the legacy tier).
        Snapshot current = snapshot.get();
        boolean baseIsCt8cComposed = current != null && current.enabled(Feature.CT8C_DAMAGE);
        if (!baseIsCt8cComposed
                && !DamageShaper.fastPathMinted(sessions, event, attacker)
                && CritPosture.attackCharge(attacker) > 0.9f && !attacker.isSprinting()) {
            return;
        }
        // The flat ×1.5 (spec §2.3), sourced from the kernel so nothing re-derives it.
        double base = event.getDamage(DamageModifier.BASE);
        event.setDamage(DamageModifier.BASE, base * DamageTables.critMultiplier());
    }
}
