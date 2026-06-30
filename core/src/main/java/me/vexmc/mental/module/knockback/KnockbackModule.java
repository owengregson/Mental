package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.ocm.OcmMechanic;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 1.7.10 melee knockback.
 *
 * <p>The vector is computed synchronously at {@code MONITOR} damage priority
 * (after the compensation module's {@code HIGHEST} hint pass) from the
 * victim's {@link VictimMotion} residual — the legacy server's motion fields
 * — and submitted to the {@link KnockbackPipeline}, which swaps it into the
 * velocity event later the same tick. Always server-authoritative: the
 * velocity the client receives is the one the server's own pipeline
 * produced, which is what movement-prediction anticheats verify against.</p>
 */
public final class KnockbackModule extends CombatModule implements Listener {

    private final VictimMotion ledger;
    private final KnockbackPipeline pipeline;
    private volatile KnockbackHints hints = KnockbackHints.NONE;

    public KnockbackModule(
            @NotNull MentalServices services,
            @NotNull VictimMotion ledger,
            @NotNull KnockbackPipeline pipeline) {
        super(services, "knockback", "Knockback",
                "1.7.10 melee knockback: friction on the residual, sprint and enchant bonuses, combos.",
                DebugCategory.KNOCKBACK);
        this.ledger = ledger;
        this.pipeline = pipeline;
    }

    @Override
    public boolean configEnabled() {
        return services.config().knockback().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {}

    /** Wired by the bootstrap once the compensation module exists. */
    public void hints(@NotNull KnockbackHints hints) {
        this.hints = hints;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only shield-absorption signal Bukkit has
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        KnockbackSettings settings = services.config().knockback();
        if (!settings.enabled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (MeleeReentryGuard.active()) {
            // A rod (or other module) is dealing this ENTITY_ATTACK through
            // victim.damage(victim, source) — not a melee hit. The dealing module
            // owns the knock and the attacker-side bookkeeping; treating it as
            // melee would self-slow/clear the wrong player and strand a MELEE
            // pending the source's own submit clobbers.
            return;
        }
        KnockbackProfile profile = services.knockbackProfiles().resolve(victim);
        // The hit's registration instant — the wire stamp's nanos when the fast
        // path registered it, else now. The post-hit sprint clear is stamped
        // with this (not the deferred clear's wall-clock) so a wire re-press that
        // arrived after registration is never clobbered by a late Folia clear.
        long registrationNanos = System.nanoTime();
        try {
            // Era rule: blocking shaped DAMAGE only — knockBack had already
            // run when the sword-block halving applied, so a blocking victim
            // was knocked in FULL (decompiled both eras; measured on real
            // 1.8.9: blocked hit ships (0.3608, 0.4) at (1+8)×0.5 damage).
            // The cancel therefore fires only for FULL blocks (final damage
            // zero — the modern shield, which vanilla itself never knocks
            // through); a partial reduction (OCM sword-block, shield chip
            // damage) knocks like the era.
            if (profile.shieldBlockingCancels()
                    && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                    && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0
                    && event.getFinalDamage() <= 0.0) {
                // Withdraw any pre-delivered pending so it cannot be adopted by an
                // unrelated later velocity event or inherited by the next hit
                // (vanilla never knocks through a full modern-shield block).
                pipeline.withdraw(victim, KnockbackPipeline.Cause.MELEE);
                debug.log(() -> victim.getName() + " fully blocked — knockback skipped");
                return;
            }
            // OCM's ownership rule for offensive mechanics: the attacker's modeset
            // decides (the victim's when a mob attacks). Where OCM's
            // old-player-knockback governs this hit, its LOWEST velocity handler
            // applies the 1.8 knock unopposed — Mental submits nothing.
            Player decider = attacker instanceof Player attackerPlayer ? attackerPlayer : victim;
            if (services.ocmGate().handles(OcmMechanic.MELEE_KNOCKBACK, decider)) {
                // OCM applies the knock from its own LOWEST handler; drop any
                // pre-delivered pending so Mental neither double-stamps nor leaks
                // it onto the next hit.
                pipeline.withdraw(victim, KnockbackPipeline.Cause.MELEE);
                debug.log(() -> "OCM owns melee knockback for " + decider.getName() + " — yielding");
                return;
            }

            // The sprint verdict as the ATTACK saw it: the fast path stamps
            // it at registration — the wire view in packet-arrival order
            // when the wtap-registration module is on, the tick-frozen
            // snapshot otherwise — because a faithful client's own
            // post-attack sprint drop beats the deferred damage to the
            // server (vanilla read the flag inline in Player.attack, ahead
            // of that packet). No stamp = vanilla-path hit = the live flag
            // is the one vanilla's own attack already evaluated.
            SprintTracker.AttackVerdict verdict = attacker instanceof Player attackerPlayer
                    ? services.sprintTracker().takeAttackVerdict(
                            attackerPlayer.getUniqueId(), System.nanoTime())
                    : null;
            if (verdict != null) {
                registrationNanos = verdict.nanos();
            }
            boolean attackerSprinting = attacker instanceof Player attackerPlayer
                    && (verdict != null ? verdict.sprinting() : attackerPlayer.isSprinting());

            // The netty fast path may have already computed AND wire-delivered
            // this hit's vector; adopt it rather than recomputing — recomputing
            // here would double-stamp the client with a slightly different value
            // (the era servers stamped once). Spend the sprint freshness the
            // pre-send only peeked at, so the tracker state stays truthful.
            if (pipeline.hasFreshPreDelivered(victim, attacker)) {
                if (attackerSprinting && attacker instanceof Player attackerPlayer) {
                    spendFreshness(attackerPlayer);
                }
                mirrorAttackerSelfSlow(attacker, attackerSprinting);
                debug.log(() -> "adopting pre-delivered knockback for " + victim.getName());
                return;
            }

            Double victimYOverride = hints.takeYOverride(victim.getUniqueId());
            EntityState victimState = EntityState.captureVictim(victim, ledger, System.nanoTime());
            // The authoritative read spends the attacker's sprint freshness
            // (both ledgers — the pre-send only peeked) and prefers the
            // registration-stamped wire answer over the Bukkit ledger, so
            // prediction and truth see one answer however the tap raced the
            // tick boundary.
            boolean bukkitFresh = attackerSprinting
                    && attacker instanceof Player attackerPlayer
                    && spendFreshness(attackerPlayer);
            boolean freshSprint = verdict != null && verdict.fresh() != null
                    ? attackerSprinting && verdict.fresh()
                    : bukkitFresh;
            KnockbackVector vector = KnockbackEngine.compute(
                    EntityState.capture(attacker, attackerSprinting), victimState, profile,
                    victimYOverride, ThreadLocalRandom.current(), freshSprint);

            pipeline.submit(victim, vector, attacker, KnockbackPipeline.Cause.MELEE);
            mirrorAttackerSelfSlow(attacker, attackerSprinting);
            debug.log(() -> vector == null
                    ? "legacy resistance cancelled knockback for " + victim.getName()
                    : "queued for " + victim.getName()
                            + (victimYOverride != null ? " [compensated vy=" + victimYOverride + "]" : "")
                            + " residual=(" + victimState.vx() + ", " + victimState.vy() + ", " + victimState.vz() + ")"
                            + " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")");
        } finally {
            clearVanillaSprint(attacker, registrationNanos);
        }
    }

    /**
     * A protection plugin cancelling the melee hit also withdraws the queued
     * knock — mirroring {@code ProjectileKnockbackModule}. Without it a netty
     * pre-send's pending lingers the full expiry window (longer on Folia) and
     * the next melee hit's {@code hasFreshPreDelivered} adopts the stale vector,
     * or an unrelated velocity event serves it and arms the suppressor against a
     * real packet.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMeleeDamageCancelled(@NotNull EntityDamageByEntityEvent event) {
        if (!event.isCancelled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (MeleeReentryGuard.active()) {
            return; // rod re-entry: never a Mental MELEE pending to withdraw
        }
        pipeline.withdraw(victim, KnockbackPipeline.Cause.MELEE);
    }

    /**
     * Vanilla parity for the other server-side write inside the bonus-
     * knockback branch: {@code attack} ends every such hit with
     * {@code motX *= 0.6; motZ *= 0.6} on the SERVER's copy of the
     * attacker's motion (both eras, beside the sprint clear). The client
     * does its own 0.6 independently — untouchable — but the server write
     * shapes the attacker's RESIDUAL machine: a player knocked mid-trade
     * who counter-hits halves their own ledger fields, so the next knock
     * they receive compounds off the smaller residual (1.7.10 trades).
     */
    private void mirrorAttackerSelfSlow(@NotNull LivingEntity attacker, boolean attackerSprinting) {
        if (!(attacker instanceof Player attackerPlayer)) {
            return;
        }
        boolean bonusKnockback = attackerSprinting
                || heldKnockbackLevel(attackerPlayer) > 0;
        if (!bonusKnockback) {
            return;
        }
        ledger.scaleHorizontal(
                attackerPlayer.getUniqueId(), 0.6, System.nanoTime(),
                Attributes.valueOr(attackerPlayer, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY));
    }

    /**
     * Spends both freshness ledgers — the Bukkit-armed set and the wire
     * view's armed flag — reporting the Bukkit one's answer for hits whose
     * registration left no wire opinion.
     */
    private boolean spendFreshness(@NotNull Player attacker) {
        services.sprintTracker().consumeWireFresh(attacker.getUniqueId());
        return services.sprintTracker().consumeFresh(attacker.getUniqueId());
    }

    private static int heldKnockbackLevel(@NotNull Player player) {
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

    /**
     * Vanilla parity for the hit the fast path swallowed: {@code Player.attack}
     * ends every sprint-bonus hit with {@code setSprinting(false)} — the
     * server half of the w-tap mechanic. The fast path cancels the attack
     * packet, so {@code Player.attack} never runs and the flag would stay
     * true for every follow-up hit: a no-w-tap second hit then keeps the
     * sprint extra that the era denied it (measured on real 1.8.9: hit 2
     * without a w-tap ships h 0.4, not 0.9 — w-tapping must matter).
     * A real client also drops its own state and re-syncs via entity-action,
     * but only a ping later — the era cleared it within the hit's tick.
     * Runs after every use of the flag in this handler (engine input and
     * freshness reads happen above), on the attacker's owning thread.
     */
    private void clearVanillaSprint(@NotNull LivingEntity attacker, long registrationNanos) {
        if (attacker instanceof Player attackerPlayer && attackerPlayer.isSprinting()) {
            UUID id = attackerPlayer.getUniqueId();
            // The wire view clears immediately — the era flag dropped the
            // instant the bonus branch ran, while the runOn task may land a
            // tick later (several ticks on Folia); without this an attack
            // registering in the gap would read a sprint the era had already
            // spent. Stamped with the hit's registration nanos so a newer wire
            // re-press is not clobbered by a late clear.
            services.sprintTracker().clearWireSprint(id, registrationNanos);
            // The live flag stays stale-high until the deferred clear lands;
            // mark it pending so the per-tick reconcile does not readopt it and
            // resurrect the cleared sprint. Resolved when the clear runs (or the
            // attacker retires first).
            services.sprintTracker().markClearPending(id, registrationNanos);
            services.scheduling().runOn(
                    attackerPlayer,
                    () -> {
                        attackerPlayer.setSprinting(false);
                        services.sprintTracker().resolveClearPending(id);
                    },
                    () -> services.sprintTracker().resolveClearPending(id));
        }
    }
}
