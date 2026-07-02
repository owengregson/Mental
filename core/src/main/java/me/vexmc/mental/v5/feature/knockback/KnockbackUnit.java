package me.vexmc.mental.v5.feature.knockback;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.CompensationQuery;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 1.7.10 melee knockback (the retired {@code KnockbackModule} on the v5 seams).
 * A pure vector computer: it reads the event's transaction from the
 * {@code DamageRouter} slot, computes the engine vector from the victim's
 * {@link me.vexmc.mental.kernel.ledger.MotionLedger} residual, and submits it to
 * the victim's {@link DeliveryDesk} — which swaps it into the velocity event and
 * records what shipped (spec §3.4–§3.5). A fast-path hit that already carried a
 * pre-delivered vector is <em>adopted</em>, not recomputed (the era servers
 * stamped once).
 *
 * <p>Dispatch is on the typed {@link HitSource}: a {@code RodPull} is never
 * treated as melee (B6); a server-side melee that never hit the fast path arrives
 * as {@code Vanilla(ENTITY_ATTACK)} and is knocked. Accepted sprint-bonus hits run
 * the vanilla obligations the cancelled attack left behind — the attacker's
 * {@code setSprinting(false)}, its ledger {@code ×0.6} self-slow, and the wire
 * sprint clear.</p>
 */
public final class KnockbackUnit implements FeatureUnit, Listener {

    private final SessionService sessions;
    private final ConnectionDomains domains;
    private final OcmBinding ocmBinding;
    private final LatencyModel latency;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;

    public KnockbackUnit(
            SessionService sessions, ConnectionDomains domains, OcmBinding ocmBinding,
            LatencyModel latency, Scheduling scheduling, Supplier<Snapshot> snapshot) {
        this.sessions = sessions;
        this.domains = domains;
        this.ocmBinding = ocmBinding;
        this.latency = latency;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
    }

    @Override
    public Feature descriptor() {
        return Feature.KNOCKBACK;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only shield-absorption signal Bukkit has
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx == null) {
            return;
        }
        HitSource source = tx.context().source();
        if (source instanceof HitSource.RodPull) {
            return; // a rod deals ENTITY_ATTACK through victim.damage(rodder) — not melee (B6)
        }
        boolean melee = source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
        if (!melee) {
            return;
        }

        DeliveryDesk desk = session.desk();
        // A cross-region attacker (Folia boundary straddle / pearl in the dispatch
        // tick): skip before any attacker read — the swallowed-throw trap.
        if (!scheduling.isOwnedByCurrentRegion(attacker)) {
            desk.withdraw(tx.context().id());
            return;
        }
        KnockbackProfile profile = profileFor(session, victim);
        // Era rule: blocking shaped DAMAGE only (knockBack ran first), so a
        // blocking victim was knocked in FULL — cancel only for a FULL block
        // (final damage zero, the modern shield vanilla itself never knocks
        // through); a partial reduction knocks like the era.
        if (profile.shieldBlockingCancels()
                && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0
                && event.getFinalDamage() <= 0.0) {
            desk.withdraw(tx.context().id());
            return;
        }
        // OCM owns melee knockback for this attacker's modeset — yield entirely.
        UUID decider = attacker instanceof Player ? attacker.getUniqueId() : victim.getUniqueId();
        if (!ocmBinding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, decider)) {
            desk.withdraw(tx.context().id());
            return;
        }

        boolean sprinting = attackerSprinting(source, tx, attacker);

        HitTransaction.State state = tx.state();
        if (state == HitTransaction.State.PRE_SENT || state == HitTransaction.State.PINNED) {
            // Adopt the pre-delivered vector — the era stamped once.
            desk.awaitVelocityEvent(tx);
            applyAttackerObligations(attacker, sprinting);
            return;
        }

        Double compensationY = compensationFor(source, tx, session, victim);
        EntityState victimState = EntityStates.captureVictim(victim, session.ledger());
        EntityState attackerState = EntityStates.capture(attacker, sprinting);
        boolean freshSprint = source instanceof HitSource.Melee && sprinting
                && tx.context().sprint().fresh() != null && tx.context().sprint().fresh();
        KnockbackVector vector = KnockbackEngine.compute(
                attackerState, victimState, profile, compensationY,
                ThreadLocalRandom.current(), freshSprint);

        desk.submit(tx, vector);
        desk.awaitVelocityEvent(tx);
        applyAttackerObligations(attacker, sprinting);
    }

    /** A protection plugin cancelling the melee hit withdraws the queued knock. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMeleeCancelled(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx != null && !(tx.context().source() instanceof HitSource.RodPull)) {
            session.desk().withdraw(tx.context().id());
        }
    }

    private boolean attackerSprinting(HitSource source, HitTransaction tx, LivingEntity attacker) {
        if (source instanceof HitSource.Melee) {
            SprintVerdict verdict = tx.context().sprint();
            return verdict != null && verdict.sprinting();
        }
        return attacker instanceof Player player && player.isSprinting();
    }

    private Double compensationFor(
            HitSource source, HitTransaction tx, CombatSession session, Player victim) {
        if (source instanceof HitSource.Melee) {
            return tx.context().compensationY(); // compute-once from the fast path
        }
        if (!snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
            return null;
        }
        PlayerView view = session.view();
        if (view == null) {
            return null;
        }
        Double ping = latency.forPlayer(victim.getUniqueId()).pingMillis();
        int rtt = ping == null ? 0 : (int) Math.round(ping);
        return CompensationQuery.verticalFor(view, rtt, view.motion().vy());
    }

    /**
     * The vanilla obligations the cancelled attack left behind, for an accepted
     * sprint-bonus hit: the attacker's ledger {@code ×0.6} self-slow, the wire
     * sprint clear, and {@code setSprinting(false)} (a no-op when vanilla's own
     * attack already cleared it on the server-side melee path).
     */
    private void applyAttackerObligations(LivingEntity attacker, boolean sprinting) {
        if (!(attacker instanceof Player player)) {
            return;
        }
        boolean bonus = sprinting || heldKnockbackLevel(player) > 0;
        if (!bonus) {
            return;
        }
        CombatSession attackerSession = sessions.sessionFor(player.getUniqueId());
        if (attackerSession != null) {
            attackerSession.ledger().scaleHorizontal(0.6);
        }
        domains.domainFor(player.getUniqueId()).sprint().onServerClear();
        if (player.isSprinting()) {
            scheduling.runOn(player, () -> player.setSprinting(false), () -> {});
        }
    }

    private KnockbackProfile profileFor(CombatSession session, Player victim) {
        PlayerView view = session.view();
        return view != null ? view.profile() : snapshot.get().profileFor(victim.getWorld().getName());
    }

    private static int heldKnockbackLevel(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() != Material.AIR) {
            return main.getEnchantmentLevel(Enchantments.knockback());
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() == Material.AIR) {
            return 0;
        }
        return off.getEnchantmentLevel(Enchantments.knockback());
    }
}
